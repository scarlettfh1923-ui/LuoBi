package com.scarlett.luobi

import org.junit.Assert.*
import org.junit.Test

class ShareBarTest {
    private val known = listOf("xhs", "wechat", "qq", "douyin", "notion", "long", "weibo", "html", "md", "copy", "yuque", "telegraph")
    @Test fun defaults() { assertEquals(listOf("xhs", "wechat", "qq", "douyin", "notion"), ShareBar.decode(null, known)); assertEquals(ShareBar.DEFAULT + "more", ShareBar.tiles(ShareBar.DEFAULT)) }
    @Test fun normalizeDropsJunkAndCaps() {
        assertEquals(listOf("long", "qq", "html", "md", "copy"), ShareBar.normalize(listOf("long", "more", "qq", "qq", "nope", " html ", "md", "copy", "yuque"), known))
    }
    @Test fun emptyAllowed() { assertEquals(emptyList<String>(), ShareBar.decode("", known)); assertEquals(listOf("more"), ShareBar.tiles(emptyList())) }
    @Test fun customIdsKept() = assertEquals(listOf("c12", "xhs"), ShareBar.decode("c12,xhs,cx", known))
    @Test fun roundTrip() { val l = listOf("weibo", "xhs", "c3"); assertEquals(l, ShareBar.decode(ShareBar.encode(l), known)) }
    @Test fun addRespectsMax() {
        val full = ShareBar.DEFAULT
        assertEquals(full, ShareBar.add(full, "long"))
        assertEquals(listOf("xhs", "long"), ShareBar.add(listOf("xhs"), "long"))
        assertEquals(listOf("xhs"), ShareBar.add(listOf("xhs"), "xhs"))
        assertEquals(listOf("xhs"), ShareBar.add(listOf("xhs"), "more"))
    }
    @Test fun removeAndMove() {
        assertEquals(listOf("xhs", "qq", "douyin", "notion"), ShareBar.remove(ShareBar.DEFAULT, "wechat"))
        assertEquals(listOf("wechat", "qq", "xhs", "douyin", "notion"), ShareBar.move(ShareBar.DEFAULT, 0, 2))
        assertEquals(listOf("notion", "xhs", "wechat", "qq", "douyin"), ShareBar.move(ShareBar.DEFAULT, 4, -3))
        assertEquals(ShareBar.DEFAULT, ShareBar.move(ShareBar.DEFAULT, 9, 0))
    }
    @Test fun othersKeepsOrder() = assertEquals(listOf("long", "weibo", "html", "md", "copy", "yuque", "telegraph"), ShareBar.others(known, ShareBar.DEFAULT))
}

class MdTest {
    private fun k(s: String, total: Int = 10) = Md.line(s, total)
    @Test fun headings() {
        assertEquals(MdLine(MdKind.H2, "一、先把时间「看见」"), k("## 一、先把时间「看见」"))
        assertEquals(MdLine(MdKind.H3, "每天 30 分钟就够"), k("### 每天 30 分钟就够"))
        assertEquals(MdKind.H2, k("# 标题").kind)
        assertEquals(MdLine(MdKind.H2, "小结"), k("**小结**"))
    }
    @Test fun quoteListHr() {
        assertEquals(MdLine(MdKind.QUOTE, "时间不会因为你忙而变多。"), k("> 时间不会因为你忙而变多。"))
        assertEquals(MdLine(MdKind.BULLET, "读完一章"), k("- 读完一章"))
        assertEquals(MdLine(MdKind.BULLET, "写下三行"), k("* 写下三行"))
        assertEquals(MdLine(MdKind.NUM, "连续记录一周，你会发现大部分时间被刷手机吃掉了。", "1"), k("1. 连续记录一周，你会发现大部分时间被刷手机吃掉了。"))
        assertEquals(MdKind.HR, k("---").kind); assertEquals(MdKind.HR, k("* * *").kind)
    }
    @Test fun boldParagraphStaysParagraph() {
        val l = k("很多人以为成长靠工作时长。**真正拉开差距的，是下班后那段时间。**")
        assertEquals(MdKind.P, l.kind)
        assertEquals(listOf("很多人以为成长靠工作时长。" to false, "真正拉开差距的，是下班后那段时间。" to true), Md.spans(l.text))
    }
    @Test fun spansEdgeCases() {
        assertEquals(listOf("a **b" to false), Md.spans("a **b"))
        assertEquals(listOf("a" to true, "b" to false, "c" to true), Md.spans("**a**b**c**"))
        assertEquals(listOf("x****y" to false), Md.spans("x****y"))
        assertEquals("ab", Md.strip("**a**b"))
    }
    @Test fun plainBodyStripsMarks() {
        val body = "## 小标题\n\n正文**金句**。\n\n> 引用\n\n- 一\n- 二\n\n---"
        assertEquals("小标题\n\n正文金句。\n\n「引用」\n\n• 一\n\n• 二\n\n——", Md.plainBody(body))
    }
    @Test fun htmlStyled() {
        val h = Export.htmlBody("## 标题\n\n正文**金句**\n\n> 引用\n\n- 列表项\n\n---", emptyList())
        assertTrue(h.contains("<h2 style=\"font-size:20px;font-weight:700"))
        assertTrue(h.contains("正文<strong>金句</strong>"))
        assertTrue(h.contains("<blockquote style=\"border-left:3px solid #D3C8B6;background:#F2ECE1"))
        assertTrue(h.contains("•&nbsp;&nbsp;列表项"))
        assertTrue(h.contains("<hr "))
        assertTrue(h.contains("margin:0 0 18px")) // 段距大于行距
    }
    @Test fun markdownNormalizes() {
        val md = Export.markdown("T", "**小结**\n\n正文**金句**\n\n> 引用", emptyList())
        assertEquals("# T\n\n## 小结\n\n正文**金句**\n\n> 引用\n", md)
    }
}
