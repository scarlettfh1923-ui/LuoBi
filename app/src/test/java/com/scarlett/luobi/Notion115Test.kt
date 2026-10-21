package com.scarlett.luobi

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Notion115Test {
    private val calls = mutableListOf<String>(); private val bodies = mutableListOf<Pair<String, String?>>()
    private val orig = Notion.transport
    @After fun r() { Notion.transport = orig }
    private val DB = "3fc667b0-77e5-80aa-bbbb-111122223333"; private val DS = "aaaa1111-2222-4333-8444-555566667777"; private val PAGE = "1a2b3c4d-5e6f-8071-8293-a4b5c6d7e8f9"
    private fun mock(schema: String, post: (String?) -> Pair<Int, String> = { 200 to """{"id":"p1","url":"https://notion.so/p1"}""" }) {
        Notion.transport = { m, u, _, b ->
            val k = "$m ${u.removePrefix("https://api.notion.com")}"; calls += k; bodies += k to b
            when (k) {
                "GET /v1/databases/$DB" -> 200 to """{"data_sources":[{"id":"$DS"}]}"""
                "GET /v1/data_sources/$DS" -> 200 to """{"properties":$schema}"""
                "GET /v1/pages/$PAGE" -> 200 to "{}"
                "POST /v1/pages" -> post(b)
                else -> 404 to "{}"
            }
        }
    }
    private val FULL = """{"名称":{"type":"title"},"文章分类":{"type":"select"},"主题标签":{"type":"multi_select"},"文章摘要":{"type":"rich_text"}}"""
    private fun lastPost() = JSONObject(bodies.last { it.first == "POST /v1/pages" }.second!!)

    @Test fun databasePropsExactNamesAndTypes() {
        mock(FULL)
        var asked = 0
        val r = Notion.publishFull("tok", "database", DB, "", "", "标题", emptyList(), listOf("影评", "爱情观"), null,
            Notion.Meta("经济与制度") { asked++; "这是一段一百多字的摘要。" })
        val p = lastPost().getJSONObject("properties")
        assertEquals("经济与制度", p.getJSONObject("文章分类").getJSONObject("select").getString("name"))
        val ms = p.getJSONObject("主题标签").getJSONArray("multi_select")
        assertEquals(listOf("影评", "爱情观"), (0 until ms.length()).map { ms.getJSONObject(it).getString("name") })
        assertEquals("这是一段一百多字的摘要。", p.getJSONObject("文章摘要").getJSONArray("rich_text").getJSONObject(0).getJSONObject("text").getString("content"))
        assertTrue(p.getJSONObject("名称").has("title"))
        assertEquals(1, asked); assertEquals("", r.coverNote)
    }
    @Test fun missingAndWrongTypeSkippedWithNote() {
        mock("""{"Name":{"type":"title"},"文章分类":{"type":"rich_text"}}""")
        var asked = 0
        val r = Notion.publishFull("tok", "database", DB, "", "", "T", emptyList(), listOf("a"), null, Notion.Meta("电影") { asked++; "x" })
        val p = lastPost().getJSONObject("properties")
        assertFalse(p.has("文章分类") || p.has("主题标签") || p.has("文章摘要"))
        assertEquals(0, asked) // 没有「文章摘要」就不调 AI
        assertTrue(r.coverNote, r.coverNote.contains("「文章分类」类型是 rich_text") && r.coverNote.contains("没有「主题标签」") && r.coverNote.contains("没有「文章摘要」"))
        assertEquals("https://notion.so/p1", r.url)
    }
    @Test fun summaryFailureStillPublishes() {
        mock(FULL)
        val r = Notion.publishFull("tok", "database", DB, "", "", "T", emptyList(), listOf("a"), null, Notion.Meta("电影") { throw RuntimeException("模型超时") })
        assertEquals("https://notion.so/p1", r.url)
        assertFalse(lastPost().getJSONObject("properties").has("文章摘要"))
        assertTrue(lastPost().getJSONObject("properties").has("文章分类"))
        assertTrue(r.coverNote.contains("摘要没生成") && r.coverNote.contains("模型超时"))
    }
    @Test fun rejectedPropertiesRetryWithout() {
        var n = 0
        mock(FULL) { n++; if (n == 1) 400 to """{"code":"validation_error","message":"bad select"}""" else 200 to """{"id":"p1","url":"u"}""" }
        val r = Notion.publishFull("tok", "database", DB, "", "", "T", emptyList(), listOf("a"), null, Notion.Meta("电影") { "s" })
        assertEquals("u", r.url); assertEquals(2, calls.count { it == "POST /v1/pages" })
        assertFalse(lastPost().getJSONObject("properties").has("文章分类"))
        assertTrue(r.coverNote.contains("bad select"))
    }
    @Test fun emptyCategoryNotSentAndSummaryTruncated() {
        mock(FULL)
        Notion.publishFull("tok", "database", DB, "", "", "T", emptyList(), emptyList(), null, Notion.Meta("") { "字".repeat(5000) })
        val p = lastPost().getJSONObject("properties")
        assertFalse(p.has("文章分类")); assertFalse(p.has("主题标签"))
        val rt = p.getJSONObject("文章摘要").getJSONArray("rich_text")
        assertTrue((0 until rt.length()).all { rt.getJSONObject(it).getJSONObject("text").getString("content").length <= 2000 })
    }
    @Test fun pageTargetHasNoProperties() {
        mock(FULL)
        var asked = 0
        Notion.publishFull("tok", "page", PAGE, "", "", "T", emptyList(), listOf("a"), null, Notion.Meta("电影") { asked++; "s" })
        assertEquals(setOf("title"), lastPost().getJSONObject("properties").keys().asSequence().toSet())
        assertEquals(0, asked); assertTrue(calls.none { it.contains("data_sources") })
    }
    @Test fun summaryClean() {
        assertEquals("核心观点。", Summary.clean("<think>x</think>摘要：**核心观点。**"))
        assertTrue(Summary.clean("字".repeat(500)).length <= 200)
    }
}
