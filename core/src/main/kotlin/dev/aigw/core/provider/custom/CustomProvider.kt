package dev.aigw.core.provider.custom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.LineTransformStream
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 用户手动导入的 OpenAI 兼容供应商。
 *
 * 无登录流程，凭证就是 API Key；配多个 key 时每个 key 是账号池里的一个「账号」，
 * 由账号池做轮询。模型列表以用户手填为准，也可用 [fetchModels] 从 `/v1/models` 拉取。
 *
 * 支持 `protocol` 两种上游协议：
 * - `openai`（默认）：标准 `/chat/completions`；
 * - `responses`：上游只提供 OpenAI Responses API（`/responses`），
 *   由 [ResponseApiTranslator] 在本 provider 内部完成双向翻译。
 *
 * `headers` 是随每个上游请求发送的额外请求头（部分站点强制校验特定头才放行），
 * 值支持 `{key}` 占位符（替换为当前账号的 API Key），多账号轮询时各自带各自的密钥。
 */
/**
 * 自定义供应商的可观测钩子：把上游请求/响应的关键事实写进「记录」页日志。
 * 简明日志始终写一行；请求/响应原文走 [verbose]（受「详细日志」开关控制，可为 null）。
 */
class ProviderWireLog(
    val brief: (String) -> Unit,
    val verbose: ((String, String) -> Unit)? = null,
)

