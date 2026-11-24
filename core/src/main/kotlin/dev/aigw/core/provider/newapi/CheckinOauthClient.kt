package dev.aigw.core.provider.newapi

import dev.aigw.core.util.HttpSupport

/**
 * 纯 HTTP 的 OAuth 签到：不依赖 WebView，批量签到也能自动跑。
 *
 * ## 为什么必须走 HTTP 而不是 WebView
 *
 * 站点的签到就是"一次新的 GitHub 登录"。WebView 那条路需要 Activity，
 * 而后台批量签到（AutoCheckin）没有界面，只能纯 HTTP。
 *
 * ## 流程
 *
 * 1. `GET {domain}/api/oauth/state?mode=login` → 站点签名的一次性 state
 * 2. `GET github.com/login/oauth/authorize?client_id=..&state=..&scope=user:email`
 *    带上该账号保存的 GitHub 会话 → GitHub 直接 302 回站点回调并带 `code`
 *    （已授权过的应用不会弹确认页；若弹了说明会话失效，按 [NeedsRelogin] 处理）
 * 3. `GET {domain}/api/oauth/github?code=..&state=..&mode=login`
 *    → 站点兑换 code、发放当日额度、下发新 session cookie
 *
 * 判据仍以站点日志为准（见 [CheckinLogClient]）：`checked_in` 只说"今天签过"，
 * 不说"是不是这一次签的"。
 */
