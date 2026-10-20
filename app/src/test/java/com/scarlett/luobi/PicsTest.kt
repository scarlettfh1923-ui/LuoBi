package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JsonPathTest {
    private val unsplash = JSONObject("""{"total":2,"results":[{"urls":{"regular":"https://a/1.jpg","thumb":"https://a/1t.jpg"},"user":{"name":"Ann"}},{"urls":{"regular":"https://a/2.jpg","thumb":"https://a/2t.jpg"},"user":{"name":"Bob"}}]}""")
    @Test fun arrayWildcard() = assertEquals(listOf("https://a/1.jpg", "https://a/2.jpg"), JsonPath.get(unsplash, "results[].urls.regular"))
    @Test fun index() = assertEquals(listOf("Bob"), JsonPath.get(unsplash, "results[1].user.name"))
    @Test fun rootArray() = assertEquals(listOf("x", "y"), JsonPath.get(JSONArray("""[{"url":"x"},{"url":"y"},{"url":null}]"""), "[].url"))
    @Test fun dollarPrefixAndNested() = assertEquals(listOf("1"), JsonPath.get(JSONObject("""{"data":{"items":[[{"id":1}]]}}"""), "$.data.items[0][].id"))
    @Test fun missing() { assertTrue(JsonPath.get(unsplash, "results[].nope.x").isEmpty()); assertTrue(JsonPath.get(unsplash, "results[9].urls.regular").isEmpty()) }
    @Test fun zipAligns() {
        val z = JsonPath.zip(unsplash, "results[].urls.regular", "results[].urls.thumb", "results[].user.name")
        assertEquals(Triple("https://a/2.jpg", "https://a/2t.jpg", "Bob"), z[1])
        assertEquals("https://a/1.jpg", JsonPath.zip(unsplash, "results[].urls.regular", "", "")[0].second)
    }
    @Test fun pexafyShape() {
        val j = JSONObject("""{"data":[{"urls":{"regular":"r1","thumb":"t1"},"attribution":{"plain":"Photo by A on Pexafy"}}]}""")
        assertEquals(listOf("Photo by A on Pexafy"), JsonPath.get(j, "data[].attribution.plain"))
    }
}

class ChainTest {
    @Test fun firstSuccess() {
        val (s, r, e) = Pics.chain(listOf("a", "b")) { listOf("$it-1") }
        assertEquals("a", s); assertEquals(listOf("a-1"), r); assertTrue(e.isEmpty())
    }
    @Test fun emptyThenFailThenOk() {
        val (s, r, e) = Pics.chain(listOf("empty", "boom", "ok")) { when (it) { "empty" -> emptyList(); "boom" -> throw RuntimeException("401"); else -> listOf(1, 2) } }
        assertEquals("ok", s); assertEquals(listOf(1, 2), r)
        assertEquals(listOf("empty" to "无结果", "boom" to "401"), e)
    }
    @Test fun allFail() {
        val (s, r, e) = Pics.chain<String, Int>(listOf("a", "b")) { throw IllegalStateException("x") }
        assertNull(s); assertTrue(r.isEmpty()); assertEquals(2, e.size)
    }
    @Test fun noSources() = assertNull(Pics.chain(emptyList<String>()) { listOf(1) }.first)
}

