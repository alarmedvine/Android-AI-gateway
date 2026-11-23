package dev.aigw.core.provider.newapi

/**
 * New API 站点 cookie 的解析与序列化。
 *
 * 签到只需要一枚 `session`（会话凭证），但站点前面通常还有一层 WAF（阿里云 ESA / WAF），
 * 会额外下发 `acw_tc` / `cdn_sec_tc` / `acw_sc__v2` 等 cookie。这里统一按「名字 → 值」
 * 处理，不假设任何一枚的存在：
 * - 干净网络下 WAF 可能只下发 `acw_tc`，甚至完全不挑战（实测 agentrouter.org）；
 * - 被风控时才会要求 `acw_sc__v2`（实测 anyrouter.top）。
 *
 * 解析刻意宽松：用户在浏览器里复制出来的字符串可能带 `Cookie: ` 前缀、换行、
 * 甚至尾部多余分号，全部吞掉；只有「没有等号」的碎片才丢弃。
 */
object CheckinCookie {

    /** 解析 cookie 串；无法解析时返回空 Map。 */
    fun parse(raw: String): Map<String, String> {
        val text = raw.trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .replace('\n', ';')
            .replace('\r', ';')
        val result = LinkedHashMap<String, String>()
        for (part in text.split(';')) {
            val item = part.trim()
            if (item.isEmpty()) continue
            val eq = item.indexOf('=')
            if (eq <= 0) continue
            val name = item.substring(0, eq).trim()
            val value = item.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            result[name] = value
        }
        return result
    }

    /** 序列化成 `k=v; k=v`；空 Map 返回空串（调用方据此跳过 Cookie 头）。 */
    fun format(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }

    /** 合并多份 cookie，后者覆盖前者同名项。 */
    fun merge(vararg sources: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        for (source in sources) merged.putAll(source)
        return merged
    }

    /** 是否是「未登录」类响应：New API 统一回 `success=false` + 这句话。 */
    fun isUnauthorized(body: String): Boolean =
        body.contains("未登录") || body.contains("access token") || body.contains("未提供")
}
