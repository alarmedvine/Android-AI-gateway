package dev.aigw.core.provider.newapi

/**
 * 一个 NewAPI / OneAPI 系站点的签到配置。
 *
 * 这类站点（AgentRouter、AnyRouter 等）的签到协议完全一致，差异全在配置里：
 * 域名、签到接口路径、用户信息路径、用户标识头名、以及「1 美元等于多少 quota」。
 * 因此新增一个站点不需要写代码，只要加一条 [NewApiSiteProfile]。
 *
 * ## 签到方式（[checkinMode]）
 *
 * 这类站点的签到协议**并不统一**，必须由配置显式声明，不能靠猜：
 *
 * - [CheckinMode.API]：站点有独立签到接口（AnyRouter 的 `/api/user/sign_in`），POST 一次即可。
 * - [CheckinMode.OAUTH_RELOGIN]：**没有签到接口**，额度只在「一次新的登录」里发放
 *   （AgentRouter）。实测 `POST /api/user/sign_in`、`/api/user/checkin` 全部 404，
 *   上游 `controller.GetSelf` 只读数据库。要签到只能重新走一遍 GitHub OAuth。
 * - [CheckinMode.NONE]：站点不支持签到。
 *
 * **曾经的错误假设**：早期版本认为 AgentRouter「查询一次 `/api/user/self` 上游就自动签到」，
 * 依据是 anyrouter-check-in 的 `sign_in_path = None` 注释。这条对 AnyRouter 成立、
 * 对 AgentRouter **不成立**——它导致签到永远回执成功而额度一分不涨。
 * 社区五个独立实现（ddgksf2013、Code-kike、青龙、jiang7f、agentrouter-magpie）
 * 都确认 AgentRouter 的额度只在登录/注册/OAuth 回调时发放。
 */
data class NewApiSiteProfile(
    /** 稳定短名，作为 provider id 的后缀（`checkin:<key>`）。 */
    val key: String,
    /** 界面展示名。 */
    val name: String,
    /** 站点根地址，不带尾斜杠。 */
    val domain: String,
    /** 签到接口路径；[checkinMode] 为 [CheckinMode.API] 时才使用。 */
    val signInPath: String = "/api/user/sign_in",
    /** 签到方式。默认按「有独立签到接口」处理，OAuth 站点必须显式覆盖。 */
    val checkinMode: CheckinMode = CheckinMode.API,
    /** 当前用户信息接口（余额、用户 id 都从这里拿）。 */
    val userSelfPath: String = "/api/user/self",
    /**
     * 用户标识请求头的名字。
     *
     * New API 用 `new-api-user`；OneAPI 系同样是 `new-api-user`。
     * 用 session cookie 访问时上游其实从会话里认人，这个头是给「粘贴 api_user」的旧用法兜底。
     */
    val apiUserHeader: String = "new-api-user",
    /** 1 个货币单位（美元）等于多少 quota；New API 默认 500000。 */
    val quotaPerUnit: Long = 500_000,
    /** 已签到时上游可能返回的文案（小写包含匹配）。 */
    val alreadyCheckedKeywords: List<String> = listOf(
        "已经签到",
        "已签到",
        "重复签到",
        "already checked",
        "already signed",
    ),
    /** 是否随 APK 内置（用户自建站点为 false）。 */
    val builtin: Boolean = false,
) {    /** 在账号池里的 provider id。 */
    val providerId: String get() = "$PREFIX$key"

    /** 上游主机名（代理设置按它匹配；解析失败返回空串表示「不参与代理判定」）。 */
    val host: String
        get() = runCatching { java.net.URI(domain.trim()).host.orEmpty() }.getOrDefault("")

    companion object {
        /** 签到站点的 provider id 前缀，与 `custom:` 并列。 */
        const val PREFIX = "checkin:"

        fun isCheckinSite(providerId: String): Boolean = providerId.startsWith(PREFIX)

        /**
         * 内置站点。
         *
         * 只放「确定可用、协议已实测」的站点；用户自建站点存在键值存储里，不在这里。
         */
        val AGENTROUTER = NewApiSiteProfile(
            key = "agentrouter",
            name = "AgentRouter",
            domain = "https://agentrouter.org",
            // 没有签到接口：额度只在一次新的 GitHub OAuth 登录里发放。
            signInPath = "",
            checkinMode = CheckinMode.OAUTH_RELOGIN,
            builtin = true,
        )

        val builtins: List<NewApiSiteProfile> = listOf(AGENTROUTER)
    }
}

/**
 * 一个站点的签到方式。
 *
 * 这是**必须显式声明**的配置，因为「没有签到接口」和「查询用户信息会自动签到」
 * 在 HTTP 层看不出区别——两者都只是几个 2xx/4xx。猜错的代价是签到永远假成功。
 */
enum class CheckinMode {
    /** 有独立签到接口，`POST {domain}{signInPath}`。 */
    API,

    /**
     * 没有签到接口；额度在「一次新的登录」里发放，必须重新走一遍 OAuth。
     *
     * 真正的成功判据不在登录响应里（`checked_in` 对当天已签过的账号同样为 true），
     * 而是站点日志里出现本次窗口内的签到记录，见 [CheckinLogClient]。
     */
    OAUTH_RELOGIN,

    /** 站点不支持签到。 */
    NONE,
}
