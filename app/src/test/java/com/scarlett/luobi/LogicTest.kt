package com.scarlett.luobi

import org.junit.Assert.*
import org.junit.Test

class VarsTest {
    private val tpl = """你是一名财经非虚构作者。
读者的疑问：{疑问}
我的观点：{观点}
要写成：{想写什么}
参考原文：{原文}"""

    @Test fun replacesValuesVerbatim() {
        val v = mapOf("疑问" to "  县城九块九的咖啡店怎么赚钱？\$1 {x} \\n ", "观点" to "品牌赚钱", "想写什么" to "财经非虚构", "原文" to "原文内容")
        val out = Vars.fill(tpl, v)
        assertTrue(out.contains("读者的疑问：  县城九块九的咖啡店怎么赚钱？\$1 {x} \\n "))
        assertTrue(out.contains("我的观点：品牌赚钱"))
        assertEquals(5, out.lines().size)
    }

    @Test fun emptyVariableDropsWholeLine() {
        val out = Vars.fill(tpl, mapOf("疑问" to "问题", "想写什么" to "", "原文" to "x"))
        assertFalse(out.contains("我的观点"))
        assertFalse(out.contains("要写成"))
        assertEquals(listOf("你是一名财经非虚构作者。", "读者的疑问：问题", "参考原文：x"), out.lines())
    }

    @Test fun blankOnlyValueCountsAsEmpty() {
        assertFalse(Vars.fill("A：{观点}\nB", mapOf("观点" to "   ")).contains("A："))
    }

    @Test fun lineWithTwoVarsDroppedIfAnyEmpty() {
        assertEquals("头", Vars.fill("头\n{疑问} / {观点}", mapOf("疑问" to "q")))
    }

    @Test fun unknownVariableLineDropped() {
        assertEquals("a", Vars.fill("a\n目标读者：{读者}", mapOf("疑问" to "q")))
    }

    @Test fun jsonBracesAreKept() {
        val t = "输出：{\"title\": \"标题\"}\n问：{疑问}"
        assertEquals("输出：{\"title\": \"标题\"}\n问：q", Vars.fill(t, mapOf("疑问" to "q")))
    }

    @Test fun missingVariableWarning() {
        val p = "读者的疑问：{疑问}\n目标读者：{读者}\n参考原文：{原文}"
        assertEquals(listOf("读者"), Vars.missing(p, Vars.DEFAULT_FIELDS))
        assertTrue(Vars.missing(p, Vars.DEFAULT_FIELDS + "读者").isEmpty())
        assertEquals(listOf("观点", "想写什么"), Vars.missing("{观点}{想写什么}", listOf("疑问")))
    }

    @Test fun referencedIsDistinctAndOrdered() {
        assertEquals(listOf("观点", "疑问"), Vars.referenced("{观点} {疑问} {观点}"))
    }

    @Test fun segmentsHighlightOnlyValues() {
        val segs = Vars.segments("问：{疑问}\n观点：{观点}", mapOf("疑问" to "Q"))
        assertEquals(listOf("问：" to false, "Q" to true), segs)
        assertEquals(listOf("观点"), Vars.emptyUsed("问：{疑问}\n观点：{观点}", mapOf("疑问" to "Q")))
    }

    @Test fun builtInTemplatesUseOnlyKnownVars() {
        Defaults.templates.forEach { assertTrue(it.name, Vars.missing(it.prompt, Vars.DEFAULT_FIELDS).isEmpty()) }
    }

    @Test fun cleanPromptFill() {
        val p = Vars.fillClean(Defaults.CLEAN, "嗯原文", "- 观点表达：x")
        assertTrue(p.endsWith("嗯原文")); assertTrue(p.contains("- 观点表达：x")); assertFalse(p.contains("{原始文本}"))
    }

    @Test fun finalPromptAddsLengthAndRaw() {
        val f = Ai.finalPrompt("问：{疑问}\n参考原文：{原文}", mapOf("疑问" to "Q"), "清洗后", attachRaw = false, length = 2)
        assertEquals("问：Q\n篇幅：长篇，正文字数控制在 1000–1800 字之间。", f)
        val g = Ai.finalPrompt("问：{疑问}\n参考原文：{原文}", mapOf("疑问" to "Q"), "清洗后", attachRaw = true, length = 0)
        assertTrue(g.contains("参考原文：清洗后"))
    }
}

