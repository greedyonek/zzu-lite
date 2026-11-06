package com.zzu.lite.domain.util

/**
 * JSONP 解析：与 ZZU.Py 的 utils.JsonPParser 一致，
 * 从 `callback({...});` 中提取括号内 JSON 文本。
 */
object Jsonp {

    private val regex = Regex("""^\s*(\w+)\((.*)\);?\s*$""", RegexOption.DOT_MATCHES_ALL)

    fun extract(body: String): String? {
        val m = regex.find(body) ?: return null
        return m.groupValues[2]
    }
}
