package com.zzu.lite.data.repo

import com.zzu.lite.data.local.CredentialsStore
import com.zzu.lite.data.remote.ApiConst
import com.zzu.lite.data.remote.CasService
import com.zzu.lite.data.remote.dto.ApiEnvelope
import com.zzu.lite.data.remote.dto.LoginData
import com.zzu.lite.data.remote.dto.MfaGidBody
import com.zzu.lite.data.remote.dto.MfaValidBody
import com.zzu.lite.domain.LoginException
import com.zzu.lite.domain.MfaException
import com.zzu.lite.domain.NetworkException
import com.zzu.lite.domain.NotLoggedInException
import com.zzu.lite.domain.ParsingException
import com.zzu.lite.domain.util.Crypto
import com.zzu.lite.domain.util.Jwt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.PublicKey

/**
 * CAS 统一认证仓库：完整复刻 ZZU.Py CASClient + MFAClient 行为。
 *
 * 关键点：
 * - 登录/MFA 参数全部在 query string、body 为空
 * - 账号/密码 RSA(PKCS#1 v1.5) 加密 + "__RSA__" 前缀
 * - MFA：detect -> initByType/securephone -> securephone/send -> securephone/valid(status==2)
 * - 无 refresh 接口：token 剩余寿命 <15 分钟即用记住的密码静默重登
 */
