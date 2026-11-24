package dev.aigw.core.provider.newapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 纯 HTTP OAuth 签到的流程回归。
 *
 * 全部喂给 [CheckinOauthClient] 一个假的 `httpGet`，不碰真网络：
 * 钉住的是「三步顺序 + 302 取 code + 站点回调发额度 + Set-Cookie 回收」，
 * 也就是**批量签到能自动跑**所依赖的那条链。
 */
class CheckinOauthClientTest {

    private val profile = NewApiSiteProfile(
        key = "test",
        name = "Test Site",
        domain = "https://site.test",
    )

    private fun client(handler: (String) -> CheckinOauthClient.HttpResult) =
        CheckinOauthClient(profile) { url, _ -> handler(url) }

    private fun ok(body: String, setCookies: List<String> = emptyList()) =
        CheckinOauthClient.HttpResult(200, body, null, setCookies)

    private fun redirect(location: String) =
        CheckinOauthClient.HttpResult(302, "", location, emptyList())

    @Test
    fun `三步走通并回收站点新会话`() {
        val seen = mutableListOf<String>()
        val c = client { url ->
            seen += url
            when {
                url.startsWith("https://site.test/oauth/github") -> ok(
                    """{"success":true,"data":{"id":42,"quota":25000000,"checked_in":true}}""",
                    setCookies = listOf("session=new-session; Path=/; HttpOnly"),
                )
                url.contains("/api/status") -> ok("""{"data":{"github_client_id":"cid123"}}""")
                url.contains("/api/oauth/state") -> ok("""{"success":true,"data":"st-1"}""")
                url.startsWith("https://github.com/login/oauth/authorize") ->
                    redirect("https://site.test/oauth/github?code=CODE1&state=st-1")
                else -> error("unexpected $url")
            }
        }
        val outcome = c.checkin(mapOf("session" to "old"), "user_session=gh")
        val done = assertIs<CheckinOauthClient.Outcome.Completed>(outcome)
        assertTrue(done.checkedIn)
        assertEquals("new-session", done.cookies["session"])
        // 顺序必须是 status/state -> github -> 站点回调
        assertTrue(seen[0].contains("/api/oauth/state") || seen[0].contains("/api/status"), seen.toString())
        assertTrue(seen.any { it.contains("github.com/login/oauth/authorize") }, seen.toString())
        assertTrue(seen.last().startsWith("https://site.test/oauth/github"), seen.toString())
    }

    @Test
    fun `GitHub 掉到登录页时要求重新登录`() {
        val c = client { url ->
            when {
                url.contains("/api/status") -> ok("""{"data":{"github_client_id":"cid"}}""")
                url.contains("/api/oauth/state") -> ok("""{"data":"st"}""")
                url.startsWith("https://github.com/") -> redirect("https://github.com/login?return_to=x")
                else -> error("unexpected $url")
            }
        }
        assertEquals(CheckinOauthClient.Outcome.NeedsRelogin, c.checkin(mapOf("session" to "s"), "user_session=gh"))
    }

    @Test
    fun `GitHub 要求重新确认授权时不谎报成功`() {
        val c = client { url ->
            when {
                url.contains("/api/status") -> ok("""{"data":{"github_client_id":"cid"}}""")
                url.contains("/api/oauth/state") -> ok("""{"data":"st"}""")
                url.startsWith("https://github.com/") ->
                    ok("<html><title>Authorize app</title><form action='/login/oauth/authorize'></form></html>")
                else -> error("unexpected $url")
            }
        }
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
        val outcome = c.checkin(mapOf("session" to "s"), "user_session=gh")
        assertIs<CheckinOauthClient.Outcome.Failed>(outcome)
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
        val outcome = c.checkin(mapOf("session" to "s"), "user_session=gh")
        assertIs<CheckinOauthClient.Outcome.Failed>(outcome)
    }

    @Test
    fun `checked_in 为 false 时也如实回报已发放而不是假成功`() {
        val c = client { url ->
            when {
                url.startsWith("https://site.test/oauth/github") ->
                    ok("""{"success":true,"data":{"id":42,"checked_in":false}}""")
                url.contains("/api/status") -> ok("""{"data":{"github_client_id":"cid"}}""")
                url.contains("/api/oauth/state") -> ok("""{"data":"st"}""")
                url.startsWith("https://github.com/") ->
                    redirect("https://site.test/oauth/github?code=C&state=st")
                else -> error("unexpected $url")
            }
        }
        val done = assertIs<CheckinOauthClient.Outcome.Completed>(c.checkin(mapOf("session" to "s"), "gh=1"))
        assertFalse(done.checkedIn)
        assertTrue(done.cookies.isEmpty())
    }
}
