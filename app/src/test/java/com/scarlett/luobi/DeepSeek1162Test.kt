package com.scarlett.luobi

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DeepSeek1162Test {
    private val sent = mutableListOf<JSONObject>()
    @After fun r() { Ai.chatTransport = null }
    private val t = Ai.Target("deepseek", "DeepSeek", "https://api.deepseek.com", "sk-test", "deepseek-flash")
    private fun reply(c: String, r: String = "") = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", c).put("reasoning_content", r)))).toString()

    @Test fun offSendsDisabledAndSliderTemperature() {
        val b = Ai.chatBody("deepseek", "deepseek-flash", JSONArray(), true, null, ds = Ai.DsGen(false, 0.7))
        assertEquals("disabled", b.getJSONObject("thinking").getString("type"))
        assertEquals(0.7, b.getDouble("temperature"), 1e-9)
        assertFalse(b.has("reasoning_effort"))
        assertEquals(2.0, Ai.chatBody("deepseek", "m", JSONArray(), true, null, ds = Ai.DsGen(false, 2.0)).getDouble("temperature"), 1e-9)
        assertEquals(0.0, Ai.chatBody("deepseek", "m", JSONArray(), true, null, ds = Ai.DsGen(false, 0.0)).getDouble("temperature"), 1e-9)
        assertEquals(2.0, Ai.chatBody("deepseek", "m", JSONArray(), true, null, ds = Ai.DsGen(false, 3.5)).getDouble("temperature"), 1e-9)
    }
    @Test fun onSendsEnabledHighNoTemperatureBigBudget() {
        val b = Ai.chatBody("deepseek", "deepseek-flash", JSONArray(), true, null, ds = Ai.DsGen(true, 0.7))
        assertEquals("enabled", b.getJSONObject("thinking").getString("type"))
        assertEquals("high", b.getString("reasoning_effort"))
        assertFalse(b.has("temperature"))
        assertTrue(b.getInt("max_tokens") >= 32768)
        assertEquals("json_object", b.getJSONObject("response_format").getString("type"))
    }
    @Test fun factCheckAndTestUnchanged() = runBlocking {
        Ai.chatTransport = { _, b -> sent += JSONObject(b); 200 to reply("好") }
        Ai.test(t); Ai.chatText(t, "s", "u", true)
        sent.forEach { assertEquals("disabled", it.getJSONObject("thinking").getString("type")); assertEquals(1.0, it.getDouble("temperature"), 1e-9); assertFalse(it.has("reasoning_effort")) }
        assertEquals(8, sent[0].getInt("max_tokens"))
    }
    @Test fun thinkingAnswerExcludesReasoning() = runBlocking {
        Ai.chatTransport = { _, b -> sent += JSONObject(b); 200 to reply("{\"title\":\"T\"}", "我先想想……") }
        val out = Ai.chatText(t, "s", "u", true, ds = Ai.DsGen(true, 0.7))
        assertEquals("{\"title\":\"T\"}", out); assertFalse(out.contains("想想"))
        assertEquals("enabled", sent.single().getJSONObject("thinking").getString("type"))
    }
}
