package dev.aigw.core.provider.newapi

import com.google.gson.JsonParser
import java.net.URI

/** 登录完成边界：域名比较不能靠 contains，也不能仅靠 OAuth state 的 session cookie。 */
object CheckinLoginPolicy {
    fun isSitePage(url: String?, domain: String): Boolean = runCatching {
        val page = URI(url ?: return false)
        val site = URI(domain)
        page.scheme.equals(site.scheme, ignoreCase = true) &&
            page.host != null && page.host.equals(site.host, ignoreCase = true) &&
            page.port == site.port && page.rawUserInfo == null
    }.getOrDefault(false)

    fun canReadIdentity(url: String?, domain: String): Boolean {
        if (!isSitePage(url, domain)) return false
        val path = runCatching { URI(url).path.orEmpty().trimEnd('/') }.getOrDefault("")
        return path.isEmpty() || path == "/console" || path.startsWith("/console/")
    }

    /** evaluateJavascript 返回的是 JSON 编码的 uid 字符串，不从任何网页抓取 token。 */
    fun pageUserId(result: String?): String? = runCatching {
        val value = JsonParser.parseString(result ?: return null)
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        val text = value.asString
        if (text.isEmpty() || !text.all { it in '0'..'9' }) return null
        text.toLongOrNull()?.takeIf { it > 0 }?.toString()
    }.getOrNull()
}