class CustomProvider(
    private val config: CustomProviderConfig,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 上游调用日志；null 时静默（测试/CLI 场景）。 */
    private val wireLog: ProviderWireLog? = null,
) : Provider {

    override val id: String = config.providerId
    override val displayName: String = config.name
    override val authKind: AuthKind = AuthKind.NONE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView =
        ProviderModelCatalogView(
            models = config.models.map { ProviderModel(id = it, name = it) },
            fromFallback = false,
            error = "",
        )

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return config.models.firstOrNull().orEmpty()
        return model
    }

    override fun hosts(): List<String> = runCatching {
        java.net.URI(config.baseUrl.trim()).host
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?.let { listOf(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val key = parseKey(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreaming(openAiBody)
        val model = requestedModel(openAiBody)
        val responses = config.protocol == CustomProviderConfig.PROTOCOL_RESPONSES

        val upstreamBody = if (responses) {
            ResponseApiTranslator.chatToResponses(openAiBody).toString()
        } else {
            openAiBody
        }
        val url = if (responses) responsesUrl(config.baseUrl) else chatUrl(config.baseUrl)

        // 请求前：URL、协议、流式、模型、生效请求头（值脱敏），一次请求一条简明日志
        wireLog?.brief("自定义上游 → POST $url（协议=${if (responses) "Responses" else "Chat"}，stream=$streaming，model=${model.ifEmpty { "—" }}）")
        wireLog?.verbose?.invoke("上游请求头", headerDigest(key))
        wireLog?.verbose?.invoke("上游请求体", upstreamBody)

        val conn = try {
            openConnection(url, key, upstreamBody, "POST", config.headers)
        } catch (e: Exception) {
            wireLog?.brief("自定义上游 ← 连接失败：POST $url：${e.message ?: e.javaClass.simpleName}")
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            wireLog?.brief("自定义上游 ← 连接失败（等待响应阶段）：${e.message ?: e.javaClass.simpleName}")
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        if (status !in 200..299) {
            val body = runCatching { readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            wireLog?.brief("自定义上游 ← HTTP $status：${extractBrief(body)}")
            wireLog?.verbose?.invoke("上游错误原文", body)
            return FailedChatCall(status, body)
        }

        val contentType = conn.contentType.orEmpty()
        wireLog?.brief("自定义上游 ← HTTP $status（${contentType.ifEmpty { "Content-Type 缺省" }}）")
        val stream = conn.inputStream
        if (contentType.contains("event-stream", ignoreCase = true)) {
            if (responses) {
                // Responses SSE → chat.completion.chunk SSE，逐行实时翻译
                val translator = ResponseApiTranslator.SseTranslator(model)
                val translated = LineTransformStream(
                    stream,
                    transform = { line -> translator.transform(line) },
                    onFinish = { translator.finish() },
                )
                return StreamingChatCall(status, translated) { conn.disconnect() }
            }
            if (streaming) {
                return StreamingChatCall(status, stream) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(stream, model)
            } finally {
                conn.disconnect()
            }
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return emptyUpstreamFailure(status, contentType, aggregated)
            }
            return AggregatedChatCall(status, aggregated)
        }

        val text = runCatching { stream.use { readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        // 200 但空 body（代理吞响应、上游静默拒绝）：当成功会让客户端「输出完成却什么都没有」
        if (text.isBlank()) return emptyUpstreamFailure(status, contentType, text)
        if (responses) {
            // Responses JSON → chat.completion；客户端要流式时包成 chat chunk SSE
            if (!text.trimStart().startsWith("{")) {
                return protocolMismatchFailure(status, contentType, text)
            }
            val completion = ResponseApiTranslator.responsesToChat(text, model)
            if (OpenAiSseAggregator.isEmptyCompletion(completion)) {
                return emptyUpstreamFailure(status, contentType, text)
            }
            if (streaming) {
                return StreamingChatCall(
                    status,
                    ResponseApiTranslator.completionAsChatSse(text, model).byteInputStream(Charsets.UTF_8),
                )
            }
            return AggregatedChatCall(status, completion)
        }
        // 非流式响应必须是 OpenAI completion；HTML 登录页之类不能当回复透传
        if (!isChatCompletion(text)) {
            return protocolMismatchFailure(status, contentType, text)
        }
        // 客户端要流式但上游只回了普通 JSON：包成 SSE，严格客户端才能解析出内容
        if (streaming) {
            return StreamingChatCall(status, OpenAiSseAggregator.completionAsSse(text).byteInputStream(Charsets.UTF_8))
        }
        return AggregatedChatCall(status, text)
    }

    /** 上游响应不符合预期协议时的可检测失败。 */
    private fun protocolMismatchFailure(status: Int, contentType: String, raw: String): ChatCall = StreamFailureChatCall(
        status,
        UpstreamError(
            ErrorKind.CLIENT,
            "上游响应不符合 OpenAI 协议（HTTP $status，Content-Type: ${contentType.ifEmpty { "—" }}）：${raw.trim().take(200)}",
        ),
    )

    /** 上游 200 但没给出任何内容：保持可检测的失败，不能报成「成功的空回复」。 */
    private fun emptyUpstreamFailure(status: Int, contentType: String, raw: String): ChatCall = StreamFailureChatCall(
        status,
        UpstreamError(
            ErrorKind.CLIENT,
            "上游返回了空响应（HTTP $status，Content-Type: ${contentType.ifEmpty { "—" }}" +
                if (raw.isBlank()) "）" else "，片段：${raw.trim().take(120)}）",
        ),
    )

    /** 是否是合法的 OpenAI completion（非流式响应）。 */
    private fun isChatCompletion(body: String): Boolean {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return false
        return obj.get("choices")?.isJsonArray == true
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body).ifEmpty { "上游 HTTP $status" }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            status == 404 -> ErrorKind.NOT_FOUND
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            status == 400 || status == 422 -> ErrorKind.CLIENT
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message)
    }

    /** 自定义供应商没有额度接口；返回 null 表示「不支持」。 */
    override fun creditInfo(account: ProviderAccount): CreditInfo? = null

    /** 已保存的模型列表（UI 读取展示用）。 */
    fun savedModels(): List<String> = config.models

    /** 接口地址（UI 读取展示用）。 */
    val baseUrl: String get() = config.baseUrl

    /** 支持粘贴裸 API Key（或 `{"apiKey":"…"}` 形式的 JSON）。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val apiKey = if (trimmed.startsWith("{")) {
            runCatching { JsonParser.parseString(trimmed).asJsonObject.get("apiKey")?.asString }
                .getOrNull().orEmpty()
        } else {
            trimmed
        }
        if (apiKey.isEmpty()) return null
        return ProviderAccount(id, uidOf(apiKey), config.name, secretOf(apiKey))
    }

    // ------------------------------------------------------------------ 内部

    /** 头部摘要（日志用）：名称 + 值，密钥类值只留前后 4 位。 */
    private fun headerDigest(key: String): String {
        val sb = StringBuilder()
        sb.append("Authorization: Bearer ").append(mask(key)).append('\n')
        for ((name, value) in config.headers) {
            val raw = expandValue(value, key)
            sb.append(name.trim()).append(": ").append(mask(raw)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun parseKey(account: ProviderAccount): String? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull()
        return obj?.get("apiKey")?.asString?.takeIf { it.isNotEmpty() }
    }

    private fun requestedModel(body: String): String =
        runCatching { JsonParser.parseString(body).asJsonObject.get("model")?.asString.orEmpty() }
            .getOrDefault("")

    private fun isStreaming(body: String): Boolean =
        runCatching { JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean ?: false }
            .getOrDefault(false)

    private fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.objOrNull("error")?.let { error ->
            error.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (key in listOf("message", "msg", "detail")) {
            obj.get(key)?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    companion object {
        const val KEY_PREFIX = "key-"

        /**
         * 用给定的 baseUrl 与 key 拉模型列表。
         *
         * 不依赖已保存的配置：新建供应商时用户还没保存，也要能先拉一次看看有哪些模型。
         */
        fun fetchModels(baseUrl: String, apiKey: String): List<String> = fetchModelsResult(baseUrl, apiKey, emptyMap()).first

        /**
         * 带自定义请求头拉模型列表，并返回（模型, 错误说明）。
         *
         * 错误说明带 HTTP 状态与响应片段——站点对请求头/密钥的拒绝原因通常写在响应体里，
         * 直接透出给用户，不能吞成「没拉到模型」。
         */
        fun fetchModelsResult(baseUrl: String, apiKey: String, headers: Map<String, String>, wireLog: ProviderWireLog? = null): Pair<List<String>, String> {
            if (baseUrl.isBlank()) return emptyList<String>() to "接口地址为空"
            val url = modelsUrl(baseUrl)
            wireLog?.brief("拉模型 → GET $url（自定义头 ${headers.size} 条）")
            val conn = try {
                openConnection(url, apiKey, null, "GET", headers)
            } catch (e: Exception) {
                wireLog?.brief("拉模型 ← 连接失败：${e.message ?: e.javaClass.simpleName}")
                return emptyList<String>() to "连接失败：${e.message ?: e.javaClass.simpleName}"
            }
            return try {
                val status = conn.responseCode
                if (status !in 200..299) {
                    val body = runCatching { readLimited(conn.errorStream) }.getOrDefault("")
                    val hint = if (status == 404) "（模型端点 404：确认接口地址是否要到 /v1）" else ""
                    wireLog?.brief("拉模型 ← HTTP $status：${extractBrief(body)}")
                    wireLog?.verbose?.invoke("拉模型错误原文", body)
                    emptyList<String>() to "HTTP $status$hint${extractBrief(body)}"
                } else {
                    wireLog?.brief("拉模型 ← HTTP $status")
                }
                val text = readLimited(conn.inputStream)
                val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                    ?: return emptyList<String>() to "响应不是 JSON：${text.trim().take(120)}"
                val data = obj.arrayOrNull("data")
                    ?: return emptyList<String>() to "响应里没有 data 字段：${text.trim().take(120)}"
                val models = data.mapNotNull { element ->
                    val item = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                    item.get("id")?.asString?.takeIf { it.isNotEmpty() }
                }
                if (models.isEmpty()) emptyList<String>() to "上游返回了空模型列表" else models to ""
            } catch (e: Exception) {
                emptyList<String>() to "读取失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                conn.disconnect()
            }
        }

        /** 把上游错误响应压成一行简短说明（优先取 JSON 里的 message 字段）。 */
        private fun extractBrief(body: String): String {
            if (body.isBlank()) return ""
            val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            if (obj != null) {
                obj.objOrNull("error")?.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return "：$it" }
                obj.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return "：$it" }
            }
            return "：${body.trim().replace('\n', ' ').take(160)}"
        }

        private fun openConnection(
            url: String,
            key: String,
            body: String?,
            method: String,
            headers: Map<String, String> = emptyMap(),
        ): HttpURLConnection {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                // SSE 必须逐块到达，显式要求不压缩
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Accept", "text/event-stream, application/json")
                if (key.isNotEmpty()) setRequestProperty("Authorization", "Bearer $key")
                // 站点强校验的额外头。放最后：同名时覆盖默认头，让用户能纠正 Content-Type 等
                for ((name, value) in headers) {
                    if (name.isBlank()) continue
                    setRequestProperty(name.trim(), expandValue(value, key))
                }
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            return conn
        }

        private fun chatUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

        /** Responses 协议的上游地址：`/chat/completions` 结尾替换为 `/responses`，其余追加。 */
        private fun responsesUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return when {
                base.endsWith("/responses") -> base
                base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/responses"
                base.endsWith("/v1") -> "$base/responses"
                else -> "$base/responses"
            }
        }

        /** 展开头值里的 `{key}` 占位符——多账号轮询时每个账号带自己的密钥。 */
        private fun expandValue(value: String, key: String): String =
            if (value.contains("{key}")) value.replace("{key}", key) else value

        private fun mask(value: String): String =
            if (value.length <= 12) "****" else value.take(4) + "****" + value.takeLast(4)

        private fun modelsUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return when {
                base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/models"
                base.endsWith("/models") -> base
                else -> "$base/models"
            }
        }

        private fun readLimited(input: InputStream?): String {
            if (input == null) return ""
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_BODY_BYTES) {
                    out.write(buffer, 0, MAX_BODY_BYTES - out.size())
                    break
                }
                out.write(buffer, 0, read)
            }
            return out.toString("UTF-8")
        }

        /** 自定义供应商的账号 uid 由 key 派生（只用于区分账号，不泄露完整 key）。 */
        fun uidOf(apiKey: String): String =
            KEY_PREFIX + apiKey.hashCode().toUInt().toString(16).padStart(8, '0')

        fun secretOf(apiKey: String): String =
            JsonObject().apply { addProperty("apiKey", apiKey) }.toString()

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
        private const val MAX_BODY_BYTES = 1 shl 20
    }
}
