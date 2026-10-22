package com.scarlett.luobi

import com.scarlett.luobi.FactCheck.SearchProvider.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Yuanbao116Test {
    private val calls = mutableListOf<Triple<String, Map<String, String>, String>>()
    private val origT = FactNet.transport; private val origS = FactNet.sleep
    @Before fun s() { FactNet.sleep = { }; Yuanbao.resetSession() }
    @After fun r() { FactNet.transport = origT; FactNet.sleep = origS; Yuanbao.resetSession() }
    private fun fake(h: (String, String) -> FactNet.Resp) { FactNet.transport = { _, u, hd, b -> calls += Triple(u, hd, b.orEmpty()); h(u, b.orEmpty()) } }
    private fun page(t: String, u: String, passage: String, score: Double, auth: Int = 0) = JSONObject().put("title", t).put("url", u).put("passage", passage).put("content", "$passage（动态摘要）").put("site", "站").put("score", score).put("authority_level", auth).toString()
    private fun result(vararg pages: String, id: Int = 1) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text")
        .put("text", JSONObject().put("Response", JSONObject().put("Pages", JSONArray(pages.toList())).put("Version", "standard")).toString())))).toString()

    @Test fun jsonResponseBearerHeaderAndSortedByScoreAuthority() {
        fake { _, _ -> FactNet.Resp(200, result(page("低", "https://a", "a", 0.3), page("高", "https://baike.baidu.com/x", "b", 0.8, 4))) }
        val r = Yuanbao.search("SECRET", "甜蜜蜜 结局", site = "baike.baidu.com")
        assertEquals("高", r.first().title)
        val (u, h, b) = calls.single()
        assertEquals("https://api.wsa.cloud.tencent.com/Mcp", u)
        assertEquals("Bearer SECRET", h["Authorization"])
        val j = JSONObject(b); assertEquals("tools/call", j.getString("method")); assertEquals("SearchPro", j.getJSONObject("params").getString("name"))
        assertEquals("baike.baidu.com", j.getJSONObject("params").getJSONObject("arguments").getString("Site"))
    }

    @Test fun sseResponseIsParsed() {
        fake { _, _ -> FactNet.Resp(200, "event: message\ndata: " + result(page("T", "https://u", "p", .9)) + "\n\n", mapOf("Content-Type" to "text/event-stream")) }
        assertEquals("T", Yuanbao.search("k", "q").single().title)
    }

    @Test fun initializeWhenServerRequiresSession() {
        var n = 0
        fake { _, b ->
            val m = JSONObject(b).getString("method")
            when {
                m == "tools/call" && n++ == 0 -> FactNet.Resp(400, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("error", JSONObject().put("code", -32600).put("message", "Bad Request: No valid session ID provided, initialize first")).toString())
                m == "initialize" -> FactNet.Resp(200, JSONObject().put("jsonrpc", "2.0").put("id", 2).put("result", JSONObject().put("protocolVersion", "2025-06-18")).toString(), mapOf("Mcp-Session-Id" to "S1"))
                m == "notifications/initialized" -> FactNet.Resp(202, "")
                else -> FactNet.Resp(200, result(page("T", "https://u", "p", .9)))
            }
        }
        assertEquals(1, Yuanbao.search("k", "q").size)
        assertEquals(listOf("tools/call", "initialize", "notifications/initialized", "tools/call"), calls.map { JSONObject(it.third).getString("method") })
        assertEquals("S1", calls.last().second["Mcp-Session-Id"])
    }

    @Test fun errorCodesMapToPlainChineseAndKeyNeverLeaks() {
        val cases = mapOf("UnauthorizedOperation" to "Key 不对", "ResourceNotFound" to "未开通", "ResourceUnavailable" to "欠费", "RequestLimitExceeded" to "太频繁")
        for ((code, want) in cases) {
            fake { _, _ -> FactNet.Resp(200, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "[$code] something")))).toString()) }
            try { Yuanbao.search("SECRET-KEY", "q"); fail() } catch (e: Yuanbao.Err) { assertTrue(e.message, e.message!!.contains(want)); assertFalse(e.message!!.contains("SECRET")) }
        }
    }

    @Test fun yuanbaoFirstThenFallsBackWhenEmptyAndBudgetCapped() {
        fake { u, _ -> when {
            "wsa" in u -> FactNet.Resp(200, result())
            "deepseek" in u -> FactNet.Resp(200, JSONObject().put("results", JSONArray().put(JSONObject().put("title", "D").put("url", "https://d").put("summary", "王家卫"))).toString())
            else -> FactNet.Resp(200, "{}")
        } }
        val body = "《甜蜜蜜》由王家卫执导。片中黎小军最后和李翘在纽约重逢。英国历史学家柯林伍德在《历史的观念》中说：“一切历史都是思想史。”"
        val g = FactCheck.gather(listOf(DEEPSEEK, YUANBAO), { if (it == YUANBAO || it == DEEPSEEK) "k" else "" }, "甜蜜蜜", body)
        val urls = calls.map { it.first }
        assertTrue("元宝排第一", urls.first().contains("wsa"))
        assertTrue(urls.count { "wsa" in it } <= Yuanbao.BUDGET_PER_ARTICLE)
        assertTrue(g.hits.any { it.url == "https://d" })
        // 情节定向站点查询
        assertTrue(calls.any { "wsa" in it.first && it.third.contains("baike.baidu.com") })
        // 学术引语用 acad
        assertTrue(calls.any { "wsa" in it.first && it.third.contains("\"Industry\":\"acad\"") })
    }

    @Test fun queryClippedTo1500Bytes() { assertTrue(Yuanbao.clip("历".repeat(900)).toByteArray().size <= 1500) }
}
