package dev.aigw.core.provider.custom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.StreamingChatCall
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自定义供应商的请求头与 Responses 协议行为。
 *
 * 背景：部分公益站强制校验特定请求头（缺头直接 403），另有站点只提供 OpenAI Responses
 * API。网关的 custom 供应商要能带着自定义头请求、并在 Responses 协议下做双向翻译。
 */
class CustomProviderHeadersTest {

    /** 记录最近一次上游请求的完整信息。 */
    private class Captured(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )

    private fun withUpstream(
        respond: (Captured) -> Pair<String, String>, // contentType to body
        block: (baseUrl: String, last: () -> Captured) -> Unit,
    ) {
        var last: Captured? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val captured = capture(exchange)
            last = captured
            val (contentType, body) = respond(captured)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1") { last ?: error("还没有请求") }
        } finally {
            server.stop(0)
        }
    }

    private fun capture(exchange: HttpExchange): Captured {
        val headers = LinkedHashMap<String, List<String>>()
        for ((name, values) in exchange.requestHeaders) headers[name.lowercase()] = values
        val body = exchange.requestBody.use { stream ->
            val out = ByteArrayOutputStream()
            stream.copyTo(out)
            out.toString("UTF-8")
        }
        return Captured(exchange.requestMethod, exchange.requestURI.path, headers, body)
    }

    private fun provider(
        baseUrl: String,
        headers: Map<String, String> = emptyMap(),
        protocol: String = CustomProviderConfig.PROTOCOL_OPENAI,
    ) = CustomProvider(
        CustomProviderConfig(
            key = "t",
            name = "测试",
            baseUrl = baseUrl,
            apiKeys = listOf("sk-abc"),
            protocol = protocol,
            headers = headers,
            models = listOf("test-model"),
        ),
    )

    private fun account(providerId: String = "custom:t") =
        ProviderAccount(providerId, "uid-1", "测试", CustomProvider.secretOf("sk-abc"))

    // ------------------------------------------------------------ 请求头

    @Test
    fun `聊天请求带上自定义请求头与占位符`() {
        withUpstream(
            respond = {
                "application/json" to JsonObject().apply {
                    add("choices", com.google.gson.JsonArray())
                }.toString()
            },
        ) { baseUrl, last ->
            val provider = provider(
                baseUrl,
                headers = mapOf(
                    "new-api-user" to "{key}",
                    "X-Title" to "AiGW",
                    "Authorization" to "Bearer fixed", // 覆盖默认头
                ),
            )
            val call = provider.openChat(account(), """{"model":"test-model","stream":false,"messages":[]}""")
            assertTrue(call is dev.aigw.core.provider.AggregatedChatCall)

            val captured = last()
            assertEquals("POST", captured.method)
            assertEquals("/v1/chat/completions", captured.path)
            // {key} 展开为裸 Key（站点要求的格式；要 Bearer 前缀就写 "Bearer {key}"）
            assertEquals(listOf("sk-abc"), captured.headers["new-api-user"])
            assertEquals(listOf("AiGW"), captured.headers["x-title"])
            assertEquals(listOf("Bearer fixed"), captured.headers["authorization"])
        }
    }

    @Test
    fun `拉模型请求同样带上自定义请求头`() {
        withUpstream(
            respond = {
                "application/json" to JsonObject().apply {
                    add("data", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { addProperty("id", "m1") })
                    })
                }.toString()
            },
        ) { baseUrl, last ->
            val models = CustomProvider.fetchModels(
                baseUrl,
                "sk-abc",
                mapOf("new-api-user" to "sk-abc"),
            )
            assertEquals(listOf("m1"), models)
            assertEquals(listOf("sk-abc"), last().headers["new-api-user"])
            assertEquals("GET", last().method)
            assertEquals("/v1/models", last().path)
        }
    }

    // ------------------------------------------------------------ Responses 协议

    @Test
    fun `responses 协议请求发到 responses 端点且请求体完成翻译`() {
        withUpstream(respond = { "application/json" to responseJson() }) { baseUrl, last ->
            val provider = provider(baseUrl, protocol = CustomProviderConfig.PROTOCOL_RESPONSES)
            val chatBody = """
                {"model":"gpt-x","stream":true,"max_tokens":1024,
                 "messages":[{"role":"system","content":"你是助手"},
                             {"role":"user","content":"你好"}]}
            """.trimIndent()
            val call = provider.openChat(account(), chatBody)
            // 客户端要流式：上游回 JSON 时包成 chat chunk SSE
            assertTrue(call is StreamingChatCall)
            val text = (call as StreamingChatCall).stream.readBytes().toString(StandardCharsets.UTF_8)
            assertTrue(text.contains("chat.completion.chunk"))
            assertTrue(text.contains("答案"))
            assertTrue(text.contains("[DONE]"))

            val request = JsonParser.parseString(last().body).asJsonObject
            assertEquals("/v1/responses", last().path)
            assertEquals("gpt-x", request.get("model").asString)
            assertEquals(1024, request.get("max_output_tokens").asInt)
            assertTrue(request.has("input"))
            assertTrue(request.get("stream").asBoolean)
        }
    }

    @Test
    fun `responses 协议非流式回包翻译成 chat completion`() {
        withUpstream(respond = { "application/json" to responseJson() }) { baseUrl, last ->
            val provider = provider(baseUrl, protocol = CustomProviderConfig.PROTOCOL_RESPONSES)
            val call = provider.openChat(
                account(),
                """{"model":"gpt-x","stream":false,"messages":[{"role":"user","content":"hi"}]}""",
            )
            val body = (call as dev.aigw.core.provider.AggregatedChatCall).aggregated
            val obj = JsonParser.parseString(body).asJsonObject
            assertEquals("chat.completion", obj.get("object").asString)
            assertEquals("resp-1", obj.get("id").asString)
            assertEquals("gpt-x", obj.get("model").asString)
            val message = obj.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
            assertEquals("答案", message.get("content").asString)
            assertEquals(11L, obj.getAsJsonObject("usage").get("prompt_tokens").asLong)
            assertEquals("/v1/responses", last().path)
        }
    }

    @Test
    fun `responses 协议流式 SSE 翻译成 chat chunk`() {
        val sse = buildString {
            append("event: response.created\n")
            append("""data: {"type":"response.created","response":{"id":"resp-9","model":"gpt-x"}}""" + "\n\n")
            append("""data: {"type":"response.output_text.delta","delta":"你"}""" + "\n\n")
            append("""data: {"type":"response.output_text.delta","delta":"好"}""" + "\n\n")
            append("""data: {"type":"response.completed","response":{"id":"resp-9","usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}}""" + "\n\n")
        }
        withUpstream(respond = { "text/event-stream" to sse }) { baseUrl, _ ->
            val provider = provider(baseUrl, protocol = CustomProviderConfig.PROTOCOL_RESPONSES)
            val call = provider.openChat(
                account(),
                """{"model":"gpt-x","stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertTrue(call is StreamingChatCall)
            val text = (call as StreamingChatCall).stream.readBytes().toString(StandardCharsets.UTF_8)
            assertTrue(text.contains("\"role\":\"assistant\""))
            assertTrue(text.contains("\"content\":\"你\""))
            assertTrue(text.contains("\"content\":\"好\""))
            assertTrue(text.contains("\"finish_reason\":\"stop\""))
            assertTrue(text.contains("chat.completion.chunk"))
            assertTrue(text.trimEnd().endsWith("data: [DONE]"))
        }
    }

    // ------------------------------------------------------------ 配置持久化兼容

    @Test
    fun `配置含请求头时序列化往返一致`() {
        val config = CustomProviderConfig(
            key = "site",
            name = "站点",
            baseUrl = "https://x.example/v1",
            apiKeys = listOf("sk-1"),
            protocol = CustomProviderConfig.PROTOCOL_RESPONSES,
            headers = linkedMapOf("new-api-user" to "{key}", "X-Title" to "AiGW"),
            models = listOf("m"),
        )
        val restored = CustomProviderConfig.fromJson(JsonParser.parseString(config.toJson().toString()).asJsonObject)
        assertEquals(config, restored)
        assertEquals("{key}", restored?.headers?.get("new-api-user"))
    }

    @Test
    fun `旧配置没有请求头字段时缺省为空`() {
        val legacy = """{"key":"site","name":"站点","baseUrl":"https://x.example/v1","apiKeys":["sk-1"],"models":[]}"""
        val restored = CustomProviderConfig.fromJson(JsonParser.parseString(legacy).asJsonObject)
        assertEquals(emptyMap(), restored?.headers)
        assertEquals(CustomProviderConfig.PROTOCOL_OPENAI, restored?.protocol)
    }

    private fun responseJson(): String = JsonObject().apply {
        addProperty("id", "resp-1")
        addProperty("model", "gpt-x")
        addProperty("created_at", 1700000000L)
        addProperty("status", "completed")
        add("output", com.google.gson.JsonArray().apply {
            add(
                JsonObject().apply {
                    addProperty("type", "message")
                    add("content", com.google.gson.JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("type", "output_text")
                                addProperty("text", "答案")
                            },
                        )
                    })
                },
            )
        })
        add(
            "usage",
            JsonObject().apply {
                addProperty("input_tokens", 11L)
                addProperty("output_tokens", 7L)
                addProperty("total_tokens", 18L)
            },
        )
    }.toString()
}
