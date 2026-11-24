package dev.aigw.core.provider.newapi

/**
 * 「这次签到到底加没加额度」的纯判据。
 *
 * ## 为什么不能信 `checked_in`
 *
 * 站点前端拿到登录响应里的 `data.checked_in` 就提示「签到成功，新增额度已到账」，
 * 但**当天已经签过**的账号再登录一次，这个位同样是 true。它回答的是
 * 「今天签过没有」，不是「刚才这一次加了没有」。把它当本次成功的证据，
 * 结果就是「每天点一次都报成功、额度一分没涨」——上一版正是栽在这里。
 *
 * ## 唯一硬判据：签到前后余额差
 *
 * `after > before` ⇒ 本次确实新增。这与 checkin-agentrouter（jiang7f）的
 * `calculate_check_in_reward` 同一口径（`max(after - before, 0)`），
 * 也是用户唯一真正关心的那个数字。
 *
 * 三种边界必须分开报，不能混成一句「成功」：
 * - 两次读数都在、`after == before` → **没加钱**，报失败（可能是今天已签过）
 * - 只有 `after` → 登录成功但无法证明加钱，如实说「已重新登录」
 * - 都没有 → 同上，并提示刷新
 */
object CheckinQuotaDelta {

    /** 一次判定的结论。 */
    enum class Kind {
        /** 余额确实增加了：本次签到成功。 */
        INCREASED,

        /** 余额没变：登录成功了，但额度没有发放（多半是今天已签过）。 */
        UNCHANGED,

        /** 只拿到签到后余额，无法比较（签到前查询失败）。 */
        AFTER_ONLY,

        /** 两次都没拿到。 */
        UNKNOWN,
    }

    data class Verdict(
        val kind: Kind,
        /** 本次新增的 quota 原值；[Kind.INCREASED] 时为正。 */
        val delta: Long = 0,
        /** 签到后余额（quota 原值），没拿到则为 null。 */
        val after: Long? = null,
    ) {
        /** 只有真的加钱了才算签到成功。 */
        val countsAsCheckin: Boolean get() = kind == Kind.INCREASED
    }

    /**
     * 判定。
     *
     * @param before 签到前 `quota` 原值；查询失败传 null
     * @param after 签到后 `quota` 原值；查询失败传 null
     */
    fun verdict(before: Long?, after: Long?): Verdict = when {
        before != null && after != null && after > before ->
            Verdict(Kind.INCREASED, delta = after - before, after = after)
        before != null && after != null ->
            // after == before 或（异常的）after < before：额度没有增加。
            // 注意不能把 after < before 当成「加了很多」——那只会是同期消费。
            Verdict(Kind.UNCHANGED, delta = 0, after = after)
        after != null -> Verdict(Kind.AFTER_ONLY, after = after)
        else -> Verdict(Kind.UNKNOWN)
    }
}
