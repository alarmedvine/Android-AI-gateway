package dev.aigw.core.provider.newapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「签到到底加没加额度」判据的回归。
 *
 * 这组用例存在的理由：上一版用 `checked_in` 当**唯一**判据，导致
 * 「每天报成功、额度一分没涨」。但反过来只用余额差也有坑：当天已签过时余额同样不动，
 * 若一律报失败，自动签到会每小时重走一遍 OAuth 刷满失败记录。
 * 所以两种信号必须组合，下面每条钉住一种组合。
 */
class CheckinQuotaDeltaTest {

    @Test
    fun `余额涨了才算签到成功`() {
        val v = CheckinQuotaDelta.verdict(before = 25_000_000, after = 37_500_000)
        assertEquals(CheckinQuotaDelta.Kind.INCREASED, v.kind)
        assertEquals(12_500_000, v.delta)
        assertEquals(37_500_000, v.after)
        assertTrue(v.countsAsCheckin)
        assertFalse(v.isFailure)
    }

    @Test
    fun `余额涨了即使站点没说签过也算成功`() {
        // 余额是硬证据，优先于 checked_in。
        val v = CheckinQuotaDelta.verdict(before = 0, after = 12_500_000, claimedCheckedIn = false)
        assertEquals(CheckinQuotaDelta.Kind.INCREASED, v.kind)
        assertTrue(v.countsAsCheckin)
    }

    @Test
    fun `余额没变但站点说今天已签过时算达成不算失败`() {
        // 这是自动签到的关键分支：若报失败，会每小时重走一遍 OAuth，
        // 一整天刷满失败记录，还可能触发上游风控。
        val v = CheckinQuotaDelta.verdict(before = 25_000_000, after = 25_000_000, claimedCheckedIn = true)
        assertEquals(CheckinQuotaDelta.Kind.ALREADY_DONE, v.kind)
        assertEquals(0, v.delta)
        assertTrue(v.countsAsCheckin, "今天的目标已达成")
        assertFalse(v.isFailure, "不能算失败，否则会无限重试")
    }

    @Test
    fun `余额没变且站点没说签过时报真失败`() {
        // 这就是上一版翻车的场景，只是这次必须如实报失败。
        val v = CheckinQuotaDelta.verdict(before = 25_000_000, after = 25_000_000, claimedCheckedIn = false)
        assertEquals(CheckinQuotaDelta.Kind.NOT_CREDITED, v.kind)
        assertFalse(v.countsAsCheckin)
        assertTrue(v.isFailure)
    }

    @Test
    fun `余额变少不能当成新增`() {
        // 同期消费会让 after < before。差额取负是错的（会显示「新增 -3 美元」）。
        val v = CheckinQuotaDelta.verdict(before = 30_000_000, after = 28_000_000, claimedCheckedIn = false)
        assertEquals(CheckinQuotaDelta.Kind.NOT_CREDITED, v.kind)
        assertEquals(0, v.delta)
        assertFalse(v.countsAsCheckin)
    }

    @Test
    fun `余额没变但不知道签没签过时不判失败`() {
        // 没有 checked_in 信号时不能武断：既不能报成功，也不能刷失败。
        val v = CheckinQuotaDelta.verdict(before = 25_000_000, after = 25_000_000, claimedCheckedIn = null)
        assertEquals(CheckinQuotaDelta.Kind.AFTER_ONLY, v.kind)
        assertFalse(v.countsAsCheckin)
        assertFalse(v.isFailure)
    }

    @Test
    fun `只有签到后余额时不算成功也不算失败`() {
        val v = CheckinQuotaDelta.verdict(before = null, after = 25_000_000)
        assertEquals(CheckinQuotaDelta.Kind.AFTER_ONLY, v.kind)
        assertEquals(25_000_000, v.after)
        assertFalse(v.countsAsCheckin)
        assertFalse(v.isFailure)
    }

    @Test
    fun `两次都拿不到时不算成功也不算失败`() {
        val v = CheckinQuotaDelta.verdict(before = null, after = null)
        assertEquals(CheckinQuotaDelta.Kind.UNKNOWN, v.kind)
        assertEquals(null, v.after)
        assertFalse(v.countsAsCheckin)
        assertFalse(v.isFailure)
    }

    @Test
    fun `从零涨到非零也算成功`() {
        // 余额为 0 的账号签到后拿到额度：before=0 不能被当成「没读到」。
        val v = CheckinQuotaDelta.verdict(before = 0, after = 12_500_000)
        assertEquals(CheckinQuotaDelta.Kind.INCREASED, v.kind)
        assertEquals(12_500_000, v.delta)
    }

    @Test
    fun `零到零且站点说已签过时算达成`() {
        val v = CheckinQuotaDelta.verdict(before = 0, after = 0, claimedCheckedIn = true)
        assertEquals(CheckinQuotaDelta.Kind.ALREADY_DONE, v.kind)
        assertTrue(v.countsAsCheckin)
    }
}
