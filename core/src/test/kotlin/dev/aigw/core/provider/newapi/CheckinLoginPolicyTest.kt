package dev.aigw.core.provider.newapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckinLoginPolicyTest {
    private val domain = "https://agentrouter.org"

    @Test fun `只接受同源HTTPS站点`() {
        assertTrue(CheckinLoginPolicy.isSitePage("https://agentrouter.org/login", domain))
        assertFalse(CheckinLoginPolicy.isSitePage("https://agentrouter.org.evil.test/login", domain))
        assertFalse(CheckinLoginPolicy.isSitePage("https://evil.test/?next=agentrouter.org", domain))
        assertFalse(CheckinLoginPolicy.isSitePage("https://agentrouter.org@evil.test/login", domain))
        assertFalse(CheckinLoginPolicy.isSitePage("http://agentrouter.org/login", domain))
        assertFalse(CheckinLoginPolicy.isSitePage("https://agentrouter.org:8443/login", domain))
        assertFalse(CheckinLoginPolicy.isSitePage(null, domain))
    }

    @Test fun `登录页与OAuth中转即使有session也不读取身份`() {
        assertFalse(CheckinLoginPolicy.canReadIdentity("$domain/login", domain))
        assertFalse(CheckinLoginPolicy.canReadIdentity("$domain/oauth/github?code=fixture", domain))
        assertFalse(CheckinLoginPolicy.canReadIdentity("$domain/api/oauth/state", domain))
        assertTrue(CheckinLoginPolicy.canReadIdentity("$domain/console/token", domain))
        assertTrue(CheckinLoginPolicy.canReadIdentity("$domain/", domain))
    }

    @Test fun `JS返回必须是合法正整数UID字符串`() {
        assertEquals("42", CheckinLoginPolicy.pageUserId("\"42\""))
        for (bad in listOf(null, "null", "42", "\"0\"", "\"-1\"", "\"42.2\"", "{}", "\"9223372036854775808\"")) {
            assertNull(CheckinLoginPolicy.pageUserId(bad), "必须拒绝 $bad")
        }
    }
}