class CheckinOauthClient(
    private val profile: NewApiSiteProfile,
    /** 抓取 GitHub 授权页的最终落点（测试可注入）。 */
    private val httpGet: (url: String, headers: Map<String, String>) -> HttpResult = { url, headers ->
        rawGet(url, headers)
    },
) {

    /**
     * 一次 OAuth 签到的结果。
     *
     * [cookies] 是站点新下发的会话串（可能为空 = 站点没轮换），由调用方写回账号。
     */
    sealed interface Outcome {
        /** 走完了整个流程，站点下发了新会话。 */
        data class Completed(val cookies: Map<String, String>, val checkedIn: Boolean, val body: String) : Outcome

        /** GitHub 会话已失效，必须在应用内重新登录一次（WebView）。 */
        data object NeedsRelogin : Outcome

        /** GitHub 要求重新确认授权（页面式，纯 HTTP 无法静默通过）。 */
        data object NeedsAuthorize : Outcome

        /** 上游返回了非预期内容（WAF 页面 / 错误码）。 */
        data class Failed(val reason: String) : Outcome
    }

    /** 简单的结果载体，便于测试注入。 */
    data class HttpResult(
        val status: Int,
        val body: String,
        val location: String?,
        val setCookies: List<String>,
    )

    /**
     * 执行一次签到。
     *
     * @param siteCookies 账号现有的站点会话（`name` → `value`）
     * @param githubCookies GitHub 会话串（`k=v; k=v`），为空则必然 [Outcome.NeedsRelogin]
     */
    fun checkin(siteCookies: Map<String, String>, githubCookies: String): Outcome {
        val base = profile.domain.trimEnd('/')

        val state = fetchState(base) ?: return Outcome.Failed("站点没有下发 OAuth state")
        val authorizeUrl = buildAuthorizeUrl(state) ?: return Outcome.Failed("站点未提供 GitHub client_id")

        // 带 GitHub 会话访问授权页。GitHub 对已授权的应用直接 302 回回调。
        val authorize = httpGet(authorizeUrl, githubHeaders(githubCookies))
        val callbackUrl = resolveCallbackUrl(authorize, base)
            ?: return when (authorize.status) {
                200 -> if (looksLikeGithubLogin(authorize.body)) Outcome.NeedsRelogin else Outcome.NeedsAuthorize
                302, 301, 303 -> if (looksLikeGithubLogin(authorize.location.orEmpty())) {
                    Outcome.NeedsRelogin
                } else {
                    Outcome.NeedsAuthorize
                }
                else -> Outcome.Failed("GitHub 授权返回 HTTP ${authorize.status}")
            }

        // 站点回调：兑换 code 并发放额度。
        val callback = httpGet(callbackUrl, siteHeaders(siteCookies))
        val setCookies = parseSetCookies(callback.setCookies)
        return when {
            callback.status !in 200..299 -> Outcome.Failed("站点回调返回 HTTP ${callback.status}")
            else -> Outcome.Completed(
                cookies = setCookies,
                checkedIn = CheckinOauthPolicy.claimedCheckin(callback.body),
                body = callback.body,
            )
        }
    }

    private fun fetchState(base: String): String? {
        val res = httpGet("$base/api/oauth/state?mode=login", mapOf("Accept" to "application/json"))
        if (res.status !in 200..299) return null
        return CheckinOauthPolicy.parseState(res.body)
    }

    private fun buildAuthorizeUrl(state: String): String? {
        val status = httpGet("${profile.domain.trimEnd('/')}/api/status", mapOf("Accept" to "application/json"))
        if (status.status !in 200..299) return null
        val clientId = CheckinOauthPolicy.parseClientId(status.body) ?: return null
        return "https://github.com/login/oauth/authorize" +
            "?client_id=" + enc(clientId) +
            "&state=" + enc(state) +
            "&scope=user%3Aemail"
    }

    /**
     * 从授权响应里取出站点回调 URL。
     *
     * GitHub 可能：①直接 302 到 `{domain}/oauth/github?code=..`；
     * ②先 302 到中间页再跳；③返回登录页/确认页（HTML）。
     */
    private fun resolveCallbackUrl(res: HttpResult, base: String): String? {
        res.location?.let { loc ->
            val absolute = if (loc.startsWith("http")) loc else base + loc
            if (CheckinOauthPolicy.isCallbackUrl(absolute, profile)) return absolute
            // 中间跳转：继续跟一跳（GitHub 有时先到 /login/oauth/authorize?...）
            if (absolute.contains("github.com")) {
                val second = httpGet(absolute, githubHeaders(""))
                second.location?.let { loc2 ->
                    val abs2 = if (loc2.startsWith("http")) loc2 else base + loc2
                    if (CheckinOauthPolicy.isCallbackUrl(abs2, profile)) return abs2
                }
            }
        }
        return null
    }

    private fun looksLikeGithubLogin(text: String): Boolean =
        text.contains("/login?") || text.contains("Sign in to GitHub") || text.contains("sign in")

    private fun githubHeaders(githubCookies: String): Map<String, String> =
        buildMap {
            put("Accept", "text/html,application/xhtml+xml")
            if (githubCookies.isNotEmpty()) put("Cookie", githubCookies)
        }

    private fun siteHeaders(siteCookies: Map<String, String>): Map<String, String> = buildMap {
        put("Accept", "application/json")
        if (siteCookies.isNotEmpty()) put("Cookie", CheckinCookie.format(siteCookies))
    }

    /** 解析 `Set-Cookie` 头，只取 `name=value` 部分。 */
    private fun parseSetCookies(lines: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in lines) {
            val first = line.substringBefore(';').trim()
            val eq = first.indexOf('=')
            if (eq <= 0) continue
            val name = first.substring(0, eq).trim()
            val value = first.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            out[name] = value
        }
        return out
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        /** 真实实现：一次 GET，不自动跟随重定向，把 Location 交给上层判断。 */
        fun rawGet(url: String, headers: Map<String, String>): HttpResult {
            val conn = HttpSupport.open(
                url = url,
                method = "GET",
                headers = headers,
                connectTimeoutMs = 15_000,
                readTimeoutMs = 25_000,
                allowCompression = true,
            )
            conn.instanceFollowRedirects = false
            return try {
                val status = conn.responseCode
                val setCookies = conn.headerFields
                    .filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                    .values.flatten()
                    .orEmpty()
                HttpResult(
                    status = status,
                    body = HttpSupport.readBody(conn),
                    location = conn.getHeaderField("Location"),
                    setCookies = setCookies,
                )
            } finally {
                conn.disconnect()
            }
        }
    }
}
