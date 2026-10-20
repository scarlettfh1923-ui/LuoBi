package com.scarlett.luobi

import org.junit.Assert.*
import org.junit.Test

/** 1.4 回归：长段落里的变量为空 / 未知时，1.3 会把整段删掉，导致预览和发送的提示词缺段落 */
class TemplatePromptTest {
    private val p1 = "你是一位资深中文财经非虚构作者，长期观察房地产、城市经济、土地财政与公共议题。你的写作风格参考“包邮区/你包叔”这类公众号长文。"
    private val p2 = "围绕{观点}展开论证：先用一个具体场景开场，再用数据算账，最后落到对普通人的影响。每节都要有小标题，不要论文腔。"
    private val p3 = "如果读者会问{疑问}，要在第二节正面回答；引用数据时注明{数据来源}，拿不准的不要编造。"
    private val p4 = "结构：小切口开场 → 故事化叙事 → 夹叙夹议 → 历史纵深 → 数据算账 → 金句收束。全文避免空洞评论。"
    private val tpl = listOf(p1, "", p2, "", "我的观点：{观点}", "读者的疑问：{疑问}", "要写成：{想写什么}", "", p3, "", p4, "参考原文：{原文}").joinToString("\n")

    @Test fun everyParagraphSurvives_whenVarsEmptyOrUnknown() {
        // 只识别出「疑问」，观点 / 想写什么 为空；{数据来源} 不是清洗字段
        val out = Ai.finalPrompt(tpl, mapOf("疑问" to "县城房价还会跌吗"), "清洗后原文", attachRaw = true, length = 2, known = Vars.DEFAULT_FIELDS.toSet())
        assertTrue(out.contains(p1)); assertTrue(out.contains(p4))
        assertTrue("长段落里空变量只去占位", out.contains("围绕展开论证：先用一个具体场景开场"))
        assertTrue(out.contains("如果读者会问县城房价还会跌吗，要在第二节正面回答"))
        assertTrue("未知变量原样保留", out.contains("注明{数据来源}"))
        assertFalse("短标签行仍然去掉", out.contains("我的观点："))
        assertFalse(out.contains("要写成："))
        assertTrue(out.contains("读者的疑问：县城房价还会跌吗")); assertTrue(out.contains("参考原文：清洗后原文"))
        assertTrue(out.trimEnd().endsWith(Lengths.hint(2, Lengths.DEFAULT[2])))
    }

    // 她在设置里保存、但预览里缺掉的原文（逐字）
    private val hers = "一、任务\n创作的主题围绕{疑问}{观点}{想写什么}，你要写一篇完整的中文公众号长文。默认要求：\n篇幅：2000—5000字；如我指定则按指定篇幅。"

    @Test fun herSection_partialValues() {
        val out = Ai.finalPrompt(hers, mapOf("疑问" to "县城房价还会跌吗", "观点" to "限跌令托不住房价"), "c", false, 2)
        assertEquals("一、任务\n创作的主题围绕县城房价还会跌吗；限跌令托不住房价，你要写一篇完整的中文公众号长文。默认要求：\n篇幅：2000—5000字；如我指定则按指定篇幅。", out)
    }

    @Test fun herSection_noValues_sentenceKept_ownLengthWins() {
        val out = Ai.finalPrompt(hers, emptyMap(), "c", false, 2)
        assertTrue(out.contains("创作的主题围绕，你要写一篇完整的中文公众号长文。默认要求："))
        assertTrue(out.contains("篇幅：2000—5000字；如我指定则按指定篇幅。"))
        assertFalse("模板已写篇幅，不追加设置篇幅", out.contains("正文字数控制在"))
    }

    @Test fun herSection_allValues() {
        val out = Ai.finalPrompt(hers, mapOf("疑问" to "Q", "观点" to "V", "想写什么" to "W"), "c", false, 1)
        assertTrue(out.contains("创作的主题围绕Q；V；W，你要写一篇"))
        assertEquals(3, out.lines().size)
    }

