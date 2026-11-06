package com.zzu.lite.domain.util

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * CAS 登录加密：ZZU.Py 的 crypto.py 逻辑 ——
 * RSA PKCS#1 v1.5 加密 -> 标准 Base64 -> 前缀 "__RSA__"。
 */
object Crypto {

    private const val PREFIX = "__RSA__"

    /** 从 PEM 文本（X.509 SubjectPublicKeyInfo）解析 RSA 公钥。 */
    fun parsePemPublicKey(pem: String): PublicKey {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val der = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    /** 加密明文并按接口约定编码：`__RSA__<base64>`。 */
    fun encryptAndEncode(publicKey: PublicKey, plain: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(encrypted)
    }
}
