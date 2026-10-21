package com.scarlett.luobi

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Kimi1151Test {
    private val sent = mutableListOf<JSONObject>()
    @After fun r() { Ai.chatTransport = null }
    private fun t(model: String) = Ai.Target("kimi", "Kimi", "https://api.moonshot.cn/v1", "sk-test", model)
    private fun reply(content: String, reasoning: String, fr: String) =
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", fr)
            .put("message", JSONObject().put("role", "assistant").put("content", content).put("reasoning_content", reasoning)))).toString()

    @Test fun k3TestSendsOnlyAcceptedParamsAndEnoughTokens() = runBlocking {
        Ai.chatTransport = { u, b -> assertEquals("https://api.moonshot.cn/v1/chat/completions", u); sent += JSONObject(b); 200 to reply("好", "用户让我回复好……", "stop") }
        Ai.test(t("kimi-k3"))
        val b = sent.single()
        assertEquals("kimi-k3", b.getString("model"))
        assertTrue(b.getInt("max_completion_tokens") >= 16000); assertFalse(b.has("max_tokens"))
        assertEquals("low", b.getString("reasoning_effort"))
        listOf("temperature", "top_p", "n", "thinking", "presence_penalty", "frequency_penalty").forEach { assertFalse(it, b.has(it)) }
    }
    @Test fun k26DisablesThinkingNoTemperature() = runBlocking {
        Ai.chatTransport = { _, b -> sent += JSONObject(b); 200 to reply("{\"a\":1}", "", "stop") }
        Ai.chatText(t("kimi-k2.6"), "sys", "u", true)
        val b = sent.single()
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"))
        assertFalse(b.has("temperature") || b.has("reasoning_effort"))
        assertEquals(Ai.KIMI_DEFAULT_TOKENS, b.getInt("max_completion_tokens"))
        assertEquals("json_object", b.getJSONObject("response_format").getString("type"))
    }
    @Test fun readsContentNeverReasoning() = runBlocking {
        Ai.chatTransport = { _, _ -> 200 to reply("正式回答", "这是思考过程，不能当回答", "stop") }
        assertEquals("正式回答", Ai.chatText(t("kimi-k3"), null, "u", false))
    }
    @Test fun reasoningOnlyLengthGivesClearError() = runBlocking {
        Ai.chatTransport = { _, _ -> 200 to reply("", "想了很久很久……", "length") }
        try { Ai.chatText(t("kimi-k3"), null, "u", false); fail() } catch (e: AiFormatError) {
            assertTrue(e.what, e.what.contains("思考")); assertFalse(e.raw.contains("想了很久"))
            assertFalse(e.what.contains("想了很久"))
        }
    }
    @Test fun retiredModelsMapToK3() {
        assertEquals("kimi-k3", Ai.kimiModel("kimi-k2.5")); assertEquals("kimi-k3", Ai.kimiModel("moonshot-v1-8k")); assertEquals("kimi-k3", Ai.kimiModel("kimi-latest"))
        assertEquals("kimi-k2.6", Ai.kimiModel("kimi-k2.6"))
        val code = Ai.kimiBody("kimi-k2.7-code", org.json.JSONArray(), false, 8, true)
        assertFalse(code.has("thinking")); assertFalse(code.has("reasoning_effort"))
        assertEquals(listOf("kimi-k3", "kimi-k2.6"), Providers.text("kimi").models.map { it.first })
    }
}
