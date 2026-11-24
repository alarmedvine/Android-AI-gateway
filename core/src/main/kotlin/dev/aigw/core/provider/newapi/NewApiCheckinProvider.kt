package dev.aigw.core.provider.newapi

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.CreditUnit
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.bodySnippet
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * NewAPI / OneAPI 系站点的**签到专用**供应商。
 *
 * 只做三件事：保存会话 cookie、每天触发一次签到、把余额换算成美元展示。
 * 对话能力刻意不实现（[openChat] 直接 501）：这类站点的聊天走 `sk-` 令牌，
 * 已经由「自定义供应商」覆盖；把两套凭证塞进一个账号只会让界面与路由变复杂。
 *
 * ## 签到协议
 *
 * - [NewApiSiteProfile.signInPath] 非空：`POST {domain}{signInPath}`，
 *   成功判定 `success == true` 或 `ret == 1`；文案命中「已签到」也算成功。
 * - 为空（AgentRouter）：`GET {domain}{userSelfPath}` —— 上游在查询用户信息时
 *   **自动完成签到**，不需要任何写请求。判据来自 anyrouter-check-in 的
 *   `utils/config.py:101`（`sign_in_path=None  # 无需签到接口，查询用户信息时自动完成签到`）。
 *
 * ## 凭证
 *
 * [ProviderAccount.secret] 是 JSON：`{"cookies":"session=…; acw_tc=…","apiUserId":"123"}`。
 * 会话 cookie 由应用内 WebView 走 OAuth 登录后读出（GitHub / LinuxDO 都行），
 * 这里不关心它怎么来的，只关心它是否还有效。
 */
