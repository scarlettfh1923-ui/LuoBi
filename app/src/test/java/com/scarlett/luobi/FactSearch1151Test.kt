package com.scarlett.luobi

import com.scarlett.luobi.FactCheck.SearchProvider.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FactSearch1151Test {
    private val calls = mutableListOf<Pair<String, String?>>(); private val sleeps = mutableListOf<Long>()
    private val origT = FactNet.transport; private val origS = FactNet.sleep
    @Before fun s() { FactNet.sleep = { sleeps += it } }
    @After fun r() { FactNet.transport = origT; FactNet.sleep = origS; Notion.transport = origN }
    private val origN = Notion.transport
    private fun fake(h: (String, String?) -> FactNet.Resp) { FactNet.transport = { m, u, _, b -> calls += "$m $u" to b; h(u, b) } }
    private fun ds(vararg urls: String) = FactNet.Resp(200, JSONObject().put("results", JSONArray().apply { urls.forEach { put(JSONObject().put("title", "T $it").put("url", it).put("summary", "陈可辛执导")) } }).toString())
    private fun kimi(vararg urls: String, text: String = "") = FactNet.Resp(200, JSONObject().put("search_results", JSONArray().apply { urls.forEach { put(JSONObject().put("title", "K $it").put("url", it).put("snippet", "陈可辛执导").put("site_name", "站").put("date", "").put("text", text)) } }).toString())
    private val body = "《甜蜜蜜》由王家卫执导。片中黎小军最后和李翘在纽约重逢。"

    @Test fun retryRespectsRetryAfterThenSucceeds() {
        var n = 0
        fake { _, _ -> n++; if (n == 1) FactNet.Resp(429, "{}", mapOf("Retry-After" to "2")) else ds("https://a") }
        val r = FactSources.deepseek("k", "q")
        assertEquals(1, r.size); assertEquals(2, calls.size)
        assertTrue(sleeps.contains(2000L)) // Retry-After 2s
        assertEquals(FactNet.gapMs, sleeps.first()) // 每次请求前都有间隔
    }
    @Test fun exponentialBackoffAndGiveUp() {
        fake { _, _ -> FactNet.Resp(429, "{}") }
        try { FactSources.deepseek("k", "q"); fail() } catch (e: FactNet.Fail) { assertEquals(429, e.code) }
        assertEquals(FactNet.TRIES, calls.size)
        assertEquals(listOf(1000L, 2000L), sleeps.filter { it != FactNet.gapMs })
    }
    @Test fun allSources429GivesPlainNoteAndModelOnly() {
        fake { _, _ -> FactNet.Resp(429, "{}") }
        val g = FactCheck.gather(listOf(DEEPSEEK, WIKI, BOCHA), { if (it == BOCHA) "" else "k" }, "甜蜜蜜", body)
        assertTrue(g.modelOnly)
        assertTrue(g.note, g.note.contains("DeepSeek 搜索请求太频繁，被暂时限流") && g.note.contains("维基百科同上") && g.note.contains("博查未填写 Key"))
        assertTrue(g.note.contains("只凭模型自身知识核对"))
        assertFalse(g.note.contains("HTTP"))
        // 每个源失败后不再继续打剩余查询：DeepSeek 3 次 + 维基 3 次 + 情节全文 ≤ 3 次
        assertTrue("calls=${calls.size}", calls.size <= 3 * 3)
    }
    @Test fun kimiRequestShape() {
        fake { _, _ -> kimi("https://k") }
        FactSources.kimiSearch("mk", "甜蜜蜜 结局")
        val (k, b) = calls.single()
        assertEquals("POST https://api.moonshot.cn/v1/tools/search", k)
        val j = JSONObject(b!!)
        assertEquals("甜蜜蜜 结局", j.getString("text_query")); assertEquals(5, j.getInt("limit")); assertEquals(10, j.getInt("timeout_seconds"))
        assertFalse(j.has("include_content"))
        FactSources.kimiSearch("mk", "x", includeContent = true); assertTrue(JSONObject(calls.last().second!!).getBoolean("include_content"))
        fake { _, _ -> FactNet.Resp(200, """{"url":"u","title":"t","markdown":"# 剧情"}""") }
        assertEquals("# 剧情", FactSources.kimiFetch("mk", "https://baike.baidu.com/item/x"))
        assertEquals("POST https://api.moonshot.cn/v1/tools/fetch", calls.last().first)
        assertEquals("https://baike.baidu.com/item/x", JSONObject(calls.last().second!!).getString("url"))
    }
    @Test fun mergeAcrossSourcesAndCorroboration() {
        fake { u, _ -> when {
            "deepseek" in u -> ds("https://same", "https://only-ds")
            "tools/search" in u -> kimi("https://same")
            else -> FactNet.Resp(200, "{}") } }
        val g = FactCheck.gather(listOf(DEEPSEEK, KIMI), { "k" }, "t", "《甜蜜蜜》由王家卫执导。")
        val same = g.hits.first { it.url == "https://same" }
        assertEquals("DeepSeek、Kimi", same.via)
        assertEquals(1, g.hits.count { it.url == "https://same" })
        val iss = FactIssue("1", "王家卫执导", "导演", "高", "", "陈可辛执导", "")
        val two = FactCheck.attachEvidence(listOf(iss.copy(evidenceIds = listOf("E${g.hits.indexOf(same) + 1}"))), g.hits).single()
        assertEquals(2, two.corroboration); assertEquals("2 个来源印证", two.corroborationLabel())
        val one = FactCheck.attachEvidence(listOf(iss.copy(evidenceIds = listOf("E${g.hits.indexOfFirst { it.url == "https://only-ds" } + 1}"))), g.hits).single()
        assertEquals("仅 1 个来源", one.corroborationLabel()); assertEquals("https://only-ds", one.links.single().url)
    }
    @Test fun oneSource429OtherOk() {
        fake { u, _ -> if ("deepseek" in u) FactNet.Resp(429, "{}") else if ("moonshot" in u) kimi("https://k1") else FactNet.Resp(200, "{}") }
        val g = FactCheck.gather(listOf(DEEPSEEK, KIMI), { "k" }, "t", "《甜蜜蜜》由王家卫执导。")
        assertFalse(g.modelOnly); assertTrue(g.hits.any { it.url == "https://k1" })
        assertTrue(g.note, g.note.startsWith("检索：Kimi") && g.note.contains("DeepSeek 搜索请求太频繁"))
    }
    @Test fun plotItemUsesFetchedFullText() {
        val wikiText = "无关的开头段落介绍演员阵容\n\n剧情\n\n黎小军与李翘在香港相识。多年后两人在纽约街头重逢，结局是两人相视而笑。\n\n票房"
        fake { u, _ -> when {
            "prop=extracts" in u -> FactNet.Resp(200, JSONObject().put("query", JSONObject().put("pages", JSONObject().put("1", JSONObject().put("extract", wikiText)))).toString())
            "list=search" in u -> FactNet.Resp(200, """{"query":{"search":[{"title":"甜蜜蜜 (1996年电影)","snippet":"x"}]}}""")
            "moonshot.cn/v1/tools/fetch" in u -> FactNet.Resp(200, """{"markdown":"## 剧情简介\n黎小军最后在纽约与李翘重逢。"}""")
            "moonshot" in u -> kimi("https://baike.baidu.com/item/甜蜜蜜")
            else -> ds("https://movie.douban.com/subject/1") } }
        val items = FactEvidence.plotItems("甜蜜蜜", body)
        assertTrue(items.isNotEmpty()); assertTrue(FactEvidence.plotQuery(items[0]).startsWith("甜蜜蜜 ")); assertTrue(FactEvidence.plotQuery(items[0]).contains("结局"))
        val g = FactCheck.gather(listOf(DEEPSEEK, KIMI, WIKI), { "k" }, "甜蜜蜜", body)
        val full = g.hits.filter { it.text.isNotBlank() }
        assertTrue(full.any { it.text.contains("纽约街头重逢") }); assertFalse(full.any { it.text.contains("票房") && it.text.contains("演员阵容") && it.text.length > 200 })
        assertTrue(full.any { it.url.contains("baike.baidu.com") || it.url.contains("douban") })
        assertTrue(g.fetches in 1..FactEvidence.MAX_FETCH_PER_RUN)
        assertTrue(calls.count { "tools/fetch" in it.first } <= FactEvidence.MAX_FETCH_PER_ITEM * items.size)
        val p = FactCheck.buildPrompt(FactCheck.DEFAULT_PROMPT, "甜蜜蜜", body, g.hits)
        assertTrue(p.contains("全文摘录") && p.contains("纽约街头重逢") && p.contains("无法核实") && p.contains("\"quote\""))
    }
    @Test fun noEvidenceGivesUnverifiableWithLinksWhenCited() {
        val reply = """{"issues":[{"span":"黎小军最后和李翘在纽约重逢","category":"情节","severity":"中","claim":"在纽约重逢","correction":"","verdict":"无法核实","evidence":[]},
          {"span":"由王家卫执导","category":"作者名","severity":"高","correction":"由陈可辛执导","verdict":"有出入","evidence":["E1"],"quote":"陈可辛执导"}],"skipped":[]}"""
        val (issues, _) = FactCheck.parse(reply)
        val hits = listOf(FactHit("甜蜜蜜 - 维基百科", "https://zh.wikipedia.org/wiki/甜蜜蜜", "陈可辛执导", "维基百科", "维基"))
        val out = FactCheck.attachEvidence(issues, hits)
        val u = out[0]; assertEquals("无法核实", u.verdict); assertFalse(u.selected); assertEquals(0, u.corroboration); assertEquals("无资料依据", u.corroborationLabel()); assertTrue(u.links.isEmpty())
        val e = out[1]; assertEquals("有出入", e.verdict); assertTrue(e.selected); assertEquals("陈可辛执导", e.quote)
        assertEquals("https://zh.wikipedia.org/wiki/甜蜜蜜", e.links.single().url); assertEquals("甜蜜蜜 - 维基百科", e.links.single().title)
        val back = FactCheck.decodeReport(FactCheck.encodeReport(FactReport(out, emptyList(), "n", emptyList())))!!.issues
        assertEquals(out.map { it.verdict to it.links }, back.map { it.verdict to it.links }); assertEquals(out[1].quote, back[1].quote)
        // 模型说「已印证」却没有任何依据 → 降为无法核实
        val fake = FactCheck.attachEvidence(FactCheck.parse("""{"issues":[{"span":"x","claim":"x","verdict":"已印证"}]}""").first, emptyList()).single()
        assertEquals("无法核实", fake.verdict)
    }
    @Test fun dedupeMergesSimilar() {
        assertEquals(listOf("甜蜜蜜 剧情简介", "陈可辛"), FactEvidence.dedupe(listOf("甜蜜蜜", "甜蜜蜜 剧情简介", "《甜蜜蜜》", "陈可辛")))
        assertEquals(FactEvidence.MAX_QUERIES, FactEvidence.dedupe((1..10).map { "查询$it 号" }).size)
    }
    @Test fun providersMultiSelectDefault() {
        assertEquals(listOf(DEEPSEEK), FactCheck.SearchProvider.many(""))
        assertEquals(listOf(DEEPSEEK), FactCheck.SearchProvider.many("deepseek"))
        assertEquals(listOf(DEEPSEEK, KIMI), FactCheck.SearchProvider.many("deepseek,kimi"))
        assertEquals("deepseek,kimi,wiki", FactCheck.SearchProvider.csv(listOf(WIKI, KIMI, DEEPSEEK)))
    }

    // ---- Notion：数据库目标正文末尾不再追加 #标签 ----
    private val DB = "3fc667b0-77e5-80aa-bbbb-111122223333"; private val DS = "aaaa1111-2222-4333-8444-555566667777"; private val PAGE = "1a2b3c4d-5e6f-8071-8293-a4b5c6d7e8f9"
    private fun notionPost(type: String, target: String): JSONArray {
        var posted: String? = null
        Notion.transport = { m, u, _, b -> val k = "$m ${u.removePrefix("https://api.notion.com")}"
            when (k) { "GET /v1/databases/$DB" -> 200 to """{"data_sources":[{"id":"$DS"}]}"""; "GET /v1/data_sources/$DS" -> 200 to """{"properties":{"Name":{"type":"title"}}}"""
                "GET /v1/pages/$PAGE" -> 200 to "{}"; "POST /v1/pages" -> { posted = b; 200 to """{"id":"p","url":"u"}""" }; else -> 404 to "{}" } }
        val tags = listOf("影评", "爱情")
        Notion.publishFull("tok", type, target, "", "", "T", Notion.blocks("第一段。\n\n第二段。", tags, emptyList(), false), tags, null, Notion.Meta("") { "" })
        return JSONObject(posted!!).getJSONArray("children")
    }
    private fun texts(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map { b -> b.optJSONObject(b.optString("type"))?.optJSONArray("rich_text")?.let { rt -> (0 until rt.length()).joinToString("") { rt.getJSONObject(it).optJSONObject("text")?.optString("content").orEmpty() } }.orEmpty() }
    @Test fun databaseBodyHasNoTagBlock() {
        val t = texts(notionPost("database", DB))
        assertEquals(listOf("第一段。", "第二段。"), t); assertTrue(t.none { it.contains("#") })
    }
    @Test fun pageBodyKeepsTagLine() {
        assertEquals("#影评 #爱情", texts(notionPost("page", PAGE)).last())
    }
}