class CleanParseTest {
    private val f = Vars.DEFAULT_FIELDS
    @Test fun plainJson() {
        val r = CleanParse.parse("""{"cleaned":"我们县城开了七八家店。","疑问":"怎么赚钱？","recommend":"财经非虚构"}""", f)
        assertEquals("我们县城开了七八家店。", r.cleaned)
        assertEquals(mapOf("疑问" to "怎么赚钱？"), r.values)
        assertEquals("财经非虚构", r.recommend)
    }
    @Test fun fencedWithThinkAndProse() {
        val r = CleanParse.parse("<think>想想</think>好的，结果如下：\n```json\n{\"cleaned\": \"文字\", \"观点\": \"品牌赚钱\", \"recommend\": \"「观点表达」\"}\n```\n以上。", f)
        assertEquals("文字", r.cleaned); assertEquals("品牌赚钱", r.values["观点"]); assertEquals("观点表达", r.recommend)
    }
    @Test fun dropsEmptyNullAndPlaceholders() {
        val r = CleanParse.parse("""{"cleaned":"x","疑问":"","观点":null,"想写什么":"……"}""", f)
        assertTrue(r.values.isEmpty())
    }
    @Test fun braceInsideStringAndTrailingText() {
        val r = CleanParse.parse("""前缀 {"cleaned":"含 } 括号 {的文字","疑问":"q"} 后缀 {乱}""", f)
        assertEquals("含 } 括号 {的文字", r.cleaned); assertEquals("q", r.values["疑问"])
    }
    @Test fun truncatedJsonRecovered() {
        val r = CleanParse.parse("""{"cleaned":"文字","疑问":"被截断的问""", f)
        assertEquals("文字", r.cleaned)
    }
    @Test fun customFieldsFollowCleanTemplate() {
        val r = CleanParse.parse("""{"cleaned":"x","读者":"宝妈","疑问":"q"}""", listOf("读者"))
        assertEquals(mapOf("读者" to "宝妈"), r.values)
    }
    @Test fun arrayValueJoined() {
        assertEquals("a；b", CleanParse.parse("""{"cleaned":"x","观点":["a","b"]}""", f).values["观点"])
    }
    @Test(expected = CleanParse.BadFormat::class) fun notJson() { CleanParse.parse("抱歉，我无法完成", f) }
    @Test(expected = CleanParse.BadFormat::class) fun missingCleaned() { CleanParse.parse("""{"疑问":"q"}""", f) }
    @Test fun recommendMatch() {
        val names = listOf("财经非虚构", "观点表达")
        assertEquals("观点表达", CleanParse.match("观点表达", names) { it })
        assertEquals("财经非虚构", CleanParse.match("财经非虚构模板", names) { it })
        assertNull(CleanParse.match("", names) { it })
    }
}

class ArticleExportTest {
    @Test fun articleJson() {
        val a = ArticleParse.parse("""{"title":"九块九","body":"## 小标题\n正文","tags":["#县城","咖啡"]}""", "fb")
        assertEquals("九块九", a.title); assertEquals(listOf("县城", "咖啡"), a.tags)
    }
    @Test fun articlePlainFallback() {
        val a = ArticleParse.parse("# 标题行\n\n第一段。\n\n#县城 #咖啡", "fb")
        assertEquals("标题行", a.title); assertEquals("第一段。", a.body); assertEquals(listOf("县城", "咖啡"), a.tags)
    }
    @Test fun markdownAndHtml() {
        val md = Export.markdown("T", "## 头\n段<落>", listOf("a"))
        assertTrue(md.startsWith("# T\n\n## 头\n\n段<落>")); assertTrue(md.trimEnd().endsWith("#a"))
        val h = Export.htmlDoc("T&", "## 头\n**粗**段", emptyList())
        assertTrue(h.contains("<title>T&amp;</title>")); assertTrue(h.contains(">头</h2>")); assertTrue(h.contains("<strong>粗</strong>段"))
    }
    @Test fun jsonTemplateEscapes() {
        assertEquals("""{"t":"a\"b","c":"x\ny"}""", Export.jsonTpl("""{"t":"{title}","c":"{body}"}""", "a\"b", "x\ny", emptyList()))
    }
}

class LengthsTest {
    @Test fun defaults() { assertEquals(listOf(150 to 300, 400 to 800, 1000 to 1800), Lengths.DEFAULT) }
    @Test fun validateOk() { assertNull(Lengths.validate("150", "300")); assertNull(Lengths.validate(" 400 ", "400")) }
    @Test fun validateBad() {
        assertEquals("请填正整数", Lengths.validate("", "300"))
        assertEquals("请填正整数", Lengths.validate("0", "300"))
        assertEquals("请填正整数", Lengths.validate("-5", "300"))
        assertEquals("请填正整数", Lengths.validate("1.5", "300"))
        assertEquals("最少不能大于最多", Lengths.validate("800", "400"))
    }
    @Test fun roundTrip() { val l = listOf(100 to 200, 300 to 600, 900 to 2000); assertEquals(l, Lengths.decode(Lengths.encode(l))) }
    @Test fun decodeBrokenFallsBack() {
        assertEquals(Lengths.DEFAULT, Lengths.decode("garbage"))
        assertEquals(Lengths.DEFAULT, Lengths.decode("300-100,400-800,1000-1800"))
        assertEquals(Lengths.DEFAULT, Lengths.decode("150-300"))
    }
    @Test fun hintText() { assertEquals("篇幅：中篇，正文字数控制在 400–800 字之间。", Lengths.hint(1, 400 to 800)) }
    @Test fun finalPromptAppendsRange() {
        val p = Ai.finalPrompt("写一篇关于{疑问}的文章\n观点：{观点}", mapOf("疑问" to "早起"), "原文", false, 0, 120 to 260)
        assertEquals("写一篇关于早起的文章\n篇幅：短篇，正文字数控制在 120–260 字之间。", p)
    }
}
