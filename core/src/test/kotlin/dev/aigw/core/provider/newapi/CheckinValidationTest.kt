package dev.aigw.core.provider.newapi

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.gateway.AutoCheckin
import dev.aigw.core.gateway.AutoCheckinResult
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.CreditUnit
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.util.startOfDay
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckinValidationTest {
    private fun withResponse(status: Int, body: String, test: (NewApiCheckinProvider) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/user/self") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            test(NewApiCheckinProvider(NewApiSiteProfile("test", "Test", "http://127.0.0.1:${server.address.port}", signInPath = "")))
        } finally { server.stop(0) }
    }

    private fun account() = ProviderAccount("checkin:test", "42", "Test", """{"cookies":"session=fixture-only","apiUserId":"42"}""")
    private fun self(id: String = "42", quota: String = "1250000") = """{"success":true,"data":{"id":$id,"quota":$quota,"used_quota":0}}"""
    private fun reject(body: String) = withResponse(200, body) { provider ->
        assertFalse(provider.performAction(account(), ACTION_CHECKIN, JsonObject()).ok)
        assertFalse(assertNotNull(provider.creditInfo(account())).known)
        assertFailsWith<IllegalStateException> { provider.importCredentials("session=fixture-only") }
    }

    @Test fun `HTML验证页面不能当作签到成功`() = reject("<html>security check</html>")
    @Test fun `空响应不能当作签到成功`() = reject("")
    @Test fun `success缺失不能当作成功`() = reject("""{"data":{"id":42,"quota":1250000}}""")
    @Test fun `success为字符串不能当作布尔成功`() = reject("""{"success":"true","data":{"id":42,"quota":1250000}}""")
    @Test fun `success为空不能当作成功`() = reject("""{"success":null,"data":{"id":42,"quota":1250000}}""")
    @Test fun `data缺失不能当作成功`() = reject("""{"success":true}""")
    @Test fun `id为零不能建账号`() = reject(self(id = "0"))
    @Test fun `id为负数不能建账号`() = reject(self(id = "-2"))
    @Test fun `id为小数不能建账号`() = reject(self(id = "42.5"))
    @Test fun `额度为小数不能被Long静默截断`() = reject(self(quota = "0.5"))
    @Test fun `额度溢出不能被Long静默截断`() = reject(self(quota = "9223372036854775808"))
    @Test fun `负额度不能被当成有效响应`() = reject(self(quota = "-1"))

    @Test fun `响应身份变化不准签到或刷新当前账号`() = withResponse(200, self(id = "99")) { provider ->
        assertFalse(provider.performAction(account(), ACTION_CHECKIN, JsonObject()).ok)
        assertFalse(assertNotNull(provider.creditInfo(account())).known)
        assertFailsWith<IllegalStateException> {
            provider.importCredentials("""{"cookies":"session=fixture-only","apiUserId":"42"}""")
        }
    }

    @Test fun `首次导入携带网页UID并仍按响应核对`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestUid = ""
        server.createContext("/api/user/self") { exchange ->
            requestUid = exchange.requestHeaders.getFirst("New-API-User").orEmpty()
            val bytes = self().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val provider = NewApiCheckinProvider(NewApiSiteProfile("test", "Test", "http://127.0.0.1:${server.address.port}", signInPath = ""))
            val result = assertNotNull(provider.importCredentials("""{"cookies":"session=fixture-only","apiUserId":"42"}"""))
            assertEquals("42", requestUid)
            assertEquals("42", result.uid)
        } finally { server.stop(0) }
    }

    @Test fun `美元余额保留原quota且不混入积分`() = withResponse(200, self()) { provider ->
        val credit = assertNotNull(provider.creditInfo(account()))
        assertEquals(1_250_000L, credit.balance)
        assertEquals("usd_quota:500000", credit.unit)
        assertFalse(CreditUnit.isCredits(credit.unit))
        assertEquals("$2.50", CreditUnit.formatUsdQuota(credit.balance, credit.unit))
    }

    @Test fun `验证页不会写今日完成台账且退避后可继续`() = withResponse(200, "<html>security check</html>") { provider ->
        val store = InMemoryKeyValueStore()
        var now = startOfDay(1_700_000_000_000L) + 10 * 3_600_000L
        val auto = AutoCheckin(store, nowMillis = { now }, selectTargets = { listOf(account()) }, execute = { accounts ->
            accounts.map { acc ->
                val result = provider.performAction(acc, ACTION_CHECKIN, JsonObject())
                AutoCheckinResult(acc.uid, result.ok, result.message)
            }
        })
        assertEquals(1, auto.tick().failed)
        assertFalse(auto.doneToday())
        assertEquals(null, store.read(AutoCheckin.KEY_LAST_SUCCESS_DAY))
        now += AutoCheckin.DEFAULT_RETRY_INTERVAL_MS
        assertTrue(auto.shouldRun())
    }
}
