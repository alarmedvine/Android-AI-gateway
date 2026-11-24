package dev.aigw.core.provider.newapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「签到 = 重新 OAuth 登录」这套判据的回归。
 *
 * 全部是纯逻辑，不碰网络与 Android：它钉住的正是上一版失败的根因——
 * 把「查余额成功」当成「签到成功」，以及把 `checked_in` 当成本次成功的证据。
 */
class CheckinOauthPolicyTest {

    private val agentrouter = NewApiSiteProfile.builtins.first { it.key == "agentrouter" }

    // ---------------------------------------------------------------- 签到方式

    @Test
    fun `AgentRouter 必须声明为 OAuth 重新登录而不是查余额`() {
        assertEquals(CheckinMode.OAUTH_RELOGIN, agentrouter.checkinMode)
        // 它确实没有签到接口路径
        assertTrue(agentrouter.signInPath.isEmpty())
    }

    @Test
    fun `普通站点默认按有签到接口处理`() {
        val other = NewApiSiteProfile(key = "anyrouter", name = "AnyRouter", domain = "https://anyrouter.top")
        assertEquals(CheckinMode.API, other.checkinMode)
        assertEquals("/api/user/sign_in", other.signInPath)
    }

    // ---------------------------------------------------------------- 回调判定

    @Test
    fun `回调页判定要求完整同源且路径精确`() {
        assertTrue(
            CheckinOauthPolicy.isCallbackUrl(
                "https://agentrouter.org/oauth/github?code=abc&state=xyz", agentrouter,
            ),
        )
        assertTrue(CheckinOauthPolicy.isCallbackUrl("https://agentrouter.org/oauth/github", agentrouter))
        // 不同源
        assertFalse(CheckinOauthPolicy.isCallbackUrl("https://evil.org/oauth/github", agentrouter))
        // 同源但路径不对
        assertFalse(CheckinOauthPolicy.isCallbackUrl("https://agentrouter.org/oauth/linuxdo", agentrouter))
        // 子路径不算（前端路由只认精确路径）
        assertFalse(
            CheckinOauthPolicy.isCallbackUrl("https://agentrouter.org/oauth/github/extra", agentrouter),
        )
        assertFalse(CheckinOauthPolicy.isCallbackUrl(null, agentrouter))
    }

    @Test
    fun `登录页 URL 走站点自己的入口`() {
        assertEquals("https://agentrouter.org/login", CheckinOauthPolicy.loginUrl(agentrouter))
    }

    // ---------------------------------------------------------------- checked_in

    @Test
    fun `登录响应里的 checked_in 能被识别`() {
        val body = """{"success":true,"data":{"id":42,"quota":100,"checked_in":true}}"""
        assertTrue(CheckinOauthPolicy.claimedCheckin(body))
        assertTrue(CheckinOauthPolicy.responseSuccess(body))
        assertEquals("42", CheckinOauthPolicy.responseUserId(body))
    }

    @Test
    fun `checked_in 只接受真实布尔值`() {
        // 字符串 "true" 不算：严格判定，避免上游字段类型漂移导致假成功
        assertFalse(CheckinOauthPolicy.claimedCheckin("""{"data":{"checked_in":"true"}}"""))
        assertFalse(CheckinOauthPolicy.claimedCheckin("""{"data":{"checked_in":1}}"""))
        assertFalse(CheckinOauthPolicy.claimedCheckin("""{"data":{}}"""))
        assertFalse(CheckinOauthPolicy.claimedCheckin(""))
        assertFalse(CheckinOauthPolicy.claimedCheckin("<html>今日已签到</html>"))
    }

    @Test
    fun `响应 uid 必须是正整数`() {
        assertEquals("42", CheckinOauthPolicy.responseUserId("""{"data":{"id":42}}"""))
        assertEquals("42", CheckinOauthPolicy.responseUserId("""{"data":{"id":"42"}}"""))
        assertNull(CheckinOauthPolicy.responseUserId("""{"data":{"id":0}}"""))
        assertNull(CheckinOauthPolicy.responseUserId("""{"data":{"id":-1}}"""))
        assertNull(CheckinOauthPolicy.responseUserId("""{"data":{"id":"abc"}}"""))
        assertNull(CheckinOauthPolicy.responseUserId("""{"data":{}}"""))
    }

    @Test
    fun `上游错误文案能原样取出`() {
        assertEquals(
            "GitHub 授权失败，请重新登录 GitHub 授权（授权码已过期或被重复使用）",
            CheckinOauthPolicy.responseMessage(
                """{"message":"GitHub 授权失败，请重新登录 GitHub 授权（授权码已过期或被重复使用）","success":false}""",
            ),
        )
    }

    // ---------------------------------------------------------------- 授权参数

