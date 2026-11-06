package com.zzu.lite.data.remote.dto

import kotlinx.serialization.Serializable

/** CAS / portal-api 系列通用响应壳：{code, message, data} */
@Serializable
data class ApiEnvelope<T>(
    val code: Int = -1,
    val message: String? = null,
    val data: T? = null
)

@Serializable
data class LoginData(
    val idToken: String = "",
    val refreshToken: String = ""
)

@Serializable
data class MfaDetectData(
    val state: String = "",
    val need: Boolean = false,
    val mfaTypeSecurePhone: Boolean = false
)

@Serializable
data class MfaInitData(
    val gid: String = "",
    val attestServerUrl: String = ""
)

@Serializable
data class MfaSendData(
    val result: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class MfaValidData(
    val status: Int = 0,
    val result: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class MfaGidBody(val gid: String)

@Serializable
data class MfaValidBody(val gid: String, val code: String)
