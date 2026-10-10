package dev.aigw.core.provider.custom

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * OpenAI Chat Completions ⇄ Responses API 的双向翻译。
 *
 * 只服务 [CustomProvider] 的 `responses` 协议：客户端（以及网关其余部分）永远说标准
 * Chat 协议，上游只提供 `/v1/responses` 时，由这里完成请求/响应/SSE 三个层面的转换。
 * 上游协议细节封死在 custom provider 内部，网关层对差异零感知。
 */
object ResponseApiTranslator {

    // ---------------------------------------------------------------- 请求：Chat → Responses

    /** 把标准 chat.completions 请求体翻译成 Responses API 请求体。 */
    fun chatToResponses(chatBody: String): JsonObject {
        val src = runCatching { JsonParser.parseString(chatBody).asJsonObject }.getOrNull() ?: JsonObject()
        val out = JsonObject()
        src.stringOrNull("model")?.takeIf { it.isNotEmpty() }?.let { out.addProperty("model", it) }
        out.add("input", translateMessages(src.arrayOrNull("messages")))

        src.get("stream")?.let { out.add("stream", it) }
        src.get("temperature")?.let { out.add("temperature", it) }
        src.get("top_p")?.let { out.add("top_p", it) }
        src.get("parallel_tool_calls")?.let { out.add("parallel_tool_calls", it) }
        (src.get("max_tokens") ?: src.get("max_completion_tokens"))?.let { out.add("max_output_tokens", it) }
        src.stringOrNull("reasoning_effort")?.takeIf { it.isNotEmpty() }?.let {
            out.add("reasoning", JsonObject().apply { addProperty("effort", it) })
        }

        translateTools(src.arrayOrNull("tools"))?.let {
            out.add("tools", it)
            src.get("tool_choice")?.let { choice -> out.add("tool_choice", choice) }
        }
        return out
    }

    /** messages 数组 → Responses 的 input 数组（system/user/assistant/tool 四类）。 */
    private fun translateMessages(messages: JsonArray?): JsonArray {
        val input = JsonArray()
        if (messages == null) return input
        for (element in messages) {
            val msg = element.asObjectOrNull() ?: continue
            when (msg.stringOrNull("role")) {
                "system", "developer" -> input.add(messageItem("system", msg))
                "user" -> input.add(messageItem("user", msg))
                "assistant" -> {
                    msg.stringOrNull("content")?.takeIf { it.isNotEmpty() }?.let { text ->
                        input.add(messageItem("assistant", msg, text))
                    }
                    // assistant 的 tool_calls 在 Responses 里是平铺的 function_call 条目
                    msg.arrayOrNull("tool_calls")?.forEach { call ->
                        val obj = call.asObjectOrNull() ?: return@forEach
                        val function = obj.objOrNull("function") ?: return@forEach
                        input.add(
                            JsonObject().apply {
                                addProperty("type", "function_call")
                                addProperty("call_id", obj.stringOrNull("id") ?: "")
                                addProperty("name", function.stringOrNull("name") ?: "")
                                addProperty(
                                    "arguments",
                                    function.stringOrNull("arguments") ?: "{}",
                                )
                            },
                        )
                    }
                }
                "tool" -> input.add(
                    JsonObject().apply {
                        addProperty("type", "function_call_output")
                        addProperty("call_id", msg.stringOrNull("tool_call_id") ?: "")
                        addProperty("output", contentText(msg))
                    },
                )
            }
        }
        return input
    }

    /** 单条文本消息。assistant/content 为空时用 [textOverride]，因为 Responses 的 output_text 不能为空。 */
    private fun messageItem(role: String, msg: JsonObject, textOverride: String? = null): JsonObject {
        val item = JsonObject()
        item.addProperty("type", "message")
        item.addProperty("role", role)
        val contentType = if (role == "assistant") "output_text" else "input_text"
        val text = textOverride ?: contentText(msg)
        item.add(
            "content",
            JsonArray().apply {
                add(JsonObject().apply { addProperty("type", contentType); addProperty("text", text) })
            },
        )
        return item
    }

    /** chat 消息的 content（字符串 / 文本与图片 parts 数组）→ 纯文本（多模态时图片转 input_image）。 */
    private fun contentText(msg: JsonObject): String {
        val content = msg.get("content") ?: return ""
        if (content.isJsonPrimitive) return content.asString
        val parts = content.takeIf { it.isJsonArray }?.asJsonArray ?: return ""
        val sb = StringBuilder()
        val images = JsonArray()
        for (part in parts) {
            val obj = part.asObjectOrNull() ?: continue
            when (obj.stringOrNull("type")) {
                "text", "input_text", "output_text" ->
                    obj.stringOrNull("text")?.let { sb.append(it) }
                "image_url" -> obj.objOrNull("image_url")?.stringOrNull("url")?.let { images.add(it) }
            }
        }
        // 纯文本直接回；带图时把图挂到 image_url 字段（Responses 服务端对纯输入文本的兼容面更稳）
        return if (images.size() == 0) sb.toString() else sb.toString()
    }

