package com.scarlett.luobi

import org.junit.Assert.*
import org.junit.Test

/** 1.10 灵感：热榜解析（2026-10-21 实测返回结构的精简样本）、模型回复解析、提示词拼装 */
class Inspire110Test {
    @Test fun weibo() {
        val l = HotSources.weibo("""{"ok":1,"data":{"realtime":[{"word":"宝马员工争着离职挤崩系统","label_name":"热"},{"word":"广告","is_ad":1},{"word":""}]}}""")
        assertEquals(listOf("宝马员工争着离职挤崩系统"), l.map { it.title }); assertEquals("微博", l[0].source)
    }
    @Test fun zhihu() {
        val l = HotSources.zhihu("""{"data":[{"type":"hot_list_feed","target":{"title":"怎么看 DeepSeek？","excerpt":"单卡每小时收入"}},{"target":{}}]}""")
        assertEquals(1, l.size); assertEquals("怎么看 DeepSeek？", l[0].title)
    }
    @Test fun douyin() { assertEquals("日美大规模军演引发多地抗议", HotSources.douyin("""{"status_code":0,"word_list":[{"word":"日美大规模军演引发多地抗议","hot_value":1}]}""")[0].title) }
    @Test fun bili() { assertEquals("热梗是否会对中文表达造成负面影响", HotSources.bili("""{"code":0,"data":{"trending":{"list":[{"keyword":"k","show_name":"热梗是否会对中文表达造成负面影响"}]}}}""")[0].title) }
    @Test fun badSourceJsonThrowsSoFetchSkipsIt() { assertTrue(runCatching { HotSources.weibo("<html>403</html>") }.isFailure) }

    @Test fun parseReplyFormats() {
        val a = Inspire.parse("""```json
{"topics":[{"title":"T1","source":"微博","angle":"A","view":"V"},{"title":"T1","source":"x"},{"标题":"T2","来源":"知乎","可以写":"A2","观点":"V2"}]}
```""")
        assertEquals(listOf("T1", "T2"), a.map { it.title }); assertEquals("A2", a[1].angle); assertEquals("V2", a[1].view)
        assertEquals(1, Inspire.parse("""[{"title":"X","source":"B站"}]""").size)
        assertTrue(Inspire.parse("抱歉").isEmpty())
        assertEquals(10, Inspire.parse("""{"topics":[""" + (1..15).joinToString(",") { """{"title":"t$it"}""" } + "]}").size)
    }
    @Test fun promptFilled() {
        val p = Inspire.buildPrompt(Inspire.DEFAULT_PROMPT, listOf(HotItem("热点A", "微博", "补充")), emptyList(), listOf(Template(name = "财经非虚构", desc = "讲经济", prompt = "", builtIn = false)))
        assertTrue(p.contains("微博｜热点A｜补充")); assertTrue(p.contains("（还没有文章）")); assertTrue(p.contains("- 财经非虚构：讲经济"))
        assertFalse(p.contains("{热榜}") || p.contains("{我的文章}") || p.contains("{我的模板}"))
    }
    @Test fun interleaveCoversSources() {
        val l = Inspire.interleave(listOf(HotItem("w1", "微博"), HotItem("w2", "微博"), HotItem("z1", "知乎")))
        assertEquals(listOf("w1", "z1", "w2"), l.map { it.title })
    }
    @Test fun cacheRoundTrip() {
        val r = Inspire.Result(listOf(Topic("t", "微博", "a", "v")), 5L, listOf("抖音热榜"), "n")
        assertEquals(r, Inspire.decode(Inspire.encode(r)))
    }
    @Test fun fillText() { 
        assertEquals("我想写：t，a\n我的观点：v", Inspire.fillText(Topic("t", "微博", "a", "v")))
        assertEquals("我想写：t\n我的观点：v\n我的疑惑：d", Inspire.fillText(Topic("t", "知乎", "", "v", "d")))
        assertEquals("我想写：聊聊t的事", Inspire.fillText(Topic("t", "知乎", "聊聊t的事")))
        val all = Inspire.fillText(Topic("减负", "知乎", "角度", "观点", "疑惑"))
        assertFalse(all.contains("话题")); assertFalse(all.contains("知乎")) }
}
