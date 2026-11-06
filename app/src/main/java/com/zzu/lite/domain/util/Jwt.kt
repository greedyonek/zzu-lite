package com.zzu.lite.domain.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * JWT 工具：与 ZZU.Py 一致，只解码 payload 读取 exp，不做签名校验。
 * 使用 java.util.Base64（minSdk 26 原生可用），保证 JVM 单测同样可运行。
 */
object Jwt {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析 JWT 的过期时间（Unix 秒）。解析失败返回 null。 */
    fun expirationEpochSec(token: String?): Long? {
        if (token.isNullOrBlank()) return null
        if (token.length > 16384) return null
        val payload = runCatching { decodePayload(token) }.getOrNull() ?: return null
        return runCatching {
            val exp = json.parseToJsonElement(payload).jsonObject["exp"] ?: return null
            when {
                exp.toString().contains(".") -> exp.toString().toDoubleOrNull()?.toLong()
                else -> exp.toString().toLongOrNull()
            }
        }.getOrNull()
    }

    /** token 距过期是否还大于 [minSeconds] 秒（无 token / 无法解析一律视为无效）。 */
    fun isValidFor(
        token: String?,
        minSeconds: Long = 900L,
        nowEpochSec: Long = System.currentTimeMillis() / 1000
    ): Boolean {
        val exp = expirationEpochSec(token) ?: return false
        return exp - nowEpochSec > minSeconds
    }

    private fun decodePayload(token: String): String {
        val parts = token.split(".")
        require(parts.size == 3) { "jwt 段数非法" }
        val normalized = parts[1].replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }
}
