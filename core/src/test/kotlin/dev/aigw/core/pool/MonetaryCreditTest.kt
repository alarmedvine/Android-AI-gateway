package dev.aigw.core.pool

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.CreditUnit
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MonetaryCreditTest {
    private fun account(uid: String) = ProviderAccount("checkin:test", uid, uid, "{}")

    @Test fun `美元quota显示精确小数原值不改`() {
        assertEquals("$2.50", CreditUnit.formatUsdQuota(1_250_000L, "usd_quota:500000"))
        assertEquals("$0.00", CreditUnit.formatUsdQuota(1L, "usd_quota:500000"))
        assertFalse(CreditUnit.isCredits("usd_quota:500000"))
        assertNull(CreditUnit.formatUsdQuota(42L, "usd_quota:0"))
        assertNull(CreditUnit.formatUsdQuota(42L, "credits"))
    }

    @Test fun `货币和未知新单位都不能混进积分汇总`() {
        val pool = AccountPool(InMemoryKeyValueStore())
        pool.upsert(account("credits"))
        pool.upsert(account("dollars"))
        pool.upsert(account("unknown"))
        pool.updateCredits("checkin:test", "credits", 900L, true, "", "credits")
        pool.updateCredits("checkin:test", "dollars", 1_250_000L, true, "余额 $2.50", "usd_quota:500000")
        pool.updateCredits("checkin:test", "unknown", 500L, true, "", "future-unit")
        assertEquals(900L, pool.summary().totalCredits)
        assertEquals(1, pool.summary().creditsKnown)
    }

    @Test fun `冷重开与UID迁移都保留原quota和单位`() {
        val store = InMemoryKeyValueStore()
        val pool = AccountPool(store)
        pool.upsert(account("a"))
        pool.updateCredits("checkin:test", "a", 1_250_000L, true, "余额 $2.50", "usd_quota:500000")
        pool.renameAccount("checkin:test", "a", account("b"))
        val cold = AccountPool(store).status("checkin:test", "b")!!
        assertEquals(1_250_000L, cold.credits)
        assertEquals("usd_quota:500000", cold.creditUnit)
        assertEquals("$2.50", CreditUnit.formatUsdQuota(cold.credits, cold.creditUnit))
        assertEquals(0L, AccountPool(store).summary().totalCredits)
    }
}
