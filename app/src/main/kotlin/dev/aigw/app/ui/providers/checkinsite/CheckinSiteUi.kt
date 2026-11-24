package dev.aigw.app.ui.providers.checkinsite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.app.CheckinOauthLoginActivity
import dev.aigw.app.CheckinWebLoginActivity
import dev.aigw.app.ui.ChoiceChip
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.newapi.CheckinMode
import dev.aigw.core.provider.newapi.NewApiSiteProfile

/**
 * 「签到站点」的界面组件。
 *
 * 一个实例对应一个 `checkin:<key>`（内置的是 `checkin:agentrouter`），
 * 与 `CustomUi` 同一套动态注册方式：ProviderUiRegistry 按前缀创建。
 *
 * 交互刻意只有两条路：
 * 1. **应用内登录**（推荐）—— 打开 WebView 走站点自己的 OAuth，登录完成后自动读 cookie；
 * 2. 粘贴 Cookie（兜底）—— 某些环境下 WebView 可能被系统限制，留一条不依赖 WebView 的路。
 */
class CheckinSiteUi(override val id: String) : ProviderUi {

    private val profile: NewApiSiteProfile? =
        NewApiSiteProfile.builtins.firstOrNull { it.providerId == id }

    override fun description(): String =
        "网页账号登录，每天自动签到（${profile?.name ?: id}）"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        val context = LocalContext.current
        var raw by remember { mutableStateOf("") }
        var pasteOpen by remember { mutableStateOf(false) }

        SectionCard {
            SectionLabel("添加账号")
            Text(
                text = "在应用内用站点的 GitHub / LinuxDO 账号登录，成功后自动保存会话，" +
                    "之后每天由网关自动签到。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val target = profile
                    if (target == null) {
                        actions.onNotice("这个站点没有内置登录配置")
                        return@Button
                    }
                    context.startActivity(
                        CheckinWebLoginActivity.intent(
                            context = context,
                            providerId = id,
                            loginUrl = "${target.domain.trimEnd('/')}/login",
                            siteHost = target.host,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开登录页（应用内）") }

            if (!pasteOpen) {
                Text(
                    text = "登录页打不开？点这里改用粘贴 Cookie",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { pasteOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("改用粘贴 Cookie") }
            } else {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    label = { Text("Cookie（session=…）") },
                    supportingText = { Text("浏览器登录后从开发者工具复制整串 Cookie") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { actions.onImport(id, raw.trim()) },
                    enabled = raw.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导入账号") }
            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        val context = LocalContext.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (profile?.checkinMode) {
                // 站点没有签到接口：额度只在一次新的 OAuth 登录里发放，
                // 必须重走一遍 GitHub 授权（见 CheckinOauthLoginActivity）。
                CheckinMode.OAUTH_RELOGIN -> {
                    // 先试纯 HTTP（已保存 GitHub 会话时完全静默完成）；
                    // 只有 GitHub 会话失效/需要确认授权时才打开 WebView。
                    Button(
                        onClick = {
                            val target = profile
                            if (target == null) {
                                actions.onNotice("这个站点没有内置签到配置")
                                return@Button
                            }
                            // 函数类型的调用不能用具名参数（Kotlin 限制）
                            actions.onActionWithUiFallback(id, status.uid, ACTION_CHECKIN, JsonObject()) {
                                context.startActivity(
                                    CheckinOauthLoginActivity.intent(
                                        context = context,
                                        providerId = id,
                                        uid = status.uid,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("签到（需要时重新授权）") }
                    Text(
                        text = "该站点的签到就是重新登录一次 GitHub。已保存登录态时直接完成，" +
                            "否则会打开授权页让你确认一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> ChoiceChip("立即签到", false) {
                    actions.onAction(id, status.uid, ACTION_CHECKIN, JsonObject())
                }
            }
        }
    }
}
