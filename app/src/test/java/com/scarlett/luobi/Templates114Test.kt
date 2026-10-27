package com.scarlett.luobi

import org.junit.Assert.*
import org.junit.Test

class Templates114Test {
    private fun her(k: String) = javaClass.classLoader!!.getResourceAsStream("tpl/$k.txt")!!.readBytes().toString(Charsets.UTF_8)
    private val map = linkedMapOf("财经非虚构" to "t87u", "豆瓣影评" to "t85u", "专栏评论" to "t86u", "通用非虚构" to "t88u", "小红书风格" to "t89u")
    private val TAG = "每篇文章另给 4–5 个主题标签，放在输出 JSON 的 tags 数组里（不带 #，每个不超过 8 个字），不要写进正文。"

    @Test fun defaultsEqualHerTextPlusAllowedAdditions() {
        // 2.2：内置换成 8 个类型；1.14 的出厂文本仍保留，用来判断她有没有改过（迁移用）
        assertEquals(Prompts22.TYPES.map { it.name }, Defaults.templates.sortedBy { it.sort }.map { it.name })
        assertEquals(TAG, DefaultTemplates114.TAG_LINE)
        map.forEach { (name, k) ->
            val expected = (if (k == "t88u") "【你是谁】\n" else "") + her(k) + "\n" + TAG
            val actual = DefaultTemplates114.prompt(name)!!
            assertArrayEquals(name, expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
            assertTrue(actual.endsWith("\n$TAG")); assertTrue(Defaults.factoryTexts(name).contains(expected.trim()))
        }
        assertFalse(her("t88u").startsWith("【你是谁】"))
    }
    @Test fun oldNamesRestoreToNewDefaults() {
        assertEquals(Defaults.builtinPrompt("豆瓣影评"), Defaults.builtinPrompt("口播转文章"))
        assertEquals(Defaults.builtinPrompt("专栏评论"), Defaults.builtinPrompt("观点表达"))
        assertEquals(Defaults.builtinPrompt("通用非虚构"), Defaults.builtinPrompt("答疑解惑"))
        assertEquals("豆瓣影评", Defaults.builtinName("口播转文章"))
        assertNull(Defaults.builtinPrompt("我自己的模板"))
    }
    @Test fun substitutionFillsHerPlaceholdersAndOmitsEmpty() {
        map.keys.forEach { name ->
            val tpl = DefaultTemplates114.prompt(name)!!
            assertEquals(setOf("想写什么", "观点", "疑问"), Vars.referenced(tpl).toSet())
            val full = Compose.prompt(name, "", tpl, mapOf("想写什么" to "WWW", "观点" to "VVV", "疑问" to "QQQ"), "口播", 1)
            listOf("WWW", "VVV", "QQQ").forEach { assertTrue("$name $it", full.contains(it)) }
            assertFalse(name, Regex("\\{(想写什么|观点|疑问)\\}").containsMatchIn(full))
            assertTrue(full.contains("【篇幅】"))
            val part = Compose.prompt(name, "", tpl, mapOf("想写什么" to "WWW", "观点" to "", "疑问" to ""), "口播", 1)
            assertTrue(part.contains("WWW"))
            assertFalse(name, Regex("(他的|用户的)(观点|疑惑)：\\s*$", RegexOption.MULTILINE).containsMatchIn(part))
            assertFalse(part.contains("{观点}") || part.contains("{疑问}"))
            val none = Compose.prompt(name, "", tpl, emptyMap(), "口播", 1)
            assertFalse(name, none.contains("【用户的意图】"))
            assertTrue(none.contains("【篇幅】"))
        }
    }
    @Test fun outRuleAsksFourToFiveTags() { assertTrue(Ai.OUT_RULE.contains("tags 为 4–5 个")) }
    @Test fun tagParsingOnSampleOutput() {
        val out = """{"title":"甜蜜蜜：漂泊比爱情更动人","body":"## 开头\n\n正文一段。","tags":["影评","爱情观","香港电影","漂泊"]}"""
        val a = ArticleParse.parse(out, "x")
        assertEquals(listOf("影评", "爱情观", "香港电影", "漂泊"), a.tags)
        assertFalse(a.body.contains("#影评") || a.body.contains("爱情观"))
        val n = Note(title = a.title, body = a.body, tags = a.tags.joinToString(" "))
        assertEquals("#影评 #爱情观 #香港电影 #漂泊", n.tagList().joinToString(" ") { "#$it" })
        assertEquals(listOf("影评", "爱情观"), ArticleParse.parse("""{"title":"t","body":"b","tags":["#影评","#爱情观"]}""", "x").tags)
    }
}
