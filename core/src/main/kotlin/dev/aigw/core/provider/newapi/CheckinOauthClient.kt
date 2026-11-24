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
 * ## ★ 第 3 步必须是 `/api/oauth/github`，不是 `/oauth/github`
 *
 * 站点把 `/oauth/github` 注册成 **SPA 前端路由**（GitHub 的 redirect_uri 指向它），
 * 浏览器打开它只会拿到 1586 字节的 HTML 空壳，由页面里的 JS 去调真正的兑换接口。
 * 实测（2026-11-25，agentrouter.org）：
 *
 * ```
 * GET /oauth/github?code=..&state=..            → 200 text/html  1586B（空壳，不兑换）
 * GET /api/oauth/github?code=..&state=..&mode=login → 200 application/json（真兑换）
 * ```
 *
 * 站点前端原文：``await Ee.get(`/api/oauth/${t.type}?code=${l}&state=${u}&mode=${c}`)``。
 * 上一版把 302 的 Location 原样 GET，拿到 HTML，解析不出 session 却回执 Completed，
 * 于是「CI 绿、真机一分额度没加」——本类的测试当时也伪造了 `GET /oauth/github` 返回 JSON，
 * 与真实行为不符，等于给错误的实现盖了章。
 *
 * 判据仍以站点日志 / 余额差为准（见 [CheckinLogClient]）：`checked_in` 只说"今天签过"，
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

        // 带 GitHub 会话访问授权页。GitHub 对已授权的应用直接 302 回站点的 SPA 路由。
        val authorize = httpGet(authorizeUrl, githubHeaders(githubCookies))
        val callbackParams = resolveCallbackParams(authorize, base)
            ?: return when (authorize.status) {
                200 -> if (looksLikeGithubLogin(authorize.body)) Outcome.NeedsRelogin else Outcome.NeedsAuthorize
                301, 302, 303, 307, 308 -> {
                    val target = authorize.location.orEmpty()
                    if (looksLikeGithubLogin(target)) Outcome.NeedsRelogin else Outcome.NeedsAuthorize
                }
                else -> Outcome.Failed("GitHub 授权返回 HTTP ${authorize.status}")
            }

        // ★ 真正的兑换接口是 `/api/oauth/github`，不是 SPA 路由 `/oauth/github`（见类注释）。
        // 这里刻意用 GitHub 回跳里带的 code+state 自己拼 API URL，而不是去 GET 那个 HTML 空壳。
        val exchangeUrl = "$base/api/oauth/github?code=${enc(callbackParams.first)}" +
            "&state=${enc(callbackParams.second)}&mode=login"
        val callback = httpGet(exchangeUrl, siteHeaders(siteCookies))
        if (callback.status !in 200..299) {
            return Outcome.Failed("站点兑换接口返回 HTTP ${callback.status}")
        }
        // 兑换失败时站点返回 200 + success:false（实测：code 过期时
        // `{"message":"GitHub 授权失败…","success":false}`）。不能当成功。
        if (!CheckinOauthPolicy.responseSuccess(callback.body)) {
            val reason = CheckinOauthPolicy.responseMessage(callback.body)
            return Outcome.Failed(reason.ifEmpty { "站点拒绝兑换授权码" })
        }
        return Outcome.Completed(
            cookies = parseSetCookies(callback.setCookies),
            checkedIn = CheckinOauthPolicy.claimedCheckin(callback.body),
            body = callback.body,
        )
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
     * 从授权响应里取出 `(code, state)`。
     *
     * GitHub 的 302 Location 形如 `{domain}/oauth/github?code=..&state=..`——
     * 那是**站点注册给 GitHub 的回跳地址**，是 SPA 路由。我们只从它身上取两个参数，
     * 不去 GET 它（GET 只会拿到 HTML 空壳，见类注释）。
     *
     * 可能的情况：①直接 302 带 code；②先 302 到 github.com 的中间页再跳；
     * ③返回登录页/确认页（HTML，说明会话失效或需要再授权）。
     */
    private fun resolveCallbackParams(res: HttpResult, base: String): Pair<String, String>? {
        val loc = res.location ?: return null
        extractCodeState(loc, base)?.let { return it }
        // GitHub 掉到登录页 = 会话失效，**不要再跟一跳**：
        // 跟着去 GET 登录页既拿不到 code，还会把「需要重新登录」误判成「需要确认授权」。
        if (looksLikeGithubLogin(loc)) return null
        // 中间跳转：GitHub 有时先到 /login/oauth/authorize?...，再跳到站点。
        if (loc.contains("github.com")) {
            val absolute = if (loc.startsWith("http")) loc else "https://github.com$loc"
            val second = httpGet(absolute, githubHeaders(""))
            extractCodeState(second.location, base)?.let { return it }
        }
        return null
    }

    /** 从 `{domain}/oauth/github?code=..&state=..` 里取两个参数；不是该路径就返回 null。 */
    private fun extractCodeState(location: String?, base: String): Pair<String, String>? {
        val raw = location ?: return null
        val absolute = if (raw.startsWith("http")) raw else base + raw
        if (!CheckinOauthPolicy.isCallbackUrl(absolute, profile)) return null
        val query = absolute.substringAfter('?', "")
        if (query.isEmpty()) return null
        val params = query.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else decode(part.substring(0, i)) to decode(part.substring(i + 1))
        }.toMap()
        val code = params["code"]?.takeIf { it.isNotEmpty() } ?: return null
        val state = params["state"]?.takeIf { it.isNotEmpty() } ?: return null
        return code to state
    }

    /**
     * 先解码再编码：Location 里的值已经是 percent-encoded 的，
     * 直接丢给 [enc] 会二次编码（`%2B` → `%252B`），服务端解出来就是错的。
     */
    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

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