class PlanParseTest {
    @Test fun standard() {
        val p = Pics.parsePlan("""{"images":[{"after":1,"query":"a desk at night"},{"after":4,"query":"city walk"}]}""", 6, 2, 3)
        assertEquals(listOf(PicPlan(1, "a desk at night"), PicPlan(4, "city walk")), p)
    }
    @Test fun fencedThinkAndProse() {
        val p = Pics.parsePlan("<think>hmm</think>好的：\n```json\n{\"images\":[{\"after\":\"2\",\"query\":\"coffee cup\"}]}\n```\n以上", 5, 1, 3)
        assertEquals(listOf(PicPlan(2, "coffee cup")), p)
    }
    @Test fun bareArrayAndAltKeys() {
        val p = Pics.parsePlan("""[{"paragraph":"第3段","description":"rainy window"},{"index":0,"prompt":"sunrise"}]""", 5, 1, 4)
        assertEquals(listOf(PicPlan(0, "sunrise"), PicPlan(3, "rainy window")), p)
    }
    @Test fun clampDedupeCap() {
        val p = Pics.parsePlan("""{"images":[{"after":99,"query":"a"},{"after":2,"query":"b"},{"after":2,"query":"c"},{"after":0,"query":"d"},{"after":1,"query":"e"}]}""", 4, 1, 3)
        assertEquals(listOf(0, 2, 3), p.map { it.after }.sorted().let { listOf(it[0], it[1], it[2]) })
        assertEquals(3, p.size); assertEquals("b", p.first { it.after == 2 }.query); assertEquals("a", p.first { it.after == 3 }.query)
    }
    @Test fun garbage() { assertTrue(Pics.parsePlan("抱歉我无法完成", 5, 2, 3).isEmpty()); assertTrue(Pics.parsePlan("""{"images":[{"after":1}]}""", 5, 1, 3).isEmpty()) }
    @Test fun noParas() = assertTrue(Pics.parsePlan("""{"images":[{"after":0,"query":"x"}]}""", 0, 1, 3).isEmpty())
}

class PicExportTest {
    private val body = "第一段。\n\n## 小标题\n\n第二段。"
    private val pic = Pic(0, "https://img/1.jpg", "https://img/1t.jpg", "夜里的书桌", "Ann", "Pexels", "/data/x.jpg")
    private val local = Pic(2, "file:///data/ai.jpg", caption = "AI 图", source = "AI")
    @Test fun roundTrip() = assertEquals(listOf(pic, local), Pics.decode(Pics.encode(listOf(pic, local))))
    @Test fun decodeBad() { assertTrue(Pics.decode("oops").isEmpty()); assertTrue(Pics.decode(null).isEmpty()) }
    @Test fun markdownHasImage() {
        val md = Export.markdown("T", body, emptyList(), listOf(pic, local))
        assertTrue(md.contains("第一段。\n\n![夜里的书桌](https://img/1.jpg)\n*夜里的书桌 · 图片来自 Pexels*"))
        assertFalse(md.contains("Ann"))
        assertTrue(Export.markdown("T", body, emptyList(), listOf(pic), showCredit = false).contains("*夜里的书桌*\n"))
        assertFalse(md.contains("file://"))
        assertTrue(md.indexOf("![夜里") < md.indexOf("## 小标题"))
    }
    @Test fun htmlHasImg() {
        val h = Export.htmlBody(body, emptyList(), pics = listOf(pic))
        assertTrue(h.contains("<img src=\"https://img/1.jpg\" alt=\"夜里的书桌\""))
        assertTrue(h.contains("<figcaption"))
        assertTrue(Export.htmlDoc("T", body, emptyList(), listOf(pic)).contains("<figure"))
    }
    @Test fun plainUnchanged() = assertEquals(Export.plain("T", body, emptyList()), "T\n\n$body")
    @Test fun creditLine() {
        assertEquals("图片来自 Pexels", pic.creditLine()); assertEquals("", Pic(0, "u").creditLine()); assertEquals("图片来自 我的 Unsplash", Pic(0, "u", source = "我的 Unsplash").creditLine())
        assertEquals("夜里的书桌", pic.captionLine(false)); assertEquals("", Pic(0, "u", source = "Pexafy").captionLine(false)); assertEquals("图片来自 Pexafy", Pic(0, "u", source = "Pexafy").captionLine(true))
        assertFalse(Export.htmlBody("a", emptyList(), pics = listOf(Pic(0, "https://x", source = "Pexafy")), showCredit = false).contains("图片来自"))
        assertTrue(Export.htmlBody("a", emptyList(), pics = listOf(Pic(0, "https://x", source = "Pexafy"))).contains("图片来自 Pexafy"))
    }
    @Test fun byParaClamps() = assertEquals(setOf(0, 2), Pics.byPara(listOf(pic, local.copy(after = 50)), 3).keys)
}