class AuthRepository(
    private val cas: CasService,
    private val tokenStore: CredentialsStore
) {

    private val mutex = Mutex()

    @Volatile
    private var cachedPublicKey: PublicKey? = null

    /** MFA 会话（进程内） */
    @Volatile
    var mfaState: String? = null
        private set

    /** 发送验证码时初始化得到的会话 ID，校验时必须复用同一 gid */
    @Volatile
    private var mfaGid: String? = null

    @Volatile
    private var attestBaseUrl: String? = null

    @Volatile
    private var encUsername: String? = null

    @Volatile
    private var encPassword: String? = null

    var onTokensChanged: ((String) -> Unit)? = null

    val hasStoredCredentials: Boolean
        get() = !tokenStore.username.isNullOrBlank() && tokenStore.rememberPassword && !tokenStore.password.isNullOrBlank()

    suspend fun publicKey(): PublicKey {
        cachedPublicKey?.let { return it }
        val resp = try {
            cas.publicKey()
        } catch (e: Exception) {
            throw NetworkException("获取登录公钥失败：${e.message ?: e.javaClass.simpleName}")
        }
        if (!resp.isSuccessful) throw NetworkException("获取登录公钥失败：HTTP ${resp.code()}")
        val pem = resp.body()?.string() ?: throw ParsingException("登录公钥响应为空")
        return runCatching { Crypto.parsePemPublicKey(pem) }
            .getOrElse { throw ParsingException("登录公钥解析失败：${it.message}") }
            .also { cachedPublicKey = it }
    }

    /** 加密账号与密码，供 detect/login 复用（MFA 会话内保持一致） */
    suspend fun encryptCredentials(username: String, password: String): Pair<String, String> {
        val key = publicKey()
        val encUser = Crypto.encryptAndEncode(key, username)
        val encPass = Crypto.encryptAndEncode(key, password)
        this.encUsername = encUser
        this.encPassword = encPass
        return encUser to encPass
    }

    /**
     * 登录第一步：检测是否需要 MFA。
     * @return true = 需要短信验证（已自动发送验证码）；false = 可直接登录
     */
    suspend fun beginLogin(username: String, password: String): Boolean {
        encryptCredentials(username, password)
        val encUser = encUsername ?: throw IllegalStateException()
        val encPass = encPassword ?: throw IllegalStateException()
        val detect = try {
            cas.mfaDetect(encUser, encPass, tokenStore.deviceId)
        } catch (e: Exception) {
            throw NetworkException("登录检测失败：${e.message ?: e.javaClass.simpleName}")
        }
        requireCode(detect, "登录检测")
        val data = detect.data ?: throw ParsingException("登录检测响应缺少数据")
        mfaState = data.state
        if (!data.need) return false
        sendSms()
        return true
    }

    /** 初始化手机 MFA 会话并发送短信验证码（缓存 gid 供校验复用） */
    suspend fun sendSms() {
        val state = mfaState ?: throw MfaException("MFA 状态缺失，请重新开始登录")
        val init = try {
            cas.mfaInit(state)
        } catch (e: Exception) {
            throw NetworkException("MFA 初始化失败：${e.message ?: e.javaClass.simpleName}")
        }
        requireCode(init, "MFA 初始化")
        val initData = init.data ?: throw ParsingException("MFA 初始化响应缺少数据")
        val attest = initData.attestServerUrl.ifBlank { ApiConst.DEFAULT_ATTEST }.trimEnd('/')
        val sendUrl = "$attest/api/guard/securephone/send"
        val send = try {
            cas.mfaSend(sendUrl, MfaGidBody(initData.gid))
        } catch (e: Exception) {
            throw NetworkException("发送验证码失败：${e.message ?: e.javaClass.simpleName}")
        }
        requireCode(send, "发送验证码")
        mfaGid = initData.gid
        attestBaseUrl = attest
    }

    /** 校验短信验证码（复用发送时会话的 gid）；成功后 mfaState 可用于登录 */
    suspend fun verifySms(code: String) {
        val gid = mfaGid ?: throw MfaException("请先获取短信验证码")
        val attest = attestBaseUrl ?: ApiConst.DEFAULT_ATTEST.trimEnd('/')
        val validUrl = "$attest/api/guard/securephone/valid"
        val valid = try {
            cas.mfaValid(validUrl, MfaValidBody(gid, code))
        } catch (e: Exception) {
            throw NetworkException("验证码校验失败：${e.message ?: e.javaClass.simpleName}")
        }
        requireCode(valid, "验证码校验")
        val vd = valid.data
        if (vd == null || vd.status != 2) {
            throw LoginException("验证码错误或已过期，请重试")
        }
    }

    /** 用已加密的凭据完成登录（MFA 校验通过后 mfaState 生效） */
    suspend fun completeLogin(): LoginData {
        val encUser = encUsername ?: throw LoginException("请先输入账号密码")
        val encPass = encPassword ?: throw LoginException("请先输入账号密码")
        val resp = try {
            cas.passwordLogin(
                username = encUser,
                password = encPass,
                deviceId = tokenStore.deviceId,
                mfaState = mfaState.orEmpty()
            )
        } catch (e: Exception) {
            throw NetworkException("登录请求失败：${e.message ?: e.javaClass.simpleName}")
        }
        requireCode(resp, "登录")
        val data = resp.data ?: throw ParsingException("登录响应缺少数据")
        if (data.idToken.isBlank() || data.refreshToken.isBlank()) {
            throw ParsingException("登录响应缺少 token")
        }
        if (Jwt.expirationEpochSec(data.idToken) == null || Jwt.expirationEpochSec(data.refreshToken) == null) {
            throw ParsingException("登录返回的 token 无法解析")
        }
        tokenStore.saveTokens(data.idToken, data.refreshToken)
        onTokensChanged?.invoke(data.idToken)
        return data
    }

    /** 一键登录（无需 MFA 场景） */
    suspend fun login(username: String, password: String): LoginData {
        val needMfa = beginLogin(username, password)
        if (needMfa) throw MfaException("需要短信验证码")
        return completeLogin()
    }

    /**
     * 确保可用的 userToken：有效直接返回；否则用记住的密码静默重登。
     * @throws NotLoggedInException 无有效 token 且无法静默重登（应引导到登录页）
     */
    suspend fun ensureUserToken(): String = mutex.withLock {
        val current = tokenStore.userToken
        if (Jwt.isValidFor(current)) return current ?: throw NotLoggedInException()

        val user = tokenStore.username
        val pass = tokenStore.password
        if (!tokenStore.rememberPassword || user.isNullOrBlank() || pass.isNullOrBlank()) {
            throw NotLoggedInException()
        }
        runCatching {
            login(user, pass)
        }.onFailure { e ->
            tokenStore.userToken = null
            if (e is MfaException) {
                // 新设备策略变化触发 MFA：静默重登不可行，引导用户手动登录
                throw NotLoggedInException("登录已过期，请重新登录（需要短信验证）")
            }
            throw e
        }
        val fresh = tokenStore.userToken ?: throw NotLoggedInException()
        fresh
    }

    /** 退出登录：清除本地凭据与 token */
    fun logout() {
        tokenStore.clearAll()
        mfaState = null
        mfaGid = null
        attestBaseUrl = null
        encUsername = null
        encPassword = null
        cachedPublicKey = null
        onTokensChanged?.invoke("")
    }

    private fun <T> requireCode(envelope: ApiEnvelope<T>, what: String) {
        if (envelope.code != 0) {
            throw LoginException("${what}失败：${envelope.message ?: "未知错误(code=${envelope.code})"}")
        }
    }
}
