package com.scarlett.luobi

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Relay1151Test {
    @After fun r() { Ai.chatTransport = null }
    private fun t(url: String, model: String = "gpt-5.5") = Ai.Target("custom", "中转站", Relay.base(url), "sk-relay", model)
    private val ok = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"好","reasoning_content":"思考……"}}]}"""

    @Test fun baseUrlNormalization() {
        assertEquals("https://x.com/v1", Relay.base("https://x.com"))
        assertEquals("https://x.com/v1", Relay.base("https://x.com/"))
        assertEquals("https://x.com/v1", Relay.base("https://x.com/v1"))
        assertEquals("https://x.com/v1", Relay.base("https://x.com/v1/chat/completions"))
        assertEquals("https://x.com/v1", Relay.base(" x.com/v1/chat/completions/ "))
        assertEquals("https://x.com/api/v1", Relay.base("https://x.com/api/v1/models"))
    }
    @Test fun standardBodyAndPathAndContentNotReasoning() = runBlocking {
        val seen = mutableListOf<Pair<String, JSONObject>>()
        Ai.chatTransport = { u, b -> seen += u to JSONObject(b); 200 to ok }
        val (_, msg) = Ai.test(t("https://x.com"))
        assertTrue(msg.contains("gpt-5.5"))
        val (u, b) = seen.single()
        assertEquals("https://x.com/v1/chat/completions", u)
        assertEquals("gpt-5.5", b.getString("model")); assertTrue(b.has("messages"))
        listOf("thinking", "enable_thinking", "reasoning_effort", "temperature", "top_p").forEach { assertFalse(it, b.has(it)) }
        assertEquals("好", Ai.chatText(t("https://x.com/v1/chat/completions"), null, "u", false))
    }
    @Test fun streamingResponseTolerated() = runBlocking {
        Ai.chatTransport = { _, _ -> 200 to "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"想\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n" }
        assertEquals("你好", Ai.chatText(t("https://x.com"), null, "u", false))
    }
    private fun err(code: Int, body: String = "{}"): String {
        Ai.chatTransport = { _, _ -> code to body }
        return try { runBlocking { Ai.chatText(t("https://x.com", "no-such"), null, "u", false) }; "" } catch (e: ConfigError) { e.hint }
    }
    @Test fun chineseErrors() {
        assertTrue(err(401).contains("Key 无效"))
        assertTrue(err(404, "<html>404</html>").contains("接口地址不对"))
        assertTrue(err(400, """{"error":{"message":"The model `no-such` does not exist"}}""").contains("没有「no-such」这个模型"))
        assertTrue(err(503, """{"error":{"message":"no available channel for model no-such"}}""").contains("没有「no-such」这个模型"))
        Ai.chatTransport = { _, _ -> throw java.net.SocketTimeoutException() }
        val m = try { runBlocking { Ai.chatText(t("https://x.com"), null, "u", false) }; "" } catch (e: ConfigError) { e.hint }
        assertTrue(m, m.contains("超时"))
    }
    @Test fun listModels() {
        var url = ""
        Ai.chatTransport = { u, _ -> url = u; 200 to """{"data":[{"id":"gpt-5.5"},{"id":"claude-sonnet-4.5"},{"id":"gpt-5.5"}]}""" }
        assertEquals(listOf("claude-sonnet-4.5", "gpt-5.5"), Ai.listModels("https://x.com/v1/chat/completions", "k"))
        assertEquals("https://x.com/v1/models", url)
    }
}
