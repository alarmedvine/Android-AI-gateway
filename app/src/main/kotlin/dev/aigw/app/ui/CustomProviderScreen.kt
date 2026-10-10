package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.core.gateway.CustomProviderConfig

/**
 * 自定义供应商的新建/编辑。
 *
 * 这里只管基本信息（地址、协议、请求头）；API Key（账号）与模型列表在供应商详情页维护，
 * 新建时可选一次性批量粘贴多个 Key，保存时并入配置并同步进账号池。
 */
@Composable
fun CustomProviderScreen(
    existing: CustomProviderConfig?,
    onSave: (CustomProviderConfig, List<String>) -> Unit,
    onDelete: ((String) -> Unit)?,
    onBack: () -> Unit,
) {
    var key by remember { mutableStateOf(existing?.key.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var protocol by rememberSaveable {
        mutableStateOf(existing?.protocol ?: CustomProviderConfig.PROTOCOL_OPENAI)
    }
    var headersText by remember { mutableStateOf(headersToText(existing?.headers.orEmpty())) }
    var keysText by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

    PageScaffold(
        title = if (existing == null) "新建供应商" else "编辑供应商",
        subtitle = "OpenAI 兼容接口",
        leading = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clickable(onClick = onBack).padding(6.dp),
            )
        },
    ) {
        SectionCard {
            SectionLabel("基本信息")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("短名（用于模型前缀 custom:<短名>）") },
                singleLine = true,
                enabled = existing == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it.trim() },
                label = { Text("接口地址（到 /v1，或直接写到端点）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("上游协议")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceChip(
                    text = "Chat Completions",
                    selected = protocol != CustomProviderConfig.PROTOCOL_RESPONSES,
                    onClick = { protocol = CustomProviderConfig.PROTOCOL_OPENAI },
                )
                ChoiceChip(
                    text = "Responses API",
                    selected = protocol == CustomProviderConfig.PROTOCOL_RESPONSES,
                    onClick = { protocol = CustomProviderConfig.PROTOCOL_RESPONSES },
                )
            }
            Text(
                text = if (protocol == CustomProviderConfig.PROTOCOL_RESPONSES) {
                    "上游只提供 /v1/responses 时选这个；网关对客户端仍是标准 Chat 接口，由网关自动翻译。"
                } else {
                    "标准 /v1/chat/completions，绝大多数站点选这个。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel("自定义请求头（可选）")
            // 逐行实时校验：解析失败的行显式标红，绝不静默丢弃
            val headerErrors = remember(headersText) { headerLineErrors(headersText) }
            OutlinedTextField(
                value = headersText,
                onValueChange = { headersText = it },
                label = { Text("一行一条，格式：名称: 值（冒号分隔）") },
                supportingText = {
                    Column {
                        Text("值支持 {key} 占位符（替换为当前账号的 API Key）；与默认头同名时覆盖默认值")
                        if (headerErrors.isNotEmpty()) {
                            Text(
                                text = "⚠ 无效行：${headerErrors.joinToString("；")}",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("启用", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }

        SectionCard {
            if (existing == null) {
                SectionLabel("批量导入 API Key（可选）")
                OutlinedTextField(
                    value = keysText,
                    onValueChange = { keysText = it },
                    label = { Text("一行一个 Key，保存时一并导入账号池") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "API Key 在供应商详情页「添加账号」里维护，支持一行一个批量粘贴。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlineActionButton("保存") {
                onSave(
                    CustomProviderConfig(
                        key = key.trim(),
                        name = name.trim().ifEmpty { key.trim() },
                        baseUrl = baseUrl.trim(),
                        apiKeys = existing?.apiKeys.orEmpty(),
                        protocol = protocol,
                        models = existing?.models.orEmpty(),
                        headers = parseHeaders(headersText),
                        enabled = enabled,
                    ),
                    keysText.lines().map { it.trim() }.filter { it.isNotEmpty() },
                )
            }
            if (existing != null && onDelete != null) {
                OutlineActionButton("删除该供应商") { onDelete(existing.key) }
            }
        }
    }
}

/** 「名称: 值」逐行解析请求头；无冒号或名称为空的行跳过。 */
private fun parseHeaders(text: String): Map<String, String> =
    headerLineErrors(text)
        .filterIsInstance<HeaderLine>()
        .associate { it.name to it.value }

/** 校验每一行：合法的返回 [HeaderLine]，非法的返回错误说明。 */
private fun headerLineErrors(text: String): List<Any> =
    text.lines().mapIndexed { index, raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> null
            // 常见误用：只粘了 User-Agent 的值（客户端指纹串），没有头名称
            line.contains('/') && !line.contains(':') && line.first().isLetterOrDigit() ->
                "第 ${index + 1} 行像是一个值而不是「名称: 值」——如果你要伪装客户端，应该写 User-Agent: $line"
            !line.contains(':') -> "第 ${index + 1} 行缺少冒号"
            else -> {
                val name = line.take(line.indexOf(':')).trim()
                if (name.isEmpty()) "第 ${index + 1} 行冒号前没有头名称" else HeaderLine(name, line.substring(line.indexOf(':') + 1).trim())
            }
        }
    }.filterNotNull()

private data class HeaderLine(val name: String, val value: String)

/** 请求头回显成「名称: 值」多行文本。 */
private fun headersToText(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
