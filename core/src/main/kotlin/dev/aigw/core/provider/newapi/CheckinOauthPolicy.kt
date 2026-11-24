package dev.aigw.core.provider.newapi

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

/**
 * 「签到 = 重新走一次 GitHub OAuth」的站点策略（AgentRouter 属于这一类）。
 *
 * ## 为什么必须这样
 *
 * AgentRouter 是 New-API 分支，**没有签到接口**：`POST /api/user/sign_in`、`/api/user/checkin`
 * 全部 404（实测 2026-11-24），上游 `controller.GetSelf` 只读数据库、不发放任何额度。
 * 每日额度只在三个地方发放，且都发生在「一次新的登录」里：
 * 密码登录 `POST /api/user/login`、GitHub 回调 `GET /api/oauth/github`、以及 LinuxDO 回调。
 * 响应体里 `data.checked_in` 为 true 时前端才提示「签到成功，新增额度已到账」。
 *
 * 因此**查询一次 `/api/user/self` 永远不可能签到成功**——这正是上一版失败的根因：
 * 它把「查余额成功」当成了「签到成功」，用户看到的回执永远是「已完成站点自动签到查询」，
 * 额度一分没动。开源社区的五个独立实现（ddgksf2013、Code-kike、青龙、jiang7f、
 * 22952497/agentrouter-magpie）都指向同一结论，其中 GitHub 账号没有站点密码的那几个
 * 一律走「重新授权」这条路。
 *
 * ## 关键约束：只能清站点的 cookie，不能清 GitHub 的
 *
 * 重新走 OAuth 要求 GitHub 那边**仍然处于已登录状态**，否则会掉到 GitHub 登录页，
 * 而应用内 WebView 无法完成 GitHub 的登录（二次验证 / 设备校验）。
 * 社区实现里 `AnnaKarelovsky/AgentRouterautoCheckin` 明确写了这条：
 * 「The only automatic cleanup is scoped to the AgentRouter origin, so the GitHub
 * session remains available for the next OAuth authorization.」
 *
 * 反过来说：如果用户从没在本机登录过 GitHub，就只能在应用内点「打开登录页」手动授权一次；
 * 那一次会同时把 GitHub 会话留在 WebView 里，之后每天的自动签到才能纯自动完成。
 */
object CheckinOauthPolicy {

    /**
     * GitHub 授权完成后站点回调页的路径。
     *
     * 站点前端把 `/oauth/github` 注册成 SPA 路由，页面一加载就会把 code 兑换掉；
     * 所以这里必须**等页面自己完成兑换**再读结果，不能自己去重放 code。
     */
    const val GITHUB_CALLBACK_PATH = "/oauth/github"

    /** 站点自己的登录页；从这里点 GitHub 按钮才走它自己的 `window.open` 流程。 */
    const val LOGIN_PATH = "/login"

    /**
     * 构造授权入口 URL。
     *
     * 刻意**不直接拼 `github.com/login/oauth/authorize`**：站点前端的按钮会先
     * `GET /api/oauth/state?mode=login` 拿一个签名 state，再拼 client_id 打开新窗口。
     * 由站点页面自己发起，state 与会话天然配套，也自动继承了它的错误处理。
     * 我们只负责「清掉站点 cookie → 打开 /login → 用户/自动化点 GitHub 按钮」。
     */
    fun loginUrl(profile: NewApiSiteProfile): String = profile.domain.trimEnd('/') + LOGIN_PATH

    /** 回调页 URL 判定：完整同源 + 路径等于回调路径（允许带 query）。 */
    fun isCallbackUrl(url: String?, profile: NewApiSiteProfile): Boolean = runCatching {
        val page = URI(url ?: return false)
        val site = URI(profile.domain)
        page.scheme.equals(site.scheme, ignoreCase = true) &&
            page.host != null && page.host.equals(site.host, ignoreCase = true) &&
            page.port == site.port &&
            page.path.orEmpty().trimEnd('/') == GITHUB_CALLBACK_PATH
    }.getOrDefault(false)

