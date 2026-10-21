package com.scarlett.luobi

import org.junit.Assert.*
import org.junit.Test

class FactCheck111Test {
    @Test fun queriesExtractTitlesAndCredits() {
        val q = FactCheck.queries("甜蜜蜜影评", "我想写一篇《甜蜜蜜》的影评。这部1996年的片子由王家卫执导，张曼玉和梁朝伟主演。")
        assertTrue(q.any { it.contains("甜蜜蜜") })
        assertTrue(q.any { it.contains("导演") || it.contains("主演") || it.contains("1996") })
    }
    @Test fun parseIssuesAndSkipped() {
        val (issues, skipped) = FactCheck.parse("""{"issues":[
          {"span":"由王家卫执导","category":"作者名","severity":"高","claim":"王家卫执导","correction":"由陈可辛执导","source":"维基百科"},
          {"span":"梁朝伟主演","category":"情节","severity":"中","claim":"梁朝伟主演","correction":"黎明主演","source":"维基百科"}
        ],"skipped":["最动人的镜头之一"]}""")
        assertEquals(2, issues.size)
        assertEquals("高", issues[0].severity)
        assertEquals("由陈可辛执导", issues[0].correction)
        assertEquals(listOf("最动人的镜头之一"), skipped)
    }
    @Test fun parseAliasKeys() {
        val (issues, _) = FactCheck.parse("""{"事实问题":[{"原文":"错名","类别":"片名","级别":"高","说法":"错","改正":"对","来源":"x"}]}""")
        assertEquals("错名", issues[0].span); assertEquals("对", issues[0].correction)
    }
    @Test fun applyFixesOnlySelected() {
        val body = "影片由王家卫执导，梁朝伟主演。"
        val a = FactIssue("1", "王家卫执导", "作者名", "高", "", "陈可辛执导", "", selected = true)
        val b = FactIssue("2", "梁朝伟主演", "情节", "中", "", "黎明主演", "", selected = false)
        assertEquals("影片由陈可辛执导，梁朝伟主演。", FactCheck.applyFixes(body, listOf(a, b)))
    }
    @Test fun promptFilled() {
        val p = FactCheck.buildPrompt(FactCheck.DEFAULT_PROMPT, "题", "正文一段", listOf(FactHit("甜蜜蜜", "https://zh.wikipedia.org", "陈可辛执导", "维基百科")))
        assertTrue(p.contains("陈可辛执导")); assertTrue(p.contains("题")); assertTrue(p.contains("正文一段"))
        assertFalse(p.contains("{搜索结果}") || p.contains("{标题}") || p.contains("{正文}"))
        assertTrue(p.contains("不做语法"))
    }
    @Test fun providerDefaultDeepseek() {
        assertEquals(FactCheck.SearchProvider.DEEPSEEK, FactCheck.SearchProvider.of("deepseek"))
        assertEquals(FactCheck.SearchProvider.WIKI, FactCheck.SearchProvider.of("wiki"))
        assertEquals(FactCheck.SearchProvider.BOCHA, FactCheck.SearchProvider.of("bocha"))
        assertEquals(FactCheck.SearchProvider.DEEPSEEK, FactCheck.SearchProvider.of("nope"))
    }
    @Test fun wikiSearchLive() {
        val hits = FactCheck.wikiSearch("甜蜜蜜 电影", 2)
        assertTrue("should get wiki hits", hits.isNotEmpty())
        assertTrue(hits[0].title.contains("甜蜜蜜") || hits[0].snippet.contains("甜蜜蜜"))
        val sum = FactCheck.wikiSummary("甜蜜蜜_(电影)")
        assertNotNull(sum); assertTrue(sum!!.snippet.contains("陈可辛") || sum.snippet.contains("张曼玉"))
    }
}
