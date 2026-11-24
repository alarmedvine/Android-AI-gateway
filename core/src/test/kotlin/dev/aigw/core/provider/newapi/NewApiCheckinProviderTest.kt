package dev.aigw.core.provider.newapi

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ProviderAccount
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 签到 provider 的协议回归。
 *
 * 全部打到本机 [HttpServer]，不碰真实上游：签到判定、余额换算、cookie 校验这些
 * 「上线后才发现就晚了」的逻辑，必须能在 CI 里跑。
 */
class NewApiCheckinProviderTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    /** 记下最近一次请求的方法、路径与 Cookie 头，供断言。 */
    private var lastMethod: String = ""
    private var lastPath: String = ""
    private var lastCookie: String = ""

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = null
        port = server.address.port
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun profile(
        signInPath: String = "",
        mode: CheckinMode = CheckinMode.API,
    ) = NewApiSiteProfile(
        key = "test",
        name = "Test Site",
        domain = "http://127.0.0.1:$port",
        signInPath = signInPath,
        checkinMode = mode,
    )

    private fun respond(path: String, status: Int, body: String, capture: Boolean = true) {
        server.createContext(path) { exchange: HttpExchange ->
            if (capture) {
                lastMethod = exchange.requestMethod
                lastPath = exchange.requestURI.path
                lastCookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun account(secret: String = """{"cookies":"session=abc; acw_tc=zz","apiUserId":"42"}""") =
        ProviderAccount(
            providerId = "checkin:test",
            uid = "42",
            nickname = "测试账号",
            secret = secret,
        )

    private fun selfOk(
        quota: Long = 25_000_000,
        used: Long = 500_000,
        checkedIn: Boolean = false,
    ) = """
        {"success":true,"data":{"id":42,"username":"u","display_name":"测试账号",
        "quota":$quota,"used_quota":$used,"checked_in":$checkedIn}}
    """.trimIndent()

    /** 只回放固定结局的签到执行器，用来钉住 provider 的判据接线。 */
    private class StubOauth(private val outcome: CheckinOauthClient.Outcome) : CheckinOauthRunner {
        override fun run(siteCookies: Map<String, String>, githubCookies: String): CheckinOauthClient.Outcome =
            outcome
    }

    // ------------------------------------------------------------------ 余额

    @Test
    fun `余额按 500000 quota 折算成美元`() {
        respond("/api/user/self", 200, selfOk(quota = 25_000_000, used = 500_000))
        val provider = NewApiCheckinProvider(profile())
        val info = assertNotNull(provider.creditInfo(account()))
        assertTrue(info.known)
        assertEquals(25_000_000L, info.balance, "余额保存原始 quota，不截断成整数美元")
        assertEquals("usd_quota:500000", info.unit)
        assertTrue(info.detail.contains("$50.00"), "detail 应带美元口径：${info.detail}")
        assertTrue(info.detail.contains("$1.00"), "已用额度也要换算：${info.detail}")
    }

    @Test
    fun `会话失效时余额标记为未知而不是 0`() {
        respond("/api/user/self", 401, """{"success":false,"message":"无权进行此操作，未登录且未提供 access token"}""")
        val provider = NewApiCheckinProvider(profile())
        val info = assertNotNull(provider.creditInfo(account()))
        assertFalse(info.known)
        assertTrue(info.detail.contains("未登录"), info.detail)
    }

    // ------------------------------------------------------------------ 导入

    @Test
    fun `导入时用 cookie 校验并落 uid 与昵称`() {
        respond("/api/user/self", 200, selfOk())
        val provider = NewApiCheckinProvider(profile())
        val imported = assertNotNull(provider.importCredentials("session=abc; acw_tc=zz"))
        assertEquals("checkin:test", imported.providerId)
        assertEquals("42", imported.uid)
        assertEquals("测试账号", imported.nickname)
        val secret = JsonParser.parseString(imported.secret).asJsonObject
        assertEquals("42", secret.get("apiUserId").asString)
        assertTrue(secret.get("cookies").asString.contains("session=abc"))
        assertEquals("session=abc; acw_tc=zz", lastCookie)
    }

    @Test
    fun `导入 JSON 形式与裸 cookie 串等价`() {
        respond("/api/user/self", 200, selfOk())
        val provider = NewApiCheckinProvider(profile())
        val imported = assertNotNull(
            provider.importCredentials("""{"cookies":"Cookie: session=abc","apiUserId":"42"}"""),
        )
        assertEquals("42", imported.uid)
    }

    @Test
    fun `缺 session 的 cookie 直接拒绝`() {
        val provider = NewApiCheckinProvider(profile())
        assertFailsWith<IllegalStateException> { provider.importCredentials("acw_tc=only") }
    }

    @Test
    fun `上游拒绝时导入失败而不是静默成功`() {
        respond("/api/user/self", 401, """{"success":false,"message":"未登录"}""")
        val provider = NewApiCheckinProvider(profile())
        val error = assertFailsWith<IllegalStateException> {
            provider.importCredentials("session=stale")
        }
        assertTrue(error.message.orEmpty().contains("未登录"), error.message.orEmpty())
    }

    // ------------------------------------------------------------------ 签到

    @Test
    fun `无签到接口的站点不能靠查询用户信息冒充签到`() {
        // 这条曾经断言「查一次 /api/user/self 就算签到成功」，那是错的：
        // AgentRouter 的额度只在一次新的 OAuth 登录里发放，查余额永远不加额度。
        // 现在没有保存 GitHub 会话时，必须明确失败并指出要先授权一次。
        respond("/api/user/self", 200, selfOk(quota = 30_000_000))
        val provider = NewApiCheckinProvider(profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN))
        val result = provider.performAction(account(), ACTION_CHECKIN, com.google.gson.JsonObject())
        assertFalse(result.ok, result.message)
        // 文案会改，所以断言的是 requiresUi 这个位而不是措辞
        assertTrue(result.requiresUi, "必须标记为需要界面接手：${result.message}")
        // 关键：不能再悄悄打 /api/user/self 然后报成功
        assertTrue(lastMethod.isNullOrEmpty(), "不该发出任何请求，实际 $lastMethod")
    }

    @Test
    fun `有 GitHub 会话时批量签到会真的执行而不是只提示`() {
        // 批量签到（AutoCheckin）没有 UI，必须能纯 HTTP 完成。
        // 这里验证它至少不会在"有 GitHub 会话"时直接返回"请手动点"。
        respond("/api/user/self", 200, selfOk())
        val provider = NewApiCheckinProvider(profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN))
        val withGh = account(secret = """{"cookies":"session=abc","apiUserId":"42","githubCookies":"user_session=gh"}""")
        val result = provider.performAction(withGh, ACTION_CHECKIN, com.google.gson.JsonObject())
        // 本地没有真 GitHub，流程会失败，但**不能**是"请手动点"那条文案
        assertFalse(result.ok, result.message)
        assertFalse(
            result.message.contains("请点一次「重新授权签到」"),
            "有 GitHub 会话时不该再要求手动操作：${result.message}",
        )
    }

    @Test
    fun `OAuth 站点签到时余额涨了才报成功`() {
        // 签到前 25 美元，签到后 37.5 美元 → 必须报成功并带新增额
        var selfCalls = 0
        server.createContext("/api/user/self") { exchange: HttpExchange ->
            selfCalls++
            val quota = if (selfCalls == 1) 25_000_000L else 37_500_000L
            val bytes = selfOk(quota = quota).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val provider = NewApiCheckinProvider(
            profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN),
            oauthRunner = StubOauth(
                CheckinOauthClient.Outcome.Completed(
                    cookies = emptyMap(),
                    checkedIn = true,
                    body = """{"success":true,"data":{"id":42,"checked_in":true}}""",
                ),
            ),
        )
        val withGh = account(secret = """{"cookies":"session=abc","apiUserId":"42","githubCookies":"user_session=gh"}""")
        val result = provider.performAction(withGh, ACTION_CHECKIN, com.google.gson.JsonObject())
        assertTrue(result.ok, result.message)
        assertTrue(result.message.contains("25.00"), "应报出新增 25.00 美元：${result.message}")
    }

    @Test
    fun `余额没变但站点说今天已签过时算达成不算失败`() {
        // 当天已签过：额度本来就不会再涨。若报失败，自动签到会每小时重走一遍 OAuth，
        // 一整天刷满失败记录，还可能触发上游风控。
        // selfOk 默认不带 checked_in，这里显式给 true。
        respond("/api/user/self", 200, selfOk(quota = 25_000_000, checkedIn = true))
        val provider = NewApiCheckinProvider(
            profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN),
            oauthRunner = StubOauth(
                CheckinOauthClient.Outcome.Completed(
                    cookies = emptyMap(),
                    checkedIn = true,
                    body = """{"success":true,"data":{"id":42,"checked_in":true}}""",
                ),
            ),
        )
        val withGh = account(secret = """{"cookies":"session=abc","apiUserId":"42","githubCookies":"user_session=gh"}""")
        val result = provider.performAction(withGh, ACTION_CHECKIN, com.google.gson.JsonObject())
        assertTrue(result.ok, "今天已签过不是失败：${result.message}")
        assertTrue(result.message.contains("已经签过"), result.message)
    }

    @Test
    fun `余额没变且站点没说签过时必须报失败`() {
        // 上一版就是在这里报成功，用户看到「签到成功」而余额不变。
        // selfOk 默认 checked_in=false，模拟「登录了但额度真没发」。
        respond("/api/user/self", 200, selfOk(quota = 25_000_000, checkedIn = false))
        val provider = NewApiCheckinProvider(
            profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN),
            oauthRunner = StubOauth(
                CheckinOauthClient.Outcome.Completed(
                    cookies = emptyMap(),
                    checkedIn = false,
                    body = """{"success":true,"data":{"id":42,"checked_in":false}}""",
                ),
            ),
        )
        val withGh = account(secret = """{"cookies":"session=abc","apiUserId":"42","githubCookies":"user_session=gh"}""")
        val result = provider.performAction(withGh, ACTION_CHECKIN, com.google.gson.JsonObject())
        assertFalse(result.ok, "额度没变且站点没说签过，绝不能报成功：${result.message}")
        assertTrue(result.message.contains("没有变化"), result.message)
    }

    @Test
    fun `OAuth 兑换被站点拒绝时报失败而不是成功`() {
        respond("/api/user/self", 200, selfOk())
        val provider = NewApiCheckinProvider(
            profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN),
            oauthRunner = StubOauth(CheckinOauthClient.Outcome.Failed("GitHub 授权失败，请重新登录 GitHub 授权")),
        )
        val withGh = account(secret = """{"cookies":"session=abc","apiUserId":"42","githubCookies":"user_session=gh"}""")
        val result = provider.performAction(withGh, ACTION_CHECKIN, com.google.gson.JsonObject())
        assertFalse(result.ok, result.message)
        assertTrue(result.message.contains("GitHub 授权失败"), result.message)
    }

    @Test
    fun `OAuth 需要重新登录时标记为界面接手`() {
        respond("/api/user/self", 200, selfOk())
        val provider = NewApiCheckinProvider(
            profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN),
            oauthRunner = StubOauth(CheckinOauthClient.Outcome.NeedsRelogin),
        )
        val withGh = account(secret = """{"cookies":"session=abc","apiUserId":"42","githubCookies":"user_session=gh"}""")
        val result = provider.performAction(withGh, ACTION_CHECKIN, com.google.gson.JsonObject())
        assertFalse(result.ok, result.message)
        assertTrue(result.requiresUi, "必须标记界面接手：${result.message}")
    }

    @Test
    fun `导入时保存 github 会话，供签到免登录`() {
        respond("/api/user/self", 200, selfOk())
        val provider = NewApiCheckinProvider(profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN))
        val raw = """{"cookies":"session=abc","apiUserId":"42","githubCookies":"user_session=gh; logged_in=yes"}"""
        val imported = assertNotNull(provider.importCredentials(raw))
        // 存进去
        assertEquals("user_session=gh; logged_in=yes", provider.githubCookiesOf(imported))
    }

    @Test
    fun `没带 github 会话的导入不会凭空造一个`() {
        respond("/api/user/self", 200, selfOk())
        val provider = NewApiCheckinProvider(profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN))
        val imported = assertNotNull(provider.importCredentials("""{"cookies":"session=abc","apiUserId":"42"}"""))
        assertEquals("", provider.githubCookiesOf(imported))
    }

    @Test
    fun `两个账号的 github 会话互不串`() {
        respond("/api/user/self", 200, selfOk())
        val provider = NewApiCheckinProvider(profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN))
        val a = assertNotNull(provider.importCredentials(
            """{"cookies":"session=a","apiUserId":"42","githubCookies":"user_session=ghA"}""",
        ))
        val b = assertNotNull(provider.importCredentials(
            """{"cookies":"session=b","apiUserId":"42","githubCookies":"user_session=ghB"}""",
        ))
        // 这条是防「签成别人账号」的关键：两个账号必须各存各的
        assertEquals("user_session=ghA", provider.githubCookiesOf(a))
        assertEquals("user_session=ghB", provider.githubCookiesOf(b))
    }

    @Test
    fun `OAuth 站点的签到方式对外可见`() {
        val provider = NewApiCheckinProvider(profile(signInPath = "", mode = CheckinMode.OAUTH_RELOGIN))
        assertEquals(CheckinMode.OAUTH_RELOGIN, provider.checkinMode())
        assertEquals("http://127.0.0.1:$port", provider.siteProfile().domain)
    }

    @Test
    fun `有签到接口的站点走 POST 并识别成功`() {
        respond("/api/user/sign_in", 200, """{"success":true,"message":"签到成功"}""")
        val provider = NewApiCheckinProvider(profile(signInPath = "/api/user/sign_in"))
        val result = provider.performAction(account(), ACTION_CHECKIN, com.google.gson.JsonObject())
        assertTrue(result.ok, result.message)
        assertTrue(result.message.contains("签到成功"), result.message)
        assertEquals("POST", lastMethod)
        assertEquals("/api/user/sign_in", lastPath)
    }

    @Test
    fun `已签到文案也算成功`() {
        respond("/api/user/sign_in", 200, """{"success":false,"message":"今日已签到"}""")
        val provider = NewApiCheckinProvider(profile(signInPath = "/api/user/sign_in"))
        val result = provider.performAction(account(), ACTION_CHECKIN, com.google.gson.JsonObject())
        assertTrue(result.ok, result.message)
        assertTrue(result.message.contains("今日已签到"), result.message)
    }

    @Test
    fun `签到失败原样回传上游文案`() {
        respond("/api/user/sign_in", 200, """{"success":false,"message":"今日签到已关闭"}""")
        val provider = NewApiCheckinProvider(profile(signInPath = "/api/user/sign_in"))
        val result = provider.performAction(account(), ACTION_CHECKIN, com.google.gson.JsonObject())
        assertFalse(result.ok)
        assertTrue(result.message.contains("今日签到已关闭"), result.message)
    }

    @Test
    fun `只有签到能力不提供对话`() {
        val provider = NewApiCheckinProvider(profile())
        val call = provider.openChat(account(), "{}")
        assertEquals(501, call.status)
        assertTrue(provider.capabilities.contains(dev.aigw.core.provider.ProviderCapability.CHECKIN))
    }

    @Test
    fun `POST成功字段不得字符串强转或小数截断`() {
        respond("/api/user/sign_in", 200, """{"success":"true","ret":1.5,"code":0.5}""")
        val result = NewApiCheckinProvider(profile("/api/user/sign_in"))
            .performAction(account(), ACTION_CHECKIN, com.google.gson.JsonObject())
        assertFalse(result.ok)
    }

    @Test
    fun `HTML含已签到字样也不能算POST成功`() {
        respond("/api/user/sign_in", 200, "<html>今日已签到</html>")
        val result = NewApiCheckinProvider(profile("/api/user/sign_in"))
            .performAction(account(), ACTION_CHECKIN, com.google.gson.JsonObject())
        assertFalse(result.ok)
    }

    // ------------------------------------------------------------------ cookie

    @Test
    fun `cookie 解析宽松处理前缀换行与多余分号`() {
        val parsed = CheckinCookie.parse("Cookie: session=a; acw_tc=b;;\n cdn_sec_tc=c")
        assertEquals("a", parsed["session"])
        assertEquals("b", parsed["acw_tc"])
        assertEquals("c", parsed["cdn_sec_tc"])
        assertEquals(3, parsed.size)
    }

    @Test
    fun `cookie 序列化后可原样解析回来`() {
        val source = mapOf("session" to "x", "acw_tc" to "y")
        val text = CheckinCookie.format(source)
        assertEquals(source, CheckinCookie.parse(text))
    }
}
