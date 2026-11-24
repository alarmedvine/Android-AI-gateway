package dev.aigw.core.provider.newapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「签到到底加没加额度」判据的回归。
 *
 * 这组用例存在的理由：上一版用 `checked_in` 当判据，导致「每天报成功、额度一分没涨」。
 * 下面每一条都钉住一种必须**不**报成功的情形。
 */
class CheckinQuotaDeltaTest {

    @Test
    fun `余额涨了才算签到成功`() {
        val v = CheckinQuotaDelta.verdict(before = 25_000_000, after = 37_500_000)
        assertEquals(CheckinQuotaDelta.Kind.INCREASED, v.kind)
        assertEquals(12_500_000, v.delta)
        assertEquals(37_500_000, v.after)
        assertTrue(v.countsAsCheckin)
    }

    @Test
    fun `余额没变不算签到成功`() {
        // 这是最关键的负控：今天已签过的账号再登录，checked_in 仍是 true，
        // 但额度不动 —— 必须报 UNCHANGED，绝不能报成功。
        val v = CheckinQuotaDelta.verdict(before = 25_000_000, after = 25_000_000)
        assertEquals(CheckinQuotaDelta.Kind.UNCHANGED, v.kind)
        assertEquals(0, v.delta)
        assertFalse(v.countsAsCheckin)
    }

    @Test
    fun `余额变少不能当成新增`() {
        // 同期消费会让 after < before。差额取负是错的（会显示「新增 -3 美元」）。
        val v = CheckinQuotaDelta.verdict(before = 30_000_000, after = 28_000_000)
        assertEquals(CheckinQuotaDelta.Kind.UNCHANGED, v.kind)
        assertEquals(0, v.delta)
        assertFalse(v.countsAsCheckin)
    }

    @Test
    fun `只有签到后余额时不算成功`() {
        val v = CheckinQuotaDelta.verdict(before = null, after = 25_000_000)
        assertEquals(CheckinQuotaDelta.Kind.AFTER_ONLY, v.kind)
        assertEquals(25_000_000, v.after)
        assertFalse(v.countsAsCheckin)
    }

    @Test
    fun `两次都拿不到时不算成功`() {
        val v = CheckinQuotaDelta.verdict(before = null, after = null)
        assertEquals(CheckinQuotaDelta.Kind.UNKNOWN, v.kind)
        assertEquals(null, v.after)
        assertFalse(v.countsAsCheckin)
    }

    @Test
    fun `从零涨到非零也算成功`() {
        // 余额为 0 的账号签到后拿到额度：before=0 不能被当成「没读到」。
        val v = CheckinQuotaDelta.verdict(before = 0, after = 12_500_000)
        assertEquals(CheckinQuotaDelta.Kind.INCREASED, v.kind)
        assertEquals(12_500_000, v.delta)
    }

    @Test
    fun `零到零不算成功`() {
        val v = CheckinQuotaDelta.verdict(before = 0, after = 0)
        assertEquals(CheckinQuotaDelta.Kind.UNCHANGED, v.kind)
        assertFalse(v.countsAsCheckin)
    }
}
