package com.zzu.lite.data.remote

import com.zzu.lite.domain.NetworkException
import com.zzu.lite.domain.PortalNotDetectedException
import com.zzu.lite.domain.util.Jsonp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Portal 网关信息（自动探测结果） */
data class PortalInfo(
    val authUrl: String,
    val portalServerUrl: String,
    val userIp: String
)

@Serializable
data class PortalAuthResult(
    val result: Int = 0,
    val msg: String? = null,
    val retCode: Int? = null
) {
    val success: Boolean get() = result == 1
}

/**
 * 校园网 Portal（Dr.COM eportal）认证，逻辑对齐 ZZU.Py web/network.py。
 * 流程：GET bilibili 探测劫持 -> 提取 portal url 与 userip -> /a41.js 解析端口 -> /eportal/portal/login。
 */
class PortalClient(private val baseClient: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun discover(): PortalInfo = withContext(Dispatchers.IO) {
        val resp = try {
            client.newCall(
                okhttp3.Request.Builder()
                    .url("http://bilibili.com")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    .build()
            ).execute()
        } catch (e: Exception) {
            throw NetworkException("探测校园网网关失败：${e.message ?: e.javaClass.simpleName}")
        }
        resp.use { r ->
            val finalUrl = r.request.url.toString()
            if (finalUrl.startsWith("https://")) {
                // 未被网关劫持：已认证或不在校园网
                throw PortalNotDetectedException()
            }
            val portalUrl = if (finalUrl != "http://bilibili.com/" && finalUrl != "http://bilibili.com") {
                finalUrl
            } else {
                val html = r.body?.string().orEmpty()
                Regex("""<a\s+href="([^"]+)"""").find(html)?.groupValues?.get(1)
                    ?: throw PortalNotDetectedException("校园网网关响应异常，无法定位认证页")
            }

            val parsed = runCatching { portalUrl.toHttpUrl() }.getOrElse {
                throw PortalNotDetectedException("认证页地址无法解析：$portalUrl")
            }
            val queryNames = parsed.queryParameterNames
            val userIp = parsed.queryParameter("userip")
                ?: parsed.queryParameter("wlanuserip")
                ?: ""
            val defaultPort = if (parsed.scheme == "https") 443 else 80
            val portPart = if (parsed.port > 0 && parsed.port != defaultPort) ":${parsed.port}" else ""
            val authUrl = "${parsed.scheme}://${parsed.host}$portPart"

            // /a41.js 解析 portal 服务端口
            val serverUrl = runCatching {
                val js = client.newCall(
                    okhttp3.Request.Builder().url("$authUrl/a41.js").build()
                ).execute().use { it.body?.string().orEmpty() }
                val vars = Regex("""var\s+(\w+)\s*=\s*(\d+);""").findAll(js)
                    .associate { it.groupValues[1] to it.groupValues[2].toInt() }
                if (vars["enableHttps"] == 0) {
                    "http://${parsed.host}:${vars["epHTTPPort"] ?: 801}"
                } else {
                    "https://${parsed.host}:${vars["enHTTPSPort"] ?: vars["enHttpsPort"] ?: 802}"
                }
            }.getOrDefault("http://${parsed.host}:801")

            PortalInfo(authUrl, serverUrl, userIp)
        }
    }

    /** 一键认证。ISP 后缀如 "@cmcc"/"@unicom"/"@telecom"，无则传 null。 */
    suspend fun auth(
        info: PortalInfo,
        account: String,
        password: String,
        ispSuffix: String? = null
    ): PortalAuthResult = withContext(Dispatchers.IO) {
        val userAccount = ",0,${account}${ispSuffix.orEmpty()}"
        val pwdB64 = java.util.Base64.getEncoder().encodeToString(password.toByteArray(Charsets.UTF_8))
        val v = (500..10499).random().toString()

        val url = "${info.portalServerUrl.trimEnd('/')}/eportal/portal/login".toHttpUrl().newBuilder()
            .addQueryParameter("callback", "dr1003")
            .addQueryParameter("login_method", "1")
            .addQueryParameter("user_account", userAccount)
            .addQueryParameter("user_password", pwdB64)
            .addQueryParameter("wlan_user_ip", info.userIp)
            .addQueryParameter("wlan_user_ipv6", "")
            .addQueryParameter("wlan_user_mac", "000000000000")
            .addQueryParameter("wlan_vlan_id", "0")
            .addQueryParameter("wlan_ac_ip", "")
            .addQueryParameter("wlan_ac_name", "")
            .addQueryParameter("authex_enable", "")
            .addQueryParameter("jsVersion", "4.2.2")
            .addQueryParameter("terminal_type", "3")
            .addQueryParameter("lang", "zh-cn")
            .addQueryParameter("v", v)
            .addQueryParameter("lang", "zh")
            .build()

        val body = try {
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute()
                .use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw NetworkException("认证请求失败：${e.message ?: e.javaClass.simpleName}")
        }
        val jsonText = Jsonp.extract(body) ?: throw NetworkException("认证响应格式异常：${body.take(120)}")
        json.decodeFromString(PortalAuthResult.serializer(), jsonText)
    }
}
