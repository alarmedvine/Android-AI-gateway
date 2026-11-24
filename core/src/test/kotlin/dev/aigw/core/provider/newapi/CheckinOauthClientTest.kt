package dev.aigw.core.provider.newapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 纯 HTTP OAuth 签到的流程回归。
 *
 * 全部喂给 [CheckinOauthClient] 一个假的 `httpGet`，不碰真网络。
 *
 * ## 这些用例必须模拟**真实站点行为**，否则等于给错误的实现盖章
 *
 * 上一版就是这样翻车的：测试伪造 `GET /oauth/github` 返回 JSON，于是「GET SPA 路由」
 * 这个错误实现在 CI 里一路绿，真机上永远拿不到 session。真实行为（实测 agentrouter.org）：
 *
 * ```
 * GET /oauth/github?code=..&state=..                 → 200 text/html 1586B（SPA 空壳）
 * GET /api/oauth/github?code=..&state=..&mode=login  → 200 application/json（真兑换）
 * ```
 *
 * 所以 [fakeSite] 里 `/oauth/github` 一律返回 HTML，只有 `/api/oauth/github` 才会兑换。
 */
class CheckinOauthClientTest {

    private val profile = NewApiSiteProfile(
        key = "test",
        name = "Test Site",
        domain = "https://site.test",
    )

    /** 站点 SPA 路由返回的 HTML 空壳（真实站点 1586 字节，这里只保留判别特征）。 */
    private val spaShell =
        "<!doctype html><html><head><meta charset=\"utf-8\"><script src=\"/assets/index-x.js\"></script></head><body></body></html>"

    private fun client(handler: (String) -> CheckinOauthClient.HttpResult) =
        CheckinOauthClient(profile) { url, _ -> handler(url) }

    private fun ok(body: String, setCookies: List<String> = emptyList()) =
        CheckinOauthClient.HttpResult(200, body, null, setCookies)

    private fun redirect(location: String) =
        CheckinOauthClient.HttpResult(302, "", location, emptyList())

    /**
     * 按真实站点行为应答的假站点。
     *
     * @param exchangeBody `/api/oauth/github` 的响应体
     * @param authorizeLocation GitHub 授权后的 302 目标；默认带 code/state 回跳 SPA 路由
     */
    private fun fakeSite(
        exchangeBody: String = """{"success":true,"data":{"id":42,"quota":25000000,"checked_in":true}}""",
        exchangeCookies: List<String> = listOf("session=new-session; Path=/; HttpOnly"),
        authorizeLocation: String? = "https://site.test/oauth/github?code=CODE1&state=st-1",
        authorizeBody: String = "<html>Sign in to GitHub</html>",
        seen: MutableList<String>? = null,
    ): (String) -> CheckinOauthClient.HttpResult = { url ->
        seen?.add(url)
        when {
            url.contains("/api/status") -> ok("""{"data":{"github_client_id":"cid123"}}""")
            url.contains("/api/oauth/state") -> ok("""{"success":true,"data":"st-1"}""")
            url.startsWith("https://github.com/login/oauth/authorize") ->
                authorizeLocation?.let { redirect(it) } ?: ok(authorizeBody)
            // ★ SPA 路由：真实站点只回 HTML，永远不兑换
            url.startsWith("https://site.test/oauth/github") -> ok(spaShell)
            // ★ 真兑换接口
            url.startsWith("https://site.test/api/oauth/github") -> ok(exchangeBody, exchangeCookies)
            else -> error("unexpected $url")
        }
    }

    // ------------------------------------------------------------------ 端点正确性（核心回归）

    @Test
    fun `兑换请求打到 api-oauth-github 而不是 SPA 路由`() {
        val seen = mutableListOf<String>()
        val c = client(fakeSite(seen = seen))
        c.checkin(mapOf("session" to "old"), "user_session=gh")

        val exchangeCalls = seen.filter { it.contains("/oauth/github") && !it.contains("/api/oauth/") }
        assertEquals(emptyList(), exchangeCalls, "不该去 GET SPA 路由 /oauth/github：$seen")

        val apiCall = seen.singleOrNull { it.startsWith("https://site.test/api/oauth/github") }
        assertTrue(apiCall != null, "必须打真兑换接口：$seen")
        assertTrue(apiCall.contains("code=CODE1"), apiCall)
        assertTrue(apiCall.contains("state=st-1"), apiCall)
        assertTrue(apiCall.contains("mode=login"), apiCall)
    }

    @Test
    fun `三步走通并回收站点新会话`() {
        val seen = mutableListOf<String>()
        val c = client(fakeSite(seen = seen))
        val done = assertIs<CheckinOauthClient.Outcome.Completed>(
            c.checkin(mapOf("session" to "old"), "user_session=gh"),
        )
        assertTrue(done.checkedIn)
        assertEquals("new-session", done.cookies["session"])
        assertTrue(seen.any { it.contains("github.com/login/oauth/authorize") }, seen.toString())
    }

