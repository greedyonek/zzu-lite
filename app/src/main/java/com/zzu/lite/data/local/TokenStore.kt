package com.zzu.lite.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 凭据存储：优先 EncryptedSharedPreferences（AES256-GCM 主密钥，硬件 Keystore 保护），
 * 初始化失败时降级普通 SharedPreferences（隐私提示见“关于”页）。
 */
class TokenStore(context: Context) : CredentialsStore {

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "zzu_lite_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            context.getSharedPreferences("zzu_lite_fallback", Context.MODE_PRIVATE)
        }
    }

    override var userToken: String?
        get() = prefs.getString(KEY_USER_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_USER_TOKEN, value).apply()

    override var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    override var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    /** 仅当用户勾选“记住密码”时保存，用于 token 过期后静默重登 */
    override var password: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    override var rememberPassword: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, false)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER, value).apply()

    /** 设备标识字符串；决定服务端是否触发 MFA（在安全中心标记可信后可跳过） */
    override var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: "ZZULite-1.0"
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    override fun saveTokens(userToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_USER_TOKEN, userToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    override fun saveCredentials(username: String, password: String?, remember: Boolean) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, if (remember) password else null)
            .putBoolean(KEY_REMEMBER, remember)
            .apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USER_TOKEN = "userToken"
        const val KEY_REFRESH_TOKEN = "refreshToken"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_REMEMBER = "rememberPassword"
        const val KEY_DEVICE_ID = "deviceId"
    }
}