class NewApiCheckinProvider(
    private val profile: NewApiSiteProfile,
    private val hooks: ProviderHooks = ProviderHooks(),
    /**
     * OAuth 签到的执行器，默认连真实 GitHub。
     *
     * 抽成可注入只为单测：`oauthCheckin` 里「签到前后余额差 → 成功/失败」这条接线
     * 必须能在本地跑，否则「额度没变却报成功」这类回归只能等真机发现。
     */
    private val oauthRunner: CheckinOauthRunner = CheckinOauthRunner.Real(profile),
) : Provider {

    override val id: String = profile.providerId

    override val displayName: String = profile.name

    /** 没有交互式登录：凭证由应用内 WebView 完成后经 [importCredentials] 写入。 */
    override val authKind: AuthKind = AuthKind.NONE

    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN)

    override fun hosts(): List<String> = listOf(profile.host).filter { it.isNotEmpty() }

    // ------------------------------------------------------------------ 聊天（不提供）

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView =
        ProviderModelCatalogView(emptyList(), fromFallback = false, error = "")

    override fun resolveModel(requested: String): String = requested

    /**
     * 签到账号不提供对话。
     *
     * 返回 501 而不是抛异常：网关会把它当成「该供应商没有这个能力」，
     * 不会污染调用记录，也不会误触发换号。
     */
    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
        FailedChatCall(501, "「${profile.name}」是签到专用账号，聊天请用自定义供应商的 sk- 令牌")

    override fun classify(status: Int, body: String): UpstreamError = when {
        status == 401 || status == 403 || CheckinCookie.isUnauthorized(body) ->
            UpstreamError(ErrorKind.SESSION_DEAD, "会话已失效，请重新登录「${profile.name}」")
        status == 429 -> UpstreamError(ErrorKind.SOFT_RATE, "上游限流")
        status >= 500 -> UpstreamError(ErrorKind.SERVER, "上游异常（HTTP $status）")
        else -> UpstreamError(ErrorKind.CLIENT, bodySnippet(body).ifEmpty { "HTTP $status" })
    }

    // ------------------------------------------------------------------ 额度

    override fun creditInfo(account: ProviderAccount): CreditInfo? = try {
        val credential = parse(account) ?: throw IllegalStateException("凭证无法解析")
        val data = fetchSelf(credential, account.uid)
        val quota = quotaValue(data, "quota", required = true)
        val used = quotaValue(data, "used_quota", required = false)
        CreditInfo(
            balance = quota,
            known = true,
            detail = "余额 ${dollars(quota)} · 已用 ${dollars(used)}",
            unit = CreditUnit.usdQuota(profile.quotaPerUnit),
        )
    } catch (e: Exception) {
        CreditInfo(0, known = false, detail = e.message ?: "查询余额失败")
    }

    // ------------------------------------------------------------------ 签到

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult = when (action) {
        ACTION_CHECKIN -> checkin(account)
        else -> ProviderActionResult.unsupported(action)
    }

    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val credential = parse(account) ?: return ProviderActionResult.failure("凭证无法解析")
        return try {
            when (profile.checkinMode) {
                CheckinMode.NONE -> return ProviderActionResult.failure("「${profile.name}」不支持签到")

                // 没有签到接口：额度只在一次新的 OAuth 登录里发放。这里**不能**用
                // 「查一次 /api/user/self」冒充签到 —— 那条路永远不加额度，却会回执成功，
                // 上一版正是这样让用户以为签到成功、实际额度一分没动。
                // 真正的签到由 CheckinOauthLoginActivity 重走一遍 GitHub 授权完成，
                // 判据见 CheckinLogClient（站点日志里的签到记录）。
                // 没有签到接口：额度只在一次新的 OAuth 登录里发放。
                // 用纯 HTTP 走完整个流程（不依赖 WebView），这样后台批量签到也能自动跑；
                // 只有 GitHub 会话真的失效时才需要用户在应用内点一次重新登录。
                CheckinMode.OAUTH_RELOGIN -> return oauthCheckin(account)

                CheckinMode.API -> Unit
            }
            if (profile.signInPath.isEmpty()) {
                return ProviderActionResult.failure("「${profile.name}」缺少签到接口配置")
            }

            val headers = mapOf(
                "Content-Type" to "application/json",
                "X-Requested-With" to "XMLHttpRequest",
            )
            val (status, text) = request(profile.signInPath, "POST", credential, "{}", headers)
            val body = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            val message = body?.stringOrNull("message")
                ?: body?.stringOrNull("msg")
                ?: bodySnippet(text).removePrefix("：")
            val success = trueBoolean(body, "success") ||
                exactInteger(body, "ret") == 1L || exactInteger(body, "code") == 0L
            if (status in 200..299 && success) {
                return ProviderActionResult.success(message.ifEmpty { "签到成功" })
            }
            if (body != null && status in 200..299 && profile.alreadyCheckedKeywords.any { message.lowercase().contains(it) }) {
                return ProviderActionResult.success("今日已签到：$message")
            }
            ProviderActionResult.failure(message.ifEmpty { "签到失败（HTTP $status）" })
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "签到失败")
        }
    }

    /**
     * 走一遍纯 HTTP 的 GitHub OAuth 完成签到。
     *
     * 成功时把站点下发的新会话（可能轮换）与当日签到结果一起回写账号池，
     * 这样下一次批量签到不必再要用户点任何东西。
     *
     * ## 判据：签到前后余额差，不是 `checked_in`
     *
     * `data.checked_in` 对**当天已经签过**的账号同样为 true（站点前端据此提示
     * 「签到成功，新增额度已到账」，但它自己也不知道这次有没有加钱）。
     * 拿它当本次成功的证据，就会重演「每天点一次都报成功、额度一分没涨」。
     *
     * 唯一用户真正关心的证据是**额度数字动了没有**：
     * 签到前记一次 `quota`，OAuth 后再记一次，`after > before` 才算本次新增。
     * 这与 checkin-agentrouter（jiang7f）的 `calculate_check_in_reward` 同一口径。
     */
    private fun oauthCheckin(account: ProviderAccount): ProviderActionResult {
        val credential = parse(account) ?: return ProviderActionResult.failure("凭证无法解析")
        val githubCookies = githubCookiesOf(account)
        if (githubCookies.isEmpty()) {
            return ProviderActionResult.requiresUi(
                "「${profile.name}」还没有保存 GitHub 登录态：需要先授权一次，之后每天可自动完成",
            )
        }
        val quotaBefore = runCatching { quotaValue(fetchSelf(credential, account.uid), "quota", true) }.getOrNull()
        return when (val outcome = oauthRunner.run(credential.cookies, githubCookies)) {
            is CheckinOauthClient.Outcome.Completed -> {
                if (outcome.cookies.isNotEmpty()) {
                    persistSiteCookies(account, credential, outcome.cookies)
                }
                val freshCredential = parse(account)?.copy(cookies = mergedCookies(credential, outcome.cookies))
                    ?: credential
                val quotaAfter = runCatching {
                    quotaValue(fetchSelf(freshCredential, account.uid), "quota", true)
                }.getOrNull()
                val verdict = CheckinQuotaDelta.verdict(quotaBefore, quotaAfter, outcome.checkedIn)
                when (verdict.kind) {
                    // 余额真的涨了：这是最硬的成功证据。
                    CheckinQuotaDelta.Kind.INCREASED -> ProviderActionResult.success(
                        "签到成功，本次新增 ${dollars(verdict.delta)}，当前余额 ${dollars(verdict.after ?: 0)}",
                    )
                    // 余额没变但站点说今天已签过：今天的目标已达成，不是失败。
                    // 若报失败，自动签到会每小时重走一遍 OAuth 刷满失败记录。
                    CheckinQuotaDelta.Kind.ALREADY_DONE -> ProviderActionResult.success(
                        "今天已经签过了，本次没有新增额度（当前余额 ${dollars(verdict.after ?: 0)}）",
                    )
                    // 余额没变、站点也没说签过：登录成功但额度没发，真失败。
                    CheckinQuotaDelta.Kind.NOT_CREDITED -> ProviderActionResult.failure(
                        "登录完成，但额度没有变化（${dollars(verdict.after ?: 0)}），" +
                            "站点也没有标记今日已签到 —— 签到可能没有生效",
                    )
                    CheckinQuotaDelta.Kind.AFTER_ONLY -> ProviderActionResult.success(
                        "已重新登录「${profile.name}」，当前余额 ${dollars(verdict.after ?: 0)}",
                    )
                    CheckinQuotaDelta.Kind.UNKNOWN ->
                        ProviderActionResult.success("已重新登录「${profile.name}」，余额查询失败，请稍后刷新确认")
                }
            }
            CheckinOauthClient.Outcome.NeedsRelogin -> ProviderActionResult.requiresUi(
                "「${profile.name}」的 GitHub 登录态已失效：需要重新授权一次",
            )
            CheckinOauthClient.Outcome.NeedsAuthorize -> ProviderActionResult.requiresUi(
                "GitHub 要求重新确认授权：需要在授权页上批准一次",
            )
            is CheckinOauthClient.Outcome.Failed -> ProviderActionResult.failure(outcome.reason)
        }
    }

    /** 把站点新下发的 cookie 合进账号凭证（保留 githubCookies 等其它字段）。 */
    private fun persistSiteCookies(
        account: ProviderAccount,
        credential: Credential,
        fresh: Map<String, String>,
    ) {
        val merged = mergedCookies(credential, fresh)
        val secret = runCatching {
            JsonParser.parseString(account.secret).asJsonObject.apply {
                addProperty("cookies", CheckinCookie.format(merged))
            }.toString()
        }.getOrNull() ?: return
        hooks.onAccountUpdated(account.copy(secret = secret))
    }

    private fun mergedCookies(
        credential: Credential,
        fresh: Map<String, String>,
    ): Map<String, String> =
        if (fresh.isEmpty()) credential.cookies else CheckinCookie.merge(credential.cookies, fresh)

    // ------------------------------------------------------------------ 签到方式（供 UI 分派）

    /** 该站点怎么签到。UI 据此决定显示「立即签到」还是「重新授权签到」。 */
    fun checkinMode(): CheckinMode = profile.checkinMode

    /** 这个账号保存的 github.com 会话（没有则空串 = 需要用户先登录一次）。 */
    fun githubCookiesOf(account: ProviderAccount): String {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull()
            ?: return ""
        return obj.stringOrNull("githubCookies").orEmpty()
    }

    /** 站点配置（UI 需要 domain / 展示名来拉起授权页）。 */
    fun siteProfile(): NewApiSiteProfile = profile

    /**
     * 用站点日志核验本次签到是否真的落库。
     *
     * 只有它能回答「额度到底加了没有」：登录响应里的 checked_in 对当天已签过的账号
     * 同样是 true，不能作为本次成功的证据。
     */
    fun verifyCheckin(account: ProviderAccount, runStartMs: Long): CheckinLogClient.Result {
        val credential = parse(account)
            ?: return CheckinLogClient.Result(CheckinLogClient.Outcome.ERROR, "凭证无法解析")
        return CheckinLogClient(profile).verify(credential.cookies, account.uid, runStartMs)
    }

    /**
     * 界面走完 WebView 授权后，用「签到前后余额差 + 站点 checked_in」核验结果。
     *
     * WebView 那条路拿不到兑换接口的响应体，所以 `checked_in` 只能从
     * `/api/user/self` 的 `data.checked_in` 读（New-API 会把这个位一起返回）。
     * 判据与纯 HTTP 路径完全一致，见 [CheckinQuotaDelta]。
     *
     * @param quotaBefore 授权开始前读到的余额（quota 原值）；null = 没读到基线
     */
    fun verifyByQuota(account: ProviderAccount, quotaBefore: Long?): CheckinQuotaDelta.Verdict {
        val credential = parse(account)
            ?: return CheckinQuotaDelta.verdict(quotaBefore, null)
        val data = runCatching { fetchSelf(credential, account.uid) }.getOrNull()
        val after = data?.let { runCatching { quotaValue(it, "quota", required = true) }.getOrNull() }
        val claimed = data?.let { trueBoolean(it, "checked_in") }
        return CheckinQuotaDelta.verdict(quotaBefore, after, claimed)
    }

    // ------------------------------------------------------------------ 凭证

    /**
     * 粘贴导入：接受裸 cookie 串（`session=…; acw_tc=…`）或
     * `{"cookies":"…","apiUserId":"…"}`。
     *
     * 导入时**必须**打一次 `/api/user/self` 校验：这既是「cookie 还能不能用」的判据，
     * 也顺便拿到 uid 与昵称 —— 否则用户会以为添加成功，第二天签到才炸。
     */
    override fun importCredentials(raw: String): ProviderAccount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw IllegalStateException("Cookie 不能为空")
        val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
        val cookies = CheckinCookie.parse(obj?.stringOrNull("cookies") ?: trimmed)
        if (!cookies.containsKey(SESSION_COOKIE)) {
            throw IllegalStateException("缺少 $SESSION_COOKIE cookie：请登录后复制整串 Cookie")
        }
        val credential = Credential(cookies, obj?.stringOrNull("apiUserId").orEmpty())
        val data = fetchSelf(credential, credential.apiUserId.takeIf { it.isNotEmpty() })
        val uid = positiveId(data, "id")
            ?: throw IllegalStateException("响应缺少合法用户 id，无法建立账号")
        val nickname = data.stringOrNull("display_name")
            ?: data.stringOrNull("username")
            ?: uid
        val secret = JsonObject().apply {
            addProperty("cookies", CheckinCookie.format(cookies))
            addProperty("apiUserId", uid)
            // GitHub 会话一并保存：签到时要把这个账号自己的 GitHub 登录态注回 WebView，
            // 否则每次签到都要重新输一次 GitHub 密码 + 重新批准授权。
            // 多账号绝不能共用——A 的会话拿去签 B 会签成 A。
            obj?.stringOrNull("githubCookies")?.takeIf { it.isNotEmpty() }?.let {
                addProperty("githubCookies", it)
            }
        }.toString()
        hooks.onLog("「${profile.name}」导入账号：$nickname（uid=$uid）")
        return ProviderAccount(
            providerId = id,
            uid = uid,
            nickname = nickname,
            secret = secret,
        )
    }

    override fun supportsAction(account: ProviderAccount, action: String): Boolean = action == ACTION_CHECKIN

    // ------------------------------------------------------------------ 内部

    private data class Credential(val cookies: Map<String, String>, val apiUserId: String)

    private fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        val cookies = CheckinCookie.parse(obj.stringOrNull("cookies").orEmpty())
        if (cookies.isEmpty()) return null
        return Credential(cookies, obj.stringOrNull("apiUserId").orEmpty())
    }

    private fun request(
        path: String,
        method: String,
        credential: Credential,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        headers["Cookie"] = CheckinCookie.format(credential.cookies)
        if (credential.apiUserId.isNotEmpty()) headers[profile.apiUserHeader] = credential.apiUserId
        headers.putAll(extraHeaders)
        val conn = HttpSupport.open(
            url = "${profile.domain.trimEnd('/')}$path",
            method = method,
            headers = headers,
            body = body,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            allowCompression = true,
        )
        return try {
            val status = conn.responseCode
            status to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** 三个入口共享同一个准入点：必须是明确成功的 JSON 和匹配的真实用户身份。 */
    private fun fetchSelf(credential: Credential, expectedUid: String?): JsonObject {
        if (expectedUid != null && positiveIdText(expectedUid) == null) {
            throw IllegalStateException("保存的用户 id 无效，请重新登录")
        }
        if (credential.apiUserId.isNotEmpty() && expectedUid != null && credential.apiUserId != expectedUid) {
            throw IllegalStateException("凭据用户 id 与账号不一致，请重新登录")
        }
        val (status, text) = request(profile.userSelfPath, "GET", credential, null)
        val body = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        if (status !in 200..299 || !trueBoolean(body, "success")) {
            val message = body?.stringOrNull("message").orEmpty()
            throw IllegalStateException(message.ifEmpty { "用户信息校验失败（HTTP $status）" })
        }
        val data = body?.objOrNull("data") ?: throw IllegalStateException("响应缺少用户信息")
        val uid = positiveId(data, "id") ?: throw IllegalStateException("响应缺少合法用户 id")
        if (expectedUid != null && uid != expectedUid) {
            throw IllegalStateException("响应用户与当前账号不一致，请重新登录")
        }
        quotaValue(data, "quota", required = true)
        return data
    }

    private fun trueBoolean(body: JsonObject?, name: String): Boolean =
        body?.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

    private fun positiveId(data: JsonObject, name: String): String? =
        data.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.let(::positiveIdText)

    private fun positiveIdText(text: String): String? =
        text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.toLongOrNull()?.takeIf { it > 0 }?.toString()

    private fun quotaValue(data: JsonObject, name: String, required: Boolean): Long {
        val value = data.get(name)
        if (value == null && !required) return 0L
        return exactInteger(data, name)?.takeIf { it >= 0 }
            ?: throw IllegalStateException("响应缺少合法 $name")
    }

    private fun exactInteger(body: JsonObject?, name: String): Long? =
        body?.get(name)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }
            ?.asString?.let { runCatching { java.math.BigDecimal(it).longValueExact() }.getOrNull() }

    /** quota 原值与金额显示分开，格式化不经过 Double，不截断余额。 */
    private fun dollars(quota: Long): String =
        CreditUnit.formatUsdQuota(quota, CreditUnit.usdQuota(profile.quotaPerUnit))
            ?: throw IllegalStateException("美元换算比例无效")

    private companion object {
        /** New API 的会话 cookie 名；没有它就没有身份，签到必然失败。 */
        const val SESSION_COOKIE = "session"

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