    @Test
    fun `SPA 路由返回 HTML 时绝不当作成功`() {
        // 模拟「站点只有 SPA 路由、API 不存在」的情况：任何 /oauth/github 路径都回 HTML。
        val c = client { url ->
            when {
                url.contains("/api/status") -> ok("""{"data":{"github_client_id":"cid"}}""")
                url.contains("/api/oauth/state") -> ok("""{"data":"st"}""")
                url.startsWith("https://github.com/") ->
                    redirect("https://site.test/oauth/github?code=C&state=st")
                url.contains("/oauth/github") -> ok(spaShell)
                else -> error("不该走到 $url")
            }
        }
        val outcome = c.checkin(mapOf("session" to "s"), "gh=1")
        // 关键：不能是 Completed（那会让上层误以为签到成功）
        assertFalse(outcome is CheckinOauthClient.Outcome.Completed, "HTML 空壳被当成了成功：$outcome")
    }

    // ------------------------------------------------------------------ 失败路径

    @Test
    fun `兑换接口返回 success false 时必须失败`() {
        // 实测真实响应：code 过期时 200 + {"message":"GitHub 授权失败…","success":false}
        val c = client(
            fakeSite(
                exchangeBody = """{"message":"GitHub 授权失败，请重新登录 GitHub 授权（授权码已过期或被重复使用）","success":false}""",
            ),
        )
        val outcome = c.checkin(mapOf("session" to "s"), "gh=1")
        val failed = assertIs<CheckinOauthClient.Outcome.Failed>(outcome)
        assertTrue(failed.reason.contains("GitHub 授权失败"), failed.reason)
    }

    @Test
    fun `GitHub 掉到登录页时要求重新登录`() {
        val c = client(
            fakeSite(authorizeLocation = "https://github.com/login?client_id=cid&return_to=%2Flogin%2Foauth%2Fauthorize"),
        )
        assertEquals(CheckinOauthClient.Outcome.NeedsRelogin, c.checkin(mapOf("session" to "s"), "user_session=gh"))
    }

    @Test
    fun `GitHub 要求重新确认授权时不谎报成功`() {
        // 授权确认页：HTML 里有 Authorize 按钮，但 URL 不是登录页 —— 纯 HTTP 无法静默点它。
        val c = client(
            fakeSite(
                authorizeLocation = null,
                authorizeBody = "<html><title>Authorize app</title><form action='/login/oauth/authorize'>" +
                    "<button name='authorize'>Authorize</button></form></html>",
            ),
        )
        assertEquals(CheckinOauthClient.Outcome.NeedsAuthorize, c.checkin(mapOf("session" to "s"), "user_session=gh"))
    }

    @Test
    fun `站点没下发 state 时明确失败`() {
        val c = client { url ->
            when {
                url.contains("/api/oauth/state") -> ok("""{"success":false,"message":"x"}""")
                else -> ok("""{"data":{"github_client_id":"cid"}}""")
            }
        }
        assertIs<CheckinOauthClient.Outcome.Failed>(c.checkin(mapOf("session" to "s"), "user_session=gh"))
    }

    @Test
    fun `站点没有 GitHub client_id 时明确失败而不是拼个空授权`() {
        val c = client { url ->
            when {
                url.contains("/api/status") -> ok("""{"data":{"github_oauth":false}}""")
                url.contains("/api/oauth/state") -> ok("""{"data":"st"}""")
                else -> error("不该走到 $url")
            }
        }
        assertIs<CheckinOauthClient.Outcome.Failed>(c.checkin(mapOf("session" to "s"), "user_session=gh"))
    }

    @Test
    fun `checked_in 为 false 时如实回报而不是假成功`() {
        val c = client(
            fakeSite(
                exchangeBody = """{"success":true,"data":{"id":42,"checked_in":false}}""",
                exchangeCookies = emptyList(),
            ),
        )
        val done = assertIs<CheckinOauthClient.Outcome.Completed>(c.checkin(mapOf("session" to "s"), "gh=1"))
        assertFalse(done.checkedIn)
        assertTrue(done.cookies.isEmpty())
    }

    @Test
    fun `回跳地址里的 code 与 state 会被 URL 编码后再拼进兑换请求`() {
        val seen = mutableListOf<String>()
        val c = client(
            fakeSite(
                authorizeLocation = "https://site.test/oauth/github?code=ab%2Bcd%2Fef&state=st%3Dx",
                seen = seen,
            ),
        )
        c.checkin(mapOf("session" to "s"), "gh=1")
        val apiCall = seen.single { it.startsWith("https://site.test/api/oauth/github") }
        // 已解码的值必须重新编码，不能把 %2B 变成 +（会被服务端解释成空格）
        assertFalse(apiCall.contains("code=ab+cd"), apiCall)
        assertTrue(apiCall.contains("code=ab%2Bcd%2Fef"), apiCall)
    }
}
