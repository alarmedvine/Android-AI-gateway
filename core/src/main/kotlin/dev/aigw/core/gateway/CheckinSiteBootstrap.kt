package dev.aigw.core.gateway

import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderRegistry
import dev.aigw.core.provider.newapi.NewApiCheckinProvider
import dev.aigw.core.provider.newapi.NewApiSiteProfile

/**
 * 签到站点注册入口。
 *
 * 与 [registerBuiltinProviders] 并列，但只负责 `checkin:` 前缀的站点。
 * 拆成独立函数是为了让「签到」这一族与「聊天供应商」互不牵连：
 * 上游新增聊天供应商时不会碰到这里，我们加站点时也不会碰到上游。
 *
 * 内置站点见 [NewApiSiteProfile.builtins]；用户自建站点（未来）从这里读配置一并注册。
 *
 * 这里不接 [ProviderHooks]：签到结果由 `AutoCheckin` 统一落日志与通知，
 * 逐条流内日志对签到没有增量信息，传默认实现即可避免把 core 内部回调暴露到 app 层。
 */
fun registerCheckinSites(registry: ProviderRegistry) {
    for (profile in NewApiSiteProfile.builtins) {
        // 已注册过的（重复调用）直接跳过：注册表按 id 覆盖，但重复构造没有必要。
        if (registry.get(profile.providerId) != null) continue
        registry.register(NewApiCheckinProvider(profile = profile, hooks = ProviderHooks()))
    }
}
