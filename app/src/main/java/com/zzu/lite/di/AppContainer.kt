package com.zzu.lite.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.glance.appwidget.updateAll
import com.zzu.lite.data.local.CacheStore
import com.zzu.lite.data.local.TokenStore
import com.zzu.lite.data.remote.CasService
import com.zzu.lite.data.remote.ECardService
import com.zzu.lite.data.remote.EasService
import com.zzu.lite.data.remote.InfoService
import com.zzu.lite.data.remote.PersonalService
import com.zzu.lite.data.remote.PortalClient
import com.zzu.lite.data.repo.AuthRepository
import com.zzu.lite.data.repo.ECardRepository
import com.zzu.lite.data.repo.ScheduleRepository
import com.zzu.lite.data.repo.UserRepository
import com.zzu.lite.domain.util.Jwt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** 登录会话状态 */
sealed class SessionState {
    data object LoggedOut : SessionState()
    data class LoggedIn(val uid: String = "") : SessionState()
}

/** 认证头注入：按目标域选择载体（对齐 ZZU.Py 各客户端的凭据用法） */
class AuthHeaderInterceptor(
    private val userTokenProvider: () -> String?,
    private val ecardTokenProvider: () -> String?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        val host = req.url.host
        val builder = req.newBuilder()
        when (host) {
            "jwxt.zzu.edu.cn" -> {
                val token = userTokenProvider()
                if (!token.isNullOrBlank()) {
                    builder.header("Authorization", token)
                    builder.header("Cookie", "userToken=$token")
                }
            }
            "info.s.zzu.edu.cn", "authx-service.s.zzu.edu.cn" -> {
                val token = userTokenProvider()
                if (!token.isNullOrBlank()) {
                    builder.header("X-Id-Token", token)
                }
            }
            "ecard.v.zzu.edu.cn" -> {
                if (req.url.encodedPath.contains("/server/utilities")) {
                    val token = ecardTokenProvider()
                    if (!token.isNullOrBlank()) {
                        builder.header("Authorization", token)
                    }
                }
            }
        }
        return chain.proceed(builder.build())
    }
}

/**
 * 手工依赖容器：网络客户端、各 Retrofit 服务与仓库。
 */
class AppContainer(context: Context) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val appContext = context.applicationContext

    /** 供 Worker / 调度器等非 UI 组件使用 */
    fun appContext(): Context = appContext

    val tokenStore = TokenStore(appContext)
    val cacheStore = CacheStore(appContext, json)

    /** 最新宿舍电费数据：服务页查询后发布，首页实时同步 */
    val roomEnergyState = MutableStateFlow<com.zzu.lite.data.repo.RoomEnergy?>(null)

    fun publishRoomEnergy(energy: com.zzu.lite.data.repo.RoomEnergy?) {
        roomEnergyState.value = energy
        if (energy != null) {
            appScope.launch {
                runCatching { cacheStore.saveEnergy(energy) }
                // 应用内查到电费后立即做提醒判断（不等后台巡检）
                runCatching { com.zzu.lite.notify.EnergyAlert.check(appContext, energy) }
            }
        }
    }

    /** 全局会话状态：启动时依据本地 token 有效性恢复 */
    private val _session = MutableStateFlow<SessionState>(
        if (Jwt.isValidFor(tokenStore.userToken)) SessionState.LoggedIn(tokenStore.username ?: "")
        else SessionState.LoggedOut
    )
    val session: StateFlow<SessionState> = _session

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(WifiPreferringSocketFactory(connectivityManager))
        .dns(WifiPreferringDns(connectivityManager))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(BigResponseTimeoutInterceptor())
        .addInterceptor(
            AuthHeaderInterceptor(
                userTokenProvider = { tokenStore.userToken },
                ecardTokenProvider = { ecardRepository.accessTokenOrNull() }
            )
        )
        .build()

    private val noRedirectClient: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient = okHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    val casService: CasService = retrofit("https://cas.s.zzu.edu.cn/").create(CasService::class.java)
    val easService: EasService = retrofit("https://jwxt.zzu.edu.cn/").create(EasService::class.java)
    val infoService: InfoService = retrofit("https://info.s.zzu.edu.cn/").create(InfoService::class.java)
    val personalService: PersonalService = retrofit("https://authx-service.s.zzu.edu.cn/").create(PersonalService::class.java)
    val ecardService: ECardService = retrofit("https://ecard.v.zzu.edu.cn/", noRedirectClient).create(ECardService::class.java)

    val portalClient = PortalClient(okHttpClient)

    val authRepository = AuthRepository(casService, tokenStore).apply {
        onTokensChanged = { token ->
            _session.value = if (Jwt.isValidFor(token)) {
                SessionState.LoggedIn(tokenStore.username ?: "")
            } else {
                SessionState.LoggedOut
            }
        }
    }

    val userRepository = UserRepository(personalService, infoService)

    val scheduleRepository = ScheduleRepository(easService, infoService, cacheStore, json)

    val classroomRepository = com.zzu.lite.data.repo.ClassroomRepository(easService)

    val ecardRepository = ECardRepository(
        ecard = ecardService,
        userTokenProvider = { authRepository.ensureUserToken() },
        cache = cacheStore
    )

    /** 应用内全局协程域（小组件刷新等后台任务） */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 启动时恢复上次电费查询结果（离线可见）
        appScope.launch {
            runCatching { cacheStore.loadEnergy() }.getOrNull()?.let { roomEnergyState.value = it }
        }
        // 电费提醒后台巡检（默认开启；关闭开关时由设置页取消）
        appScope.launch {
            if (runCatching { cacheStore.energyAlertEnabled() }.getOrDefault(true)) {
                runCatching { com.zzu.lite.notify.EnergyCheckScheduler.ensure(appContext) }
            }
        }
        scheduleRepository.onTimetableChanged = {
            appScope.launch {
                runCatching { com.zzu.lite.widget.TodayWidget().updateAll(appContext) }
            }
        }
    }


    fun logout() {
        authRepository.logout()
        ecardRepository.clearCache()
        _session.value = SessionState.LoggedOut
    }
}