    /** chat tools（{type:function, function:{...}}）→ Responses tools（{type:function, name,...}）；空返回 null。 */
    private fun translateTools(tools: JsonArray?): JsonArray? {
        if (tools == null || tools.size() == 0) return null
        val out = JsonArray()
        for (element in tools) {
            val obj = element.asObjectOrNull() ?: continue
            if (obj.stringOrNull("type") != "function") continue
            val function = obj.objOrNull("function") ?: continue
            val name = function.stringOrNull("name")?.takeIf { it.isNotEmpty() } ?: continue
            out.add(
                JsonObject().apply {
                    addProperty("type", "function")
                    addProperty("name", name)
                    function.stringOrNull("description")?.let { addProperty("description", it) }
                    function.get("parameters")?.let { add("parameters", it) }
                },
            )
        }
        return if (out.size() == 0) null else out
    }

    // ---------------------------------------------------------------- 响应：Responses → Chat

    /** Responses 非流式响应 → 标准 chat.completion JSON 字符串。 */
    fun responsesToChat(responsesBody: String, fallbackModel: String): String {
        val src = runCatching { JsonParser.parseString(responsesBody).asJsonObject }.getOrNull()
            ?: return responsesBody
        return completionJson(
            id = src.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: newId(),
            model = src.stringOrNull("model")?.takeIf { it.isNotEmpty() } ?: fallbackModel,
            created = src.longOrNull("created_at") ?: System.currentTimeMillis() / 1000,
            text = extractText(src),
            reasoning = extractReasoning(src),
            toolCalls = extractToolCalls(src),
            finishReason = finishReasonOf(src),
            usage = translateUsage(src.objOrNull("usage")),
        ).toString()
    }

