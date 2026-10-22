package com.scarlett.luobi

import com.scarlett.luobi.FactCheck.SearchProvider.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class FactQuote116Test {
    private val calls = mutableListOf<String>(); private val origT = FactNet.transport; private val origS = FactNet.sleep
    @Before fun s() { FactNet.sleep = { } }
    @After fun r() { FactNet.transport = origT; FactNet.sleep = origS }
    private fun fake(h: (String, String?) -> FactNet.Resp) { FactNet.transport = { m, u, _, b -> calls += "$m ${URLDecoder.decode(u, "UTF-8")} ${b.orEmpty()}"; h(u, b) } }
    private fun mw(vararg titles: Pair<String, String>) = FactNet.Resp(200, JSONObject().put("query", JSONObject().put("search", JSONArray().apply { titles.forEach { put(JSONObject().put("title", it.first).put("snippet", it.second)) } })).toString())
    private fun extract(t: String) = FactNet.Resp(200, JSONObject().put("query", JSONObject().put("pages", JSONObject().put("1", JSONObject().put("extract", t)))).toString())
    private val kimiNoise = FactNet.Resp(200, JSONObject().put("search_results", JSONArray().put(JSONObject().put("title", "历史随笔").put("url", "https://x.cn/a").put("snippet", "今天聊聊历史").put("text", ""))).toString())
    private val body = "英国历史学家柯林伍德在《历史的观念》中说：“一切历史都是思想史。”历史学家要在心中重演过去的思想。"
    private val en = "Robin George Collingwood was an English philosopher.\nIn The Idea of History (1946) he argued that \"all history is the history of thought\", and that the historian must re-enact past thought in his own mind."

    @Test fun extractsSpeakerClaimBookAndShortQueries() {
        val it = FactQuotes.items("", body).single()
        assertEquals("柯林伍德", it.speaker); assertEquals("历史的观念", it.book)
        assertEquals("The Idea of History", it.enBook)
        assertTrue(it.enTerms.contains("all history is the history of thought"))
        val (zh, enQ) = FactQuotes.queries(it)
        (zh + enQ).forEach { q -> assertFalse("整句查询：$q", q.contains("一切历史都是思想史。")); assertTrue(q, q.length <= 60) }
        assertTrue(enQ.any { it.contains("Collingwood") && it.contains("history of thought") })
        assertTrue(enQ.any { it.contains("The Idea of History") })
    }

    @Test fun collingwoodFallsThroughToEnglishWikipediaWithPassage() {
        var kimi = 0
        fake { u, _ ->
            when {
                "moonshot" in u -> { kimi++; if (kimi == 1) FactNet.Resp(429, "{}", mapOf("Retry-After" to "1")) else kimiNoise }
                "zh.wikipedia" in u && "list=search" in u -> mw("历史" to "历史是……")
                "zh.wikipedia" in u -> extract("历史是对过去的研究。")
                "en.wikipedia" in u && "list=search" in u -> mw("R. G. Collingwood" to "English philosopher, The Idea of History")
                "en.wikipedia" in u -> extract(en)
                else -> FactNet.Resp(200, "{}")
            }
        }
        val tr = FactQuotes.resolve(FactQuotes.items("", body), FactQuotes.chain(listOf(KIMI, WIKI), { if (it == KIMI) "k" else "" })).single()
        assertNotNull(tr.found)
        assertEquals(listOf("Kimi 搜索", "中文维基", "英文维基"), tr.tried)
        assertTrue(tr.found!!.url.startsWith("https://en.wikipedia.org/wiki/"))
        assertTrue(tr.found!!.text, tr.found!!.text.contains("all history is the history of thought"))
        assertTrue("429 后有重试", kimi >= 2)
        // 写回：模型给了「无法核实」但已找到原文 → 附原文段落 + 链接
        val iss = FactQuotes.annotate(listOf(FactIssue("1", "一切历史都是思想史", "引语出处", "中", "柯林伍德原话", "", "", verdict = "无法核实")), listOf(tr)).single()
        assertTrue(iss.links.first().url.contains("en.wikipedia.org"))
    }

    @Test fun allSourcesFailMarksUnverifiableAndListsTried() {
        fake { u, _ -> if ("moonshot" in u || "deepseek" in u) FactNet.Resp(429, "{}") else mw() }
        val trails = FactQuotes.resolve(FactQuotes.items("", body), FactQuotes.chain(listOf(DEEPSEEK), { if (it == KIMI || it == DEEPSEEK) "k" else "" }))
        val tr = trails.single(); assertNull(tr.found)
        assertEquals(listOf("DeepSeek 搜索", "Kimi 搜索", "中文维基", "英文维基", "维基语录"), tr.tried)
        val out = FactQuotes.annotate(emptyList(), trails).single()
        assertEquals("无法核实", out.verdict)
        assertTrue(out.note, out.note.contains("已尝试：DeepSeek 搜索、Kimi 搜索、中文维基、英文维基、维基语录"))
    }

    @Test fun gatherIncludesQuoteEvidenceAndNote() {
        fake { u, _ -> when { "en.wikipedia" in u && "list=search" in u -> mw("R. G. Collingwood" to "The Idea of History"); "en.wikipedia" in u -> extract(en); "list=search" in u -> mw(); else -> extract("") } }
        val g = FactCheck.gather(listOf(WIKI), { "" }, "", body)
        assertTrue(g.hits.any { it.url.contains("en.wikipedia.org") && it.text.contains("history of thought") })
        assertTrue(g.note, g.note.contains("引语出处 1/1 找到"))
    }
}
