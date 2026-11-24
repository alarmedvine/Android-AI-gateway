package dev.aigw.core.provider.newapi

/**
 * 「这次签到到底加没加额度」的纯判据。
 *
 * ## 为什么不能只信 `checked_in`
 *
 * 站点前端拿到登录响应里的 `data.checked_in` 就提示「签到成功，新增额度已到账」，
 * 但**当天已经签过**的账号再登录一次，这个位同样是 true。它回答的是
 * 「今天签过没有」，不是「刚才这一次加了没有」。把它当**唯一**证据，
 * 结果就是「每天点一次都报成功、额度一分没涨」——上一版正是栽在这里。
 *
 * ## 唯一硬判据：签到前后余额差
 *
 * `after > before` ⇒ 本次确实新增。这与 checkin-agentrouter（jiang7f）的
 * `calculate_check_in_reward` 同一口径（`max(after - before, 0)`），
 * 也是用户唯一真正关心的那个数字。
 *
 * ## 但余额没变有两种完全不同的原因，必须分开
 *
 * | 余额 | `checked_in` | 含义 | 该报什么 |
 * |---|---|---|---|
 * | 涨了 | 任意 | 本次真的签到了 | 成功（带新增额） |
 * | 没变 | true | 今天早些时候已经签过 | **已签过**（不是失败！） |
 * | 没变 | false | 登录成功但站点没发额度 | 失败 |
 * | 没变 | 未知 | 无法区分 | 存疑，不算成功也不算失败 |
 *
 * 中间那行是**必须**区分出来的：否则自动签到会把「今天已签过」当成失败，
 * 每小时重试一次、重走一遍 OAuth，一整天刷满失败记录，还可能触发上游风控。
 */
object CheckinQuotaDelta {

    /** 一次判定的结论。 */
    enum class Kind {
        /** 余额确实增加了：本次签到成功。 */
        INCREASED,

        /** 余额没变，但站点说今天已签过：今天的目标已达成，**不是失败**。 */
        ALREADY_DONE,

        /** 余额没变，站点也没说今天签过：登录成功但额度没发，是真失败。 */
        NOT_CREDITED,

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
        /** 今天的目标达成了吗（本次新增，或今天早先已签过）。 */
        val countsAsCheckin: Boolean
            get() = kind == Kind.INCREASED || kind == Kind.ALREADY_DONE

        /** 这次运行算不算失败（决定自动签到要不要继续重试）。 */
        val isFailure: Boolean get() = kind == Kind.NOT_CREDITED
    }

    /**
     * 判定。
     *
     * @param before 签到前 `quota` 原值；查询失败传 null
     * @param after 签到后 `quota` 原值；查询失败传 null
     * @param claimedCheckedIn 站点响应里的 `checked_in`；null = 没拿到 / 响应不认
     */
    fun verdict(before: Long?, after: Long?, claimedCheckedIn: Boolean? = null): Verdict = when {
        before != null && after != null && after > before ->
            Verdict(Kind.INCREASED, delta = after - before, after = after)
        before != null && after != null ->
            // after == before 或（异常的）after < before：额度没有增加。
            // 注意不能把 after < before 当成「加了很多」——那只会是同期消费。
            when (claimedCheckedIn) {
                true -> Verdict(Kind.ALREADY_DONE, after = after)
                false -> Verdict(Kind.NOT_CREDITED, after = after)
                null -> Verdict(Kind.AFTER_ONLY, after = after)
            }
        after != null -> Verdict(Kind.AFTER_ONLY, after = after)
        else -> Verdict(Kind.UNKNOWN)
    }
}
