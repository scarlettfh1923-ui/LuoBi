package com.scarlett.luobi

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Inspire22Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    @After fun tearDown() { HotSources.fake = null }

    @Test fun heatFormatting() {
        assertEquals("", HotSources.heatOf(0)); assertEquals("124万", HotSources.heatOf(1_243_850)); assertEquals("1.2亿", HotSources.heatOf(123_000_000)); assertEquals("980", HotSources.heatOf(980))
        val w = HotSources.weibo("""{"data":{"realtime":[{"word":"A","num":2500000,"label_name":"热"}]}}""")
        assertEquals("250万", w[0].heat)
        assertEquals("1234万", HotSources.zhihu("""{"data":[{"detail_text":"1234 万热度","target":{"title":"Q"}}]}""")[0].heat)
        assertEquals("50万", HotSources.douyin("""{"word_list":[{"word":"D","hot_value":500000}]}""")[0].heat)
    }
    @Test fun agoText() {
        val now = 10_000_000_000L
        assertEquals("刚刚更新", Inspire.ago(now - 10_000, now)); assertEquals("5 分钟前更新", Inspire.ago(now - 5 * 60_000, now))
        assertEquals("2 小时前更新", Inspire.ago(now - 2 * 3_600_000, now))
    }
    @Test fun streamsPerSourceAndSkipsFailures() = runBlocking {
        HotSources.fake = { s -> when (s.id) {
            "weibo" -> """{"data":{"realtime":[{"word":"W1","num":10000}]}}"""
            "zhihu" -> throw java.io.IOException("timeout")
            "douyin" -> """{"word_list":[{"word":"D1","hot_value":1}]}"""
            else -> """{"data":{"cards":[{"content":[{"content":[{"word":"B1"}]}]}]}}""" } }
        val parts = mutableListOf<Int>()
        val r = Inspire.refresh(ctx) { parts += it.topics.size }
        assertTrue(parts.isNotEmpty()); assertEquals(parts.sorted(), parts)
        assertTrue(r.failed.contains("知乎热榜") || r.failed.any { it.contains("知乎") })
        assertTrue(r.topics.none { it.angle.isNotBlank() })   // 原始热榜不调 AI
        val c = Inspire.cached(ctx)!!; assertEquals(r.topics.map { it.title }, c.topics.map { it.title }); assertEquals("1万", c.topics.first { it.title == "W1" }.heat)
    }
    @Test fun cacheIsReturnedAnyDay() {
        Inspire.store(ctx, Inspire.Result(listOf(Topic("T", "微博", heat = "9万")), 1000L, emptyList()))
        assertEquals("9万", Inspire.cached(ctx)!!.topics[0].heat)
    }
    @Test fun expandParsesSmallJson() {
        val t = Topic("某地取消早高峰限行", "微博")
        val r = Inspire.parseExpand(t, """```json
{"angle":"限行取消后,通勤者的一天","view":"该先看数据再下结论","doubt":"堵车会回来吗?"}
```""")!!
        assertEquals("限行取消后，通勤者的一天", r.angle); assertEquals("堵车会回来吗？", r.doubt); assertEquals("微博", r.source)
        assertNull(Inspire.parseExpand(t, "不是 JSON")); assertTrue(Inspire.expandPrompt(t).contains(Prompts22.NO_FAB_MARK))
        assertTrue(Inspire.fillText(r).contains("限行取消后"))
    }
    @Test fun oldBiliSettingMapsToBaidu() {
        Settings(ctx).inspireSources = setOf("weibo", "bili")
        assertEquals(setOf("weibo", "baidu"), Settings(ctx).inspireSources)
    }
}
