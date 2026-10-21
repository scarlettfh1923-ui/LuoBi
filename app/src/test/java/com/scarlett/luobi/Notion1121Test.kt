package com.scarlett.luobi

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Notion1121Test {
    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<String?>()
    private val headers = mutableListOf<Map<String, String>>()
    private val orig = Notion.transport
    @After fun restore() { Notion.transport = orig }

    private fun mock(vararg routes: Pair<String, Pair<Int, String>>) {
        val map = routes.toMap()
        Notion.transport = { m, u, h, b ->
            val key = "$m ${u.removePrefix("https://api.notion.com")}"
            calls += key; bodies += b; headers += h
            map[key] ?: (404 to """{"object":"error","status":404,"code":"object_not_found","message":"not mocked: $key"}""")
        }
    }

    private val DB = "3fc667b0-77e5-80aa-bbbb-111122223333"
    private val VIEW = "3fc667b0-77e5-806e-9386-000c586bc9c9"
    private val PAGE = "1a2b3c4d-5e6f-8071-8293-a4b5c6d7e8f9"
    private val DS = "aaaa1111-2222-4333-8444-555566667777"

    // ---------- 链接解析 ----------
    @Test fun herLinkShapeTakesPathIdNotViewId() {
        val link = "https://www.notion.so/3fc667b077e580aabbbb111122223333?v=3fc667b077e5806e9386000c586bc9c9&source=copy_link"
        assertEquals(DB, Notion.parseId(link))
        assertTrue(Notion.looksLikeDatabaseLink(link))
    }
    @Test fun urlShapes() {
        val hex = "1a2b3c4d5e6f80718293a4b5c6d7e8f9"
        listOf(
            "https://www.notion.so/My-Page-Title-$hex",
            "https://www.notion.so/workspace/My-Page-Title-$hex?source=copy_link",
            "https://notion.so/$hex",
            "https://www.notion.so/ws/$hex#abcdef",
            "https://www.notion.so/ws/中文标题-$hex?pvs=4",
            "https://example.notion.site/Title-$hex",
            "https://www.notion.so/ws/Title-$hex/",
            hex, hex.uppercase(), PAGE, "  $PAGE  ",
            "https://www.notion.so/$PAGE?source=copy_link",
        ).forEach { assertEquals(it, PAGE, Notion.parseId(it)) }
        assertNull(Notion.parseId("https://www.notion.so/no-id-here"))
        assertNull(Notion.parseId(""))
        assertFalse(Notion.looksLikeDatabaseLink("https://www.notion.so/Title-$hex?source=copy_link"))
    }

    // ---------- 页面 ----------
    @Test fun pageTestCallsOnlyPagesEndpoint() {
        mock("GET /v1/users/me" to (200 to "{}"), "GET /v1/pages/$PAGE" to (200 to """{"object":"page","id":"$PAGE"}"""))
        val r = Notion.testWith("tok", "page", "https://www.notion.so/T-1a2b3c4d5e6f80718293a4b5c6d7e8f9?source=copy_link")
        assertEquals(listOf("GET /v1/users/me", "GET /v1/pages/$PAGE"), calls)
        assertTrue(calls.none { it.contains("databases") || it.contains("data_sources") })
        assertTrue(r.contains("页面"))
        assertEquals("2025-09-03", headers[0]["Notion-Version"])
    }
    @Test fun page404ShowsMethodPathTypeAndNotionCode() {
        mock("GET /v1/users/me" to (200 to "{}"))
        val link = "https://www.notion.so/3fc667b077e580aabbbb111122223333?v=3fc667b077e5806e9386000c586bc9c9&source=copy_link"
        val e = assertThrows(PublishError::class.java) { Notion.testWith("tok", "page", link) }
        val m = e.message!!
        assertTrue(m, m.contains("GET /v1/pages/$DB"))
        assertTrue(m, m.contains("页面（建子页面）"))
        assertTrue(m, m.contains("object_not_found"))
        assertTrue(m, m.contains("数据库（新增一行）")) // ?v= 提示
        assertFalse(m.contains(VIEW))
        assertTrue(calls.none { it.contains("databases") })
    }
    @Test fun pagePublishUsesPageParent() {
        mock("GET /v1/pages/$PAGE" to (200 to "{}"), "POST /v1/pages" to (200 to """{"id":"new","url":"https://notion.so/new"}"""))
        val url = Notion.publish("tok", "page", PAGE, "", "", "标题", listOf(JSONObject().put("type", "paragraph")), emptyList())
        assertEquals("https://notion.so/new", url)
        assertEquals(listOf("GET /v1/pages/$PAGE", "POST /v1/pages"), calls)
        val body = JSONObject(bodies[1]!!)
        assertEquals(PAGE, body.getJSONObject("parent").getString("page_id"))
        assertTrue(body.getJSONObject("properties").has("title"))
    }

    // ---------- 数据库 ----------
    @Test fun databaseResolvesDataSourceAndPublishesWithDataSourceParent() {
        mock(
            "GET /v1/users/me" to (200 to "{}"),
            "GET /v1/databases/$DB" to (200 to """{"object":"database","id":"$DB","data_sources":[{"id":"$DS","name":"文案"}]}"""),
            "GET /v1/data_sources/$DS" to (200 to """{"object":"data_source","properties":{"标题":{"type":"title"},"标签":{"type":"multi_select"}}}"""),
            "POST /v1/pages" to (200 to """{"id":"p","url":"u"}"""),
        )
        val link = "https://www.notion.so/3fc667b077e580aabbbb111122223333?v=3fc667b077e5806e9386000c586bc9c9&source=copy_link"
        assertTrue(Notion.testWith("tok", "database", link).contains(DS))
        calls.clear(); bodies.clear()
        Notion.publish("tok", "database", link, "", "标签", "T", emptyList(), listOf("a"))
        assertEquals(listOf("GET /v1/databases/$DB", "GET /v1/data_sources/$DS", "POST /v1/pages"), calls)
        val b = JSONObject(bodies[2]!!)
        assertEquals("data_source_id", b.getJSONObject("parent").getString("type"))
        assertEquals(DS, b.getJSONObject("parent").getString("data_source_id"))
        assertTrue(b.getJSONObject("properties").has("标题"))
        assertTrue(b.getJSONObject("properties").has("标签"))
    }
    @Test fun databaseIdThatIsActuallyDataSource() {
        mock("GET /v1/data_sources/$DS" to (200 to """{"object":"data_source","properties":{"Name":{"type":"title"}}}"""))
        val t = Notion.resolve("tok", "database", DS)
        assertEquals(listOf("GET /v1/databases/$DS", "GET /v1/data_sources/$DS"), calls)
        assertEquals(DS, t.parentJson.getString("data_source_id"))
        assertEquals("Name", t.titleProp)
    }
    @Test fun databaseFailureReportsBothPaths() {
        mock()
        val e = assertThrows(PublishError::class.java) { Notion.resolve("tok", "database", DB) }
        assertTrue(e.message!!, e.message!!.contains("GET /v1/databases/$DB"))
        assertTrue(e.message!!.contains("/v1/data_sources/$DB"))
        assertTrue(e.message!!.contains("数据库（新增一行）"))
    }
    @Test fun testAndPublishResolveSameWay() {
        mock("GET /v1/users/me" to (200 to "{}"), "GET /v1/pages/$PAGE" to (200 to "{}"), "POST /v1/pages" to (200 to "{}"))
        Notion.testWith("tok", "page", PAGE); val testCalls = calls.drop(1).toList(); calls.clear()
        Notion.publish("tok", "page", PAGE, "", "", "t", emptyList(), emptyList())
        assertEquals(testCalls, calls.dropLast(1))
    }
}