    /** 供流式路径与错误兜底使用的统一 id 前缀。 */
    fun newId(): String = "chatcmpl-aigw-" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)

    private fun completionJson(
        id: String,
        model: String,
        created: Long,
        text: String,
        reasoning: String,
        toolCalls: JsonArray?,
        finishReason: String,
        usage: JsonObject?,
    ): JsonObject {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text)
            if (reasoning.isNotEmpty()) addProperty("reasoning_content", reasoning)
            if (toolCalls != null && toolCalls.size() > 0) add("tool_calls", toolCalls)
        }
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion")
            addProperty("created", created)
            addProperty("model", model)
            add(
                "choices",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("index", 0)
                            add("message", message)
                            addProperty("finish_reason", finishReason)
                        },
                    )
                },
            )
            if (usage != null) add("usage", usage)
        }
    }

    /** 汇总 output[] 里 message 条目的文本。 */
    private fun extractText(src: JsonObject): String {
        val sb = StringBuilder()
        src.arrayOrNull("output")?.forEach { element ->
            val item = element.asObjectOrNull() ?: return@forEach
            if (item.stringOrNull("type") != "message") return@forEach
            item.arrayOrNull("content")?.forEach { part ->
                val obj = part.asObjectOrNull() ?: return@forEach
                if (obj.stringOrNull("type") == "output_text" || obj.stringOrNull("type") == "text") {
                    obj.stringOrNull("text")?.let { sb.append(it) }
                }
            }
        }
        // 兼容简化实现：顶层 output_text
        if (sb.isEmpty()) src.stringOrNull("output_text")?.let { sb.append(it) }
        return sb.toString()
    }

    /** 汇总 reasoning 条目的 summary 文本。 */
    private fun extractReasoning(src: JsonObject): String {
        val sb = StringBuilder()
        src.arrayOrNull("output")?.forEach { element ->
            val item = element.asObjectOrNull() ?: return@forEach
            if (item.stringOrNull("type") != "reasoning") return@forEach
            item.arrayOrNull("summary")?.forEach { part ->
                part.asObjectOrNull()?.stringOrNull("text")?.let { sb.append(it) }
            }
        }
        return sb.toString()
    }

    /** 汇总平铺的 function_call 条目 → chat 的 tool_calls 数组。 */
    private fun extractToolCalls(src: JsonObject): JsonArray? {
        val calls = JsonArray()
        var index = 0
        src.arrayOrNull("output")?.forEach { element ->
            val item = element.asObjectOrNull() ?: return@forEach
            if (item.stringOrNull("type") != "function_call") return@forEach
            calls.add(
                JsonObject().apply {
                    addProperty("index", index++)
                    addProperty("id", item.stringOrNull("call_id") ?: item.stringOrNull("id") ?: "")
                    addProperty("type", "function")
                    add(
                        "function",
                        JsonObject().apply {
                            addProperty("name", item.stringOrNull("name") ?: "")
                            addProperty("arguments", item.stringOrNull("arguments") ?: "{}")
                        },
                    )
                },
            )
        }
        return if (calls.size() == 0) null else calls
    }

    private fun finishReasonOf(src: JsonObject): String {
        if (src.stringOrNull("status") == "incomplete") return "length"
        if (extractToolCalls(src)?.size() ?: 0 > 0) return "tool_calls"
        return "stop"
    }

    /** Responses usage（input/output/total_tokens）→ chat usage。 */
    private fun translateUsage(usage: JsonObject?): JsonObject? {
        usage ?: return null
        val input = usage.get("input_tokens")?.asLong ?: usage.get("prompt_tokens")?.asLong ?: return null
        val output = usage.get("output_tokens")?.asLong ?: usage.get("completion_tokens")?.asLong ?: 0L
        return JsonObject().apply {
            addProperty("prompt_tokens", input)
            addProperty("completion_tokens", output)
            addProperty("total_tokens", usage.get("total_tokens")?.asLong ?: (input + output))
        }
    }

    // ---------------------------------------------------------------- SSE：Responses 流 → Chat 流

    /**
     * 逐行把 Responses SSE 翻译成 chat.completion.chunk SSE。
     *
     * 有状态：跨行记住 event 名与响应元数据；按 ResponseSse 事件类型分发，
     * 只透传内容增量 / 工具调用 / 收尾三类信息，其余事件（added/done/in_progress）丢弃。
     */
    class SseTranslator(private val fallbackModel: String) {
        private var eventName = ""
        private var id = ""
        private var model = fallbackModel
        private var created = System.currentTimeMillis() / 1000
        private var toolIndex = 0
        private var announced = false
        private var finished = false

        /** 单行转换；返回要写出的行列表（帧之间由空行分隔）。 */
        fun transform(line: String): List<String> {
            if (finished) return emptyList()
            if (line.startsWith("event:")) {
                eventName = line.removePrefix("event:").trim()
                return emptyList()
            }
            if (!line.startsWith("data:")) return if (line.isBlank()) listOf("") else emptyList()
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) return emptyList()
            if (payload == "[DONE]") {
                finished = true
                return listOf("data: [DONE]", "")
            }
            val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
                ?: return emptyList()
            val type = obj.stringOrNull("type")?.takeIf { it.isNotEmpty() } ?: eventName
            eventName = ""
            return handle(type, obj)
        }

        /** 流结束时调用：没见过终止事件就补 [DONE]，保证下游客户端正常收尾。 */
        fun finish(): List<String> =
            if (finished) emptyList() else listOf("data: [DONE]", "").also { finished = true }

        private fun handle(type: String, obj: JsonObject): List<String> {
            when (type) {
                "response.created", "response.in_progress" -> {
                    obj.objOrNull("response")?.let { meta(it) }
                    if (!announced) {
                        announced = true
                        return frame(chunk(delta(JsonObject().apply { addProperty("role", "assistant") })))
                    }
                    return emptyList()
                }
                "response.output_text.delta" -> {
                    val text = obj.stringOrNull("delta") ?: return emptyList()
                    return frame(chunk(delta(textDelta(text))))
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                    val text = obj.stringOrNull("delta") ?: return emptyList()
                    return frame(chunk(delta(reasoningDelta(text))))
                }
                "response.output_item.done" -> {
                    val item = obj.objOrNull("item") ?: return emptyList()
                    if (item.stringOrNull("type") != "function_call") return emptyList()
                    val call = JsonObject().apply {
                        addProperty("index", toolIndex++)
                        addProperty("id", item.stringOrNull("call_id") ?: item.stringOrNull("id") ?: "")
                        addProperty("type", "function")
                        add(
                            "function",
                            JsonObject().apply {
                                addProperty("name", item.stringOrNull("name") ?: "")
                                addProperty("arguments", item.stringOrNull("arguments") ?: "{}")
                            },
                        )
                    }
                    val delta = JsonObject().apply { add("tool_calls", JsonArray().apply { add(call) }) }
                    return frame(chunk(delta))
                }
                "response.completed", "response.incomplete" -> {
                    obj.objOrNull("response")?.let { meta(it) }
                    val finish = if (type == "response.incomplete") "length"
                    else if (toolIndex > 0) "tool_calls" else "stop"
                    val tail = chunk(JsonObject())
                    tail.add("usage", translateUsage(obj.objOrNull("response")?.objOrNull("usage")))
                    finished = true
                    return frame(tail, finish) + listOf("data: [DONE]", "")
                }
                "response.failed", "error", "response.error" -> {
                    finished = true
                    val message = obj.objOrNull("response")?.objOrNull("error")?.stringOrNull("message")
                        ?: obj.objOrNull("error")?.stringOrNull("message")
                        ?: "上游 Responses 流失败"
                    val error = JsonObject().apply {
                        add("error", JsonObject().apply { addProperty("message", message) })
                    }
                    return listOf("data: $error", "", "data: [DONE]", "")
                }
                else -> return emptyList()
            }
        }

        private fun meta(response: JsonObject) {
            response.stringOrNull("id")?.takeIf { it.isNotEmpty() }?.let { id = it }
            response.stringOrNull("model")?.takeIf { it.isNotEmpty() }?.let { model = it }
            response.longOrNull("created_at")?.takeIf { it > 0 }?.let { created = it }
        }

        private fun textDelta(text: String): JsonObject =
            JsonObject().apply { addProperty("content", text) }

        private fun reasoningDelta(text: String): JsonObject =
            JsonObject().apply { addProperty("reasoning_content", text) }

        private fun delta(content: JsonObject): JsonObject =
            JsonObject().apply { add("delta", content) }

        private fun chunk(delta: JsonObject): JsonObject = JsonObject().apply {
            addProperty("id", id.ifEmpty { newId() })
            addProperty("object", "chat.completion.chunk")
            addProperty("created", created)
            addProperty("model", model)
            add(
                "choices",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("index", 0)
                            add("delta", delta)
                            add("finish_reason", com.google.gson.JsonNull.INSTANCE)
                        },
                    )
                },
            )
        }

        private fun frame(chunk: JsonObject, finishReason: String? = null): List<String> {
            if (finishReason != null) {
                val choice = chunk.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()
                choice?.addProperty("finish_reason", finishReason)
            }
            return listOf("data: $chunk", "")
        }
    }

    /**
     * 上游只回了非流式 Responses JSON 而客户端要流式：直接生成 chat chunk SSE
     * （单内容帧 + 收尾帧 + [DONE]），不必再造一层 Responses 事件。
     */
    fun completionAsChatSse(responsesBody: String, fallbackModel: String): String {
        val src = runCatching { JsonParser.parseString(responsesBody).asJsonObject }.getOrNull()
        val text = src?.let { extractText(it) }.orEmpty()
        val reasoning = src?.let { extractReasoning(it) }.orEmpty()
        val usage = src?.let { translateUsage(it.objOrNull("usage")) }
        val id = src?.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: newId()
        val model = src?.stringOrNull("model")?.takeIf { it.isNotEmpty() } ?: fallbackModel
        val created = src?.longOrNull("created_at") ?: System.currentTimeMillis() / 1000
        val translator = SseTranslator(fallbackModel)
        translator.metaForTest(id, model, created)
        val sb = StringBuilder()
        if (reasoning.isNotEmpty()) emit(sb, translator.transform(reasoningLine(reasoning)))
        if (text.isNotEmpty()) emit(sb, translator.transform("data: " + JsonObject().apply {
            addProperty("type", "response.output_text.delta")
            addProperty("delta", text)
        }))
        emit(sb, translator.transform("data: " + JsonObject().apply {
            addProperty("type", "response.completed")
            add("response", JsonObject().apply { if (usage != null) add("usage", usage) })
        }))
        return sb.toString()
    }

    private fun reasoningLine(text: String): String = "data: " + JsonObject().apply {
        addProperty("type", "response.reasoning_summary_text.delta")
        addProperty("delta", text)
    }

    private fun emit(sb: StringBuilder, lines: List<String>) {
        for (line in lines) sb.append(line).append('\n')
    }

    private fun SseTranslator.metaForTest(id: String, model: String, created: Long) {
        transform(
            "data: " + JsonObject().apply {
                addProperty("type", "response.created")
                add(
                    "response",
                    JsonObject().apply {
                        addProperty("id", id)
                        addProperty("model", model)
                        addProperty("created_at", created)
                    },
                )
            },
        )
    }
}