    /** 是否已经离开 GitHub、回到站点域（用来判断授权窗口该不该关）。 */
    fun isBackOnSite(url: String?, profile: NewApiSiteProfile): Boolean =
        CheckinLoginPolicy.isSitePage(url, profile.domain)

    /**
     * 从登录/回调响应体里判定「这一次登录有没有真的发额度」。
     *
     * 只看 `checked_in` 这一个布尔位，理由来自站点自己的前端：
     * `ut && ut.checked_in ? Pe("签到成功，新增额度已到账") : Pe("登录成功！")`。
     * 但**它不能单独作为「本次新签」的证据**——当天已签过的账号再登录同样返回 true
     * （青龙脚本 `2263075977/qinglong` 明确记录了这个陷阱）。因此本函数的返回值只用于
     * 「这次登录是否值得去核日志」，最终成功与否以 [CheckinLogEvidence] 为准。
     */
    fun claimedCheckin(body: String?): Boolean = runCatching {
        val obj = JsonParser.parseString(body ?: return false)
        if (!obj.isJsonObject) return false
        val root = obj.asJsonObject
        // 密码登录把用户对象放在 data；GitHub 回调同样放 data。
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        trueBoolean(data, "checked_in")
    }.getOrDefault(false)

    /** 从登录/回调响应体里取用户 id（`data.id`），没有就是 null。 */
    fun responseUserId(body: String?): String? = runCatching {
        val obj = JsonParser.parseString(body ?: return null)
        if (!obj.isJsonObject) return null
        val data = obj.asJsonObject.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val raw = data.get("id")?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }?.asString
        raw?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.toLongOrNull()?.takeIf { it > 0 }?.toString()
    }.getOrNull()

    /**
     * 取 GitHub 授权所需的 client_id 与 state，拼成授权页 URL。
     *
     * 两个接口都是公开的（实测 `/api/status` 200、`/api/oauth/state` 200），
     * state 是服务端签名的一次性 token、不绑定会话，所以**不需要任何 cookie**。
     *
     * @return 授权页 URL；任一步失败或参数为空时返回 null，由调用方报明确错误
     */
    fun fetchAuthorizeParams(
        profile: NewApiSiteProfile,
        httpGet: (String) -> String? = { url ->
            runCatching {
                val conn = dev.aigw.core.util.HttpSupport.open(
                    url = url,
                    method = "GET",
                    headers = mapOf("Accept" to "application/json"),
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = 20_000,
                )
                try {
                    if (conn.responseCode !in 200..299) null
                    else dev.aigw.core.util.HttpSupport.readBody(conn)
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        },
    ): String? {
        val base = profile.domain.trimEnd('/')
        val statusBody = httpGet("$base/api/status") ?: return null
        val clientId = runCatching {
            JsonParser.parseString(statusBody).asJsonObject
                .getAsJsonObject("data")?.get("github_client_id")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

        val stateBody = httpGet("$base/api/oauth/state?mode=login") ?: return null
        val state = runCatching {
            JsonParser.parseString(stateBody).asJsonObject
                .get("data")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

        return "https://github.com/login/oauth/authorize" +
            "?client_id=" + java.net.URLEncoder.encode(clientId, "UTF-8") +
            "&state=" + java.net.URLEncoder.encode(state, "UTF-8") +
            "&scope=user%3Aemail"
    }

    /** 从 `/api/oauth/state?mode=login` 的响应里取签名 state。 */
    fun parseState(body: String?): String? = runCatching {
        JsonParser.parseString(body ?: return null).asJsonObject
            .get("data")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 从 `/api/status` 的响应里取 GitHub OAuth 的 client_id。 */
    fun parseClientId(body: String?): String? = runCatching {
        JsonParser.parseString(body ?: return null).asJsonObject
            .getAsJsonObject("data")
            ?.get("github_client_id")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 从响应体里取 `success` 严格布尔（不接受 "true"/1）。 */
    fun responseSuccess(body: String?): Boolean = runCatching {
        val obj = JsonParser.parseString(body ?: return false)
        if (!obj.isJsonObject) return false
        trueBoolean(obj.asJsonObject, "success")
    }.getOrDefault(false)

    /** 上游错误文案（`message` / `msg`），用于把失败原因原样回传。 */
    fun responseMessage(body: String?): String = runCatching {
        val obj = JsonParser.parseString(body ?: return "")
        if (!obj.isJsonObject) return ""
        val root = obj.asJsonObject
        for (name in listOf("message", "msg")) {
            val value = root.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            if (value != null) return value.asString
        }
        ""
    }.getOrDefault("")

    private fun trueBoolean(obj: JsonObject, name: String): Boolean =
        obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
}

/**
 * 一次签到的**唯一硬判据**：站点日志里今天出现了一条签到记录。
 *
 * `checked_in` 只说「今天签过」，不说「是不是刚才签的」；`quota` 变动又会被同期的
 * 对话消费掩盖。青龙脚本（`2263075977/qinglong`）踩过这个坑后给出的判据是
 * `GET /api/log/self` 里 type=4（LogTypeSystem）且 created_at 落在本次运行之后。
 * 这里把它抽成纯函数，好在 `:core` 单测里直接验证，不必连真站点。
 */
object CheckinLogEvidence {

    /** New-API 的 `LogTypeSystem`；签到记录用的就是这个 type。 */
    const val LOG_TYPE_SYSTEM = 4

    /** 签到日志正文里通常含这两个词之一；type 判定失败时作兜底。 */
    private val CONTENT_HINTS = listOf("签到成功", "每日签到", "签到")

    /**
     * 日志条目是否是一条签到记录。
     *
     * 优先看 type（结构化、不受文案改动影响），type 缺失或不认时才退回文案匹配。
     */
    fun isCheckinEntry(type: Int?, content: String?): Boolean {
        if (type == LOG_TYPE_SYSTEM) return true
        val text = content.orEmpty()
        return text.isNotEmpty() && CONTENT_HINTS.any { text.contains(it) }
    }

    /**
     * 在日志条目里找「本次运行开始之后」的签到记录。
     *
     * @param items 每条至少含 `created_at`（秒）、可选 `type` 与 `content`
     * @param runStartMs 本次签到开始时刻（毫秒）；留 [SLACK_MS] 容差吸收时钟偏差
     * @return 命中的记录时间（毫秒），没有则 null
     */
    fun freshCheckinAt(items: List<Map<String, Any?>>, runStartMs: Long): Long? {
        val threshold = runStartMs - SLACK_MS
        var newest: Long? = null
        for (item in items) {
            val createdAtSec = (item["created_at"] as? Number)?.toLong() ?: continue
            val createdAtMs = createdAtSec * 1000
            if (createdAtMs < threshold) continue
            val type = (item["type"] as? Number)?.toInt()
            val content = item["content"] as? String
            if (!isCheckinEntry(type, content)) continue
            if (newest == null || createdAtMs > newest) newest = createdAtMs
        }
        return newest
    }

    /** 容差窗口：吸收客户端时钟偏差与请求往返（5 分钟）。 */
    const val SLACK_MS = 5 * 60 * 1000L

    /**
     * 构造日志查询路径。
     *
     * 必须带 `type=4` 过滤：在用账号每天有几百条消费日志（type=2），
     * 今天那条签到记录早就被挤出第一页，只翻第一页会让判定结果和账号使用强度耦合。
     */
    fun logQueryPath(startEpochSec: Long, pageSize: Int = 50): String =
        "/api/log/self?p=1&page_size=$pageSize&type=$LOG_TYPE_SYSTEM&start_timestamp=$startEpochSec"
}
