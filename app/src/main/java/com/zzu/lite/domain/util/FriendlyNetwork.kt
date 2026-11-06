package com.zzu.lite.domain.util

import com.zzu.lite.domain.NetworkException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把底层网络异常翻译成用户能看懂的提示。
 * 教务/信息等教育网服务通常仅限校园网内访问，手机流量会连接超时。
 */
object FriendlyNetwork {

    fun wrap(e: Throwable, what: String): NetworkException {
        val cause = when {
            e is SocketTimeoutException || e.cause is SocketTimeoutException ->
                "${what}超时：该服务（教务/信息门户）通常仅限校园网内访问，" +
                    "请连接校园网 Wi-Fi 后重试；流量下仍可查看已缓存的课表"
            e is ConnectException || (e.message?.contains("failed to connect", ignoreCase = true) == true) ->
                "${what}失败：无法连接到学校服务器，该服务可能仅限校园网访问，请尝试连接校园网 Wi-Fi"
            e is UnknownHostException ->
                "${what}失败：域名解析失败，请检查手机网络后重试"
            else -> "${what}失败：${e.message ?: e.javaClass.simpleName}"
        }
        return NetworkException(cause)
    }
}
