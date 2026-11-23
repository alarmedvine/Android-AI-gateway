package dev.aigw.core.provider.newapi

/**
 * 一个 NewAPI / OneAPI 系站点的签到配置。
 *
 * 这类站点（AgentRouter、AnyRouter 等）的签到协议完全一致，差异全在配置里：
 * 域名、签到接口路径、用户信息路径、用户标识头名、以及「1 美元等于多少 quota」。
 * 因此新增一个站点不需要写代码，只要加一条 [NewApiSiteProfile]。
 *
 * [signInPath] 为空是合法且有意义的：AgentRouter 这类站点没有独立签到接口，
 * **查询一次 `/api/user/self` 上游就自动完成签到**（对照 anyrouter-check-in 的
 * `utils/config.py:101` —— `sign_in_path = None  # 无需签到接口，查询用户信息时自动完成签到`）。
 */
data class NewApiSiteProfile(
    /** 稳定短名，作为 provider id 的后缀（`checkin:<key>`）。 */
    val key: String,
    /** 界面展示名。 */
    val name: String,
    /** 站点根地址，不带尾斜杠。 */
    val domain: String,
    /** 签到接口路径；空串表示「查询用户信息即自动签到」。 */
    val signInPath: String = "/api/user/sign_in",
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
) {
    /** 在账号池里的 provider id。 */
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
            // 查询用户信息即自动签到（见类注释）。
            signInPath = "",
            builtin = true,
        )

        val builtins: List<NewApiSiteProfile> = listOf(AGENTROUTER)
    }
}
