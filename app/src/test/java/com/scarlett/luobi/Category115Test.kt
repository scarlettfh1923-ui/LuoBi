package com.scarlett.luobi

import org.junit.Assert.*
import org.junit.Test

class Category115Test {
    @Test fun listIsExactly15InOrder() {
        assertEquals(listOf("学习方法与知识管理", "文学", "电影", "哲学思考", "思想史与观念史", "历史", "文化与艺术", "政治",
            "社会观察", "经济与制度", "社会学与现代性", "心理与关系", "生活与自我认知", "写作与表达", "AI 科技"), Categories.LIST)
        assertFalse(Categories.LIST.contains("其他"))
    }
    @Test fun validate() {
        assertEquals("电影", Categories.validate("电影"))
        assertEquals("电影", Categories.validate(" 「电影」 "))
        assertEquals("AI 科技", Categories.validate("AI 科技"))
        assertEquals("", Categories.validate("AI科技"))
        assertEquals("", Categories.validate("其他"))
        assertEquals("", Categories.validate("电影评论"))
        assertEquals("", Categories.validate(null))
    }
    @Test fun parseCategoryFromOutput() {
        val ok = ArticleParse.parse("""{"title":"t","body":"b","tags":["影评","爱情"],"category":"电影"}""", "x")
        assertEquals("电影", ok.category); assertEquals(listOf("影评", "爱情"), ok.tags)
        assertEquals("", ArticleParse.parse("""{"title":"t","body":"b","tags":[],"category":"影视"}""", "x").category)
        assertEquals("", ArticleParse.parse("""{"title":"t","body":"b"}""", "x").category)
        assertTrue(Ai.OUT_RULE.contains("\"category\"") && Categories.LIST.all { Ai.OUT_RULE.contains(it) })
    }
    @Test fun deskSectionsAndFilter() {
        val cal = java.util.Calendar.getInstance(java.util.Locale.CHINA).apply { set(2026, 9, 21, 15, 0, 0) } // 周三
        val now = cal.timeInMillis; val day = 86_400_000L
        assertEquals("今天", Desk.section(now - 3_600_000L, now))
        assertEquals("本周", Desk.section(now - day, now))      // 周二
        assertEquals("本周", Desk.section(now - 2 * day, now))  // 周一
        assertEquals("10月", Desk.section(now - 3 * day, now))  // 上周日
        assertEquals("9月", Desk.section(now - 40 * day, now))
        assertEquals("2025年10月", Desk.section(now - 366 * day, now))
        val ns = listOf(Note(id = 1, title = "a", body = "", tags = "", category = "电影", createdAt = 1),
            Note(id = 2, title = "b", body = "", tags = "", category = "历史", createdAt = 3),
            Note(id = 3, title = "c", body = "", tags = "", category = "电影", createdAt = 2))
        assertEquals(listOf(3L, 1L), Desk.filter(ns, "电影").map { it.id })
        assertEquals(listOf(2L, 3L, 1L), Desk.filter(ns, "").map { it.id })
        val c = Desk.counts(ns)
        assertEquals(16, c.size); assertEquals("" to 3, c[0]); assertEquals("电影" to 2, c.first { it.first == "电影" })
    }
    @Test fun articleInfoLine() {
        assertEquals("通用非虚构 · DeepSeek deepseek-flash · 第 1 版", ArticleInfo.line(Note(title = "", body = "", tags = "", templateName = "通用非虚构", model = "DeepSeek · deepseek-flash")))
    }
}
