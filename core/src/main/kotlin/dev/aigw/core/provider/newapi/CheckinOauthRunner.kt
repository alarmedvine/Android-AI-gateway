package dev.aigw.core.provider.newapi

/**
 * 「走一遍 GitHub OAuth 完成签到」这件事的可替换执行器。
 *
 * 存在理由只有一个：**让判据接线可测**。
 *
 * `NewApiCheckinProvider.oauthCheckin` 里最关键的一段不是 HTTP，而是
 * 「签到前余额 → OAuth → 签到后余额 → 涨了才算成功」。如果这条接线只能靠真账号验证，
 * 那么「额度没变却报成功」的回归就永远只能在真机上被发现 —— 上一版正是这样漏掉的。
 *
 * 生产实现是 [Real]（连真 GitHub），单测注入一个只回放固定结局的桩。
 */
interface CheckinOauthRunner {

    fun run(siteCookies: Map<String, String>, githubCookies: String): CheckinOauthClient.Outcome

    /** 生产实现：真的去 GitHub 走一遍授权。 */
    class Real(private val profile: NewApiSiteProfile) : CheckinOauthRunner {
        override fun run(siteCookies: Map<String, String>, githubCookies: String): CheckinOauthClient.Outcome =
            CheckinOauthClient(profile).checkin(siteCookies, githubCookies)
    }
}