    @Test
    fun `授权参数从公开接口现取，不依赖 cookie`() {
        val responses = mapOf(
            "https://agentrouter.org/api/status" to
                """{"success":true,"data":{"github_oauth":true,"github_client_id":"Ov23lidtiR4LeVZvVRNL","system_name":"Agent Router"}}""",
            "https://agentrouter.org/api/oauth/state?mode=login" to
                """{"success":true,"data":"eyJuIjoid0I2S2h1cWhKa2NlIiwibSI6ImxvZ2luIn0.sig","message":""}""",
        )
        val url = CheckinOauthPolicy.fetchAuthorizeParams(agentrouter) { responses[it] }
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://github.com/login/oauth/authorize?"), url)
        assertTrue(url.contains("client_id=Ov23lidtiR4LeVZvVRNL"), url)
        assertTrue(url.contains("scope=user%3Aemail"), url)
    }

    @Test
    fun `站点没开 GitHub 登录时返回 null 而不是假授权`() {
        val noOauth = mapOf(
            "https://agentrouter.org/api/status" to
                """{"success":true,"data":{"github_oauth":false,"github_client_id":""}}""",
        )
        assertNull(CheckinOauthPolicy.fetchAuthorizeParams(agentrouter) { noOauth[it] })
    }

    @Test
    fun `任一接口失败都返回 null`() {
        // status 拿不到
        assertNull(CheckinOauthPolicy.fetchAuthorizeParams(agentrouter) { null })
        // status 有、state 拿不到
        val onlyStatus = mapOf(
            "https://agentrouter.org/api/status" to
                """{"success":true,"data":{"github_client_id":"Ov1"}}""",
        )
        assertNull(CheckinOauthPolicy.fetchAuthorizeParams(agentrouter) { onlyStatus[it] })
        // 返回 HTML（WAF 拦截）
        val html = mapOf(
            "https://agentrouter.org/api/status" to "<html>captcha</html>",
            "https://agentrouter.org/api/oauth/state?mode=login" to "<html>captcha</html>",
        )
        assertNull(CheckinOauthPolicy.fetchAuthorizeParams(agentrouter) { html[it] })
    }

    @Test
    fun `state 里的特殊字符会被正确转义`() {
        val responses = mapOf(
            "https://agentrouter.org/api/status" to """{"data":{"github_client_id":"Ov1"}}""",
            "https://agentrouter.org/api/oauth/state?mode=login" to
                """{"success":true,"data":"a+b/c=d&e"}""",
        )
        val url = CheckinOauthPolicy.fetchAuthorizeParams(agentrouter) { responses[it] }
        assertNotNull(url)
        // 未转义的 + / = & 会把 state 截断或改变含义
        assertFalse(url!!.contains("state=a+b/c=d&e"), url)
        assertTrue(url.contains("state=a%2Bb%2Fc%3Dd%26e"), url)
    }

    // ---------------------------------------------------------------- 日志判据

    @Test
    fun `日志判据只认本次窗口之后的签到记录`() {
        val runStart = 1_700_000_000_000L
        // 本次之后的一条 type=4
        val fresh = listOf<Map<String, Any?>>(
            mapOf("created_at" to runStart / 1000 + 5, "type" to 4, "content" to "每日签到成功"),
        )
        assertEquals((runStart / 1000 + 5) * 1000, CheckinLogEvidence.freshCheckinAt(fresh, runStart))

        // 早于窗口的（今天早些时候签过）：不算本次
        val old = listOf<Map<String, Any?>>(
            mapOf("created_at" to runStart / 1000 - 3600, "type" to 4, "content" to "每日签到成功"),
        )
        assertNull(CheckinLogEvidence.freshCheckinAt(old, runStart))

        // 本次之后的消费日志（type=2）：不算签到
        val consume = listOf<Map<String, Any?>>(
            mapOf("created_at" to runStart / 1000 + 5, "type" to 2, "content" to "模型调用"),
        )
        assertNull(CheckinLogEvidence.freshCheckinAt(consume, runStart))
    }

    @Test
    fun `type 缺失时靠文案兜底`() {
        val runStart = 1_700_000_000_000L
        val noType = listOf<Map<String, Any?>>(
            mapOf("created_at" to runStart / 1000 + 1, "content" to "签到成功，增加额度 $25"),
        )
        assertEquals((runStart / 1000 + 1) * 1000, CheckinLogEvidence.freshCheckinAt(noType, runStart))
    }

    @Test
    fun `日志查询必须带 type 过滤与时间下界`() {
        val path = CheckinLogEvidence.logQueryPath(1_700_000_000L)
        assertTrue(path.contains("type=4"), path)
        assertTrue(path.contains("start_timestamp=1700000000"), path)
        assertTrue(path.startsWith("/api/log/self?"), path)
    }
}
