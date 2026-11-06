package com.zzu.lite.data.local

/**
 * 凭据存储抽象：AuthRepository 依赖此接口，JVM 单测可提供内存实现。
 */
interface CredentialsStore {
    var userToken: String?
    var refreshToken: String?
    var username: String?
    var password: String?
    var rememberPassword: Boolean
    var deviceId: String

    fun saveTokens(userToken: String, refreshToken: String)
    fun saveCredentials(username: String, password: String?, remember: Boolean)
    fun clearAll()
}

/** 电费房间绑定存储抽象 */
interface RoomStore {
    suspend fun savedRoom(): String?
    suspend fun saveRoom(room: String?)
}
