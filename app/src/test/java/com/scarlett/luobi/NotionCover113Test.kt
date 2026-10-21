package com.scarlett.luobi

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NotionCover113Test {
    private val calls = mutableListOf<String>()
    private val bodies = mutableMapOf<String, String?>()
    private val sends = mutableListOf<String>()
    private val origT = Notion.transport; private val origS = Notion.sendFile
    @After fun restore() { Notion.transport = origT; Notion.sendFile = origS }
    private val PAGE = "1a2b3c4d-5e6f-8071-8293-a4b5c6d7e8f9"
    private val NEW = "99999999-0000-4000-8000-000000000001"

    private fun mock(vararg extra: Pair<String, Pair<Int, String>>) {
        val map = mapOf(
            "GET /v1/pages/$PAGE" to (200 to "{}"),
            "POST /v1/pages" to (200 to """{"id":"$NEW","url":"https://notion.so/new"}"""),
            "PATCH /v1/pages/$NEW" to (200 to "{}"),
        ) + extra.toMap()
        Notion.transport = { m, u, _, b -> val k = "$m ${u.removePrefix("https://api.notion.com")}"; calls += k; bodies[k] = b; map[k] ?: (500 to """{"code":"internal","message":"x"}""") }
        Notion.sendFile = { u, _, f, mime, bytes -> sends += "$u|$f|$mime|${bytes.size}"; 200 to """{"status":"uploaded"}""" }
    }
    private fun pub(cover: Notion.CoverSource?) = Notion.publishFull("tok", "page", PAGE, "", "", "T", emptyList(), emptyList(), cover)

    @Test fun firstImageIsEarliestParagraph() {
        val pics = listOf(Pic(3, "https://b.jpg"), Pic(0, "https://a.jpg"), Pic(0, "https://a2.jpg"))
        assertEquals("https://a.jpg", Notion.firstImage(pics, 5)!!.url)
        assertNull(Notion.firstImage(emptyList(), 5))
    }
    @Test fun remoteImageSetsExternalCover() {
        mock()
        val r = pub(Notion.coverSourceOf(Pic(0, "https://img.example/a.jpg")))
        assertEquals(listOf("GET /v1/pages/$PAGE", "POST /v1/pages", "PATCH /v1/pages/$NEW"), calls)
        val c = JSONObject(bodies["PATCH /v1/pages/$NEW"]!!).getJSONObject("cover")
        assertEquals("external", c.getString("type"))
        assertEquals("https://img.example/a.jpg", c.getJSONObject("external").getString("url"))
        assertEquals("", r.coverNote)
        assertFalse(JSONObject(bodies["POST /v1/pages"]!!).has("cover"))
    }
    @Test fun localImageUsesFileUpload() {
        val f = File.createTempFile("pic", ".png").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        mock("POST /v1/file_uploads" to (200 to """{"id":"fu-1","status":"pending"}"""))
        val src = Notion.coverSourceOf(Pic(0, "file://" + f.absolutePath))
        assertTrue(src is Notion.CoverSource.Local)
        val r = pub(src)
        assertEquals(listOf("GET /v1/pages/$PAGE", "POST /v1/pages", "POST /v1/file_uploads", "PATCH /v1/pages/$NEW"), calls)
        assertEquals(1, sends.size); assertTrue(sends[0].startsWith("https://api.notion.com/v1/file_uploads/fu-1/send|"))
        assertTrue(sends[0].contains("|image/png|10"))
        val c = JSONObject(bodies["PATCH /v1/pages/$NEW"]!!).getJSONObject("cover")
        assertEquals("file_upload", c.getString("type")); assertEquals("fu-1", c.getJSONObject("file_upload").getString("id"))
        assertEquals("", r.coverNote)
    }
    @Test fun noImageNoCover() {
        mock()
        val r = pub(null)
        assertEquals(listOf("GET /v1/pages/$PAGE", "POST /v1/pages"), calls)
        assertEquals("https://notion.so/new", r.url); assertEquals("", r.coverNote)
    }
    @Test fun missingLocalFileSkipsCover() { assertNull(Notion.coverSourceOf(Pic(0, "file:///nope/x.jpg"))) }
    @Test fun coverUploadFailureStillPublishes() {
        val f = File.createTempFile("pic", ".jpg").apply { writeBytes(ByteArray(5)); deleteOnExit() }
        mock("POST /v1/file_uploads" to (400 to """{"code":"validation_error","message":"bad"}"""))
        val r = pub(Notion.coverSourceOf(Pic(0, "file://" + f.absolutePath)))
        assertEquals("https://notion.so/new", r.url)
        assertTrue(r.coverNote, r.coverNote.contains("封面没设上") && r.coverNote.contains("/v1/file_uploads"))
        assertFalse(calls.contains("PATCH /v1/pages/$NEW"))
    }
    @Test fun coverPatchFailureStillPublishes() {
        mock("PATCH /v1/pages/$NEW" to (400 to """{"code":"validation_error","message":"bad cover"}"""))
        val r = pub(Notion.coverSourceOf(Pic(0, "https://x/a.jpg")))
        assertEquals("https://notion.so/new", r.url); assertTrue(r.coverNote.contains("bad cover"))
    }
    @Test fun databaseTargetAlsoGetsCover() {
        val DB = "3fc667b0-77e5-80aa-bbbb-111122223333"; val DS = "aaaa1111-2222-4333-8444-555566667777"
        mock("GET /v1/databases/$DB" to (200 to """{"data_sources":[{"id":"$DS"}]}"""),
            "GET /v1/data_sources/$DS" to (200 to """{"properties":{"Name":{"type":"title"}}}"""))
        val r = Notion.publishFull("tok", "database", DB, "", "", "T", emptyList(), emptyList(), Notion.CoverSource.External("https://x/a.jpg"))
        assertTrue(calls.last() == "PATCH /v1/pages/$NEW"); assertEquals("", r.coverNote)
    }
}