    @Test fun labelOnlyLineStillOmittedWhenEmpty() {
        assertEquals("a", Vars.fill("a\n我的观点：{观点}", emptyMap()))
        assertEquals("a\n我的观点：V", Vars.fill("a\n我的观点：{观点}", mapOf("观点" to "V")))
    }

    @Test fun allFilled_noLoss() {
        val v = mapOf("疑问" to "Q", "观点" to "V", "想写什么" to "W")
        val out = Ai.finalPrompt(tpl, v, "R", true, 1)
        listOf(p1, p4, "我的观点：V", "要写成：W", "参考原文：R").forEach { assertTrue(it, out.contains(it)) }
        assertEquals(tpl.split("\n").size + 1, out.split("\n").size)
    }

    @Test fun previewMatchesFill() {
        val v = mapOf("疑问" to "Q")
        val segs = Vars.segments(tpl, v, Vars.defaultKnown).joinToString("") { it.first }
        assertEquals(Vars.fill(tpl, v), segs)
    }

    @Test fun labelLine() {
        assertTrue(Vars.isLabelLine("我的观点：{观点}")); assertFalse(Vars.isLabelLine(p2))
        assertFalse(Vars.isLabelLine("创作的主题围绕{疑问}{观点}{想写什么}，你要写一篇完整的中文公众号长文。默认要求："))
    }

    @Test fun uniqueNamesAndPreferMine() {
        assertEquals("财经非虚构 2", Names.unique("财经非虚构", listOf("财经非虚构")))
        assertEquals("新模板 3", Names.unique("新模板", listOf("新模板", "新模板 2")))
        data class T(val name: String, val builtIn: Boolean)
        val l = listOf(T("财经非虚构", true), T("财经非虚构", false))
        assertFalse(CleanParse.matchPrefer("财经非虚构", l, { it.name }, { !it.builtIn })!!.builtIn)
        assertTrue(CleanParse.matchPrefer("「财经非虚构」", listOf(T("财经非虚构", true)), { it.name }, { !it.builtIn })!!.builtIn)
    }

    @Test fun sectionNumbers() {
        assertEquals(1, Md.sectionNo("1")); assertEquals(2, Md.sectionNo("02")); assertEquals(3, Md.sectionNo("三")); assertEquals(1, Md.sectionNo("**1.**"))
        assertEquals(12, Md.sectionNo("十二")); assertNull(Md.sectionNo("1 土地财政")); assertNull(Md.sectionNo("限跌令"))
    }
}

class GoldenSentenceTest {
    private val g = "卖地是收租，炒股是博弈。收租的躺赢，博弈的未必。"
    @Test fun boldParagraphIsBodyNotHeading() {
        val ml = Md.line("**$g**", 20)
        assertEquals(MdKind.P, ml.kind); assertEquals(listOf(g to true), Md.spans(ml.text))
    }
    @Test fun hashSentenceDemoted() {
        assertEquals(MdKind.P, Md.line("## $g", 20).kind)
        assertEquals(MdKind.P, Md.line("### $g", 20).kind)
        assertEquals(MdKind.H2, Md.line("## 合肥的运气，不是谁都有", 20).kind)
        assertEquals(MdKind.H2, Md.line("## 土地财政的终局", 20).kind)
    }
    @Test fun shortBoldStillHeading() { assertEquals(MdKind.H2, Md.line("**土地财政的终局**", 20).kind) }
    @Test fun exportsKeepBodySize() {
        val body = "第一段。\n\n**$g**\n\n第三段。"
        val html = Export.htmlBody(body, emptyList())
        assertFalse(html.contains("<h2")); assertTrue(html.contains("<strong>$g</strong>"))
        assertTrue(Export.markdown("t", body, emptyList()).contains("**$g**"))
    }
}
