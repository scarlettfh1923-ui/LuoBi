package com.scarlett.luobi

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Fact119Test {
    private val p0 = FactNet.probe
    @After fun r() { FactNet.probe = p0 }

    private fun claimJson(span: String) = """{"claims":[{"span":"$span","claim":"x","details":[{"aspect":"人物","text":"$span","type":"可核实事实","entities":["丁玲"],"queries":["丁玲 1928"]}]}]}"""

    @Test fun longBodyIsChunkedAndRenumbered() = runBlocking {
        val paras = (1..6).map { i -> "丁玲第${i}段。" + "字".repeat(900) + "\n" }
        val body = paras.joinToString("")
        val parts = FactPipeline.chunks(body)
        assertTrue(parts.size >= 3); assertTrue(parts.all { it.length <= FactPipeline.CHUNK })
        assertEquals(body, parts.joinToString(""))
        var calls = 0
        val env = FactPipeline.Env({ _, u, _ -> calls++; val n = Regex("丁玲第(\\d)段").find(u)!!.groupValues[1]; claimJson("丁玲第${n}段") }, emptyList())
        val cs = FactPipeline.extractAll("t", body, env)
        assertEquals(parts.size, calls)
        assertEquals(cs.indices.map { "c${it + 1}" }, cs.map { it.id })
        assertEquals("c2.d1", cs[1].details[0].id)
    }

    @Test fun failingChunkRetriedOnceThenPartial() = runBlocking {
        val body = "丁玲甲。" + "字".repeat(2600) + "\n丁玲乙。" + "字".repeat(100)
        var bad = 0
        val env = FactPipeline.Env({ _, u, _ -> if (u.contains("丁玲甲")) { bad++; "不是 JSON" } else claimJson("丁玲乙") }, emptyList())
        val cs = FactPipeline.extractAll("t", body, env)
        assertEquals(2, bad); assertTrue(cs.isNotEmpty() && cs.none { it.span.contains("甲") })
        assertTrue(env.status.summary().contains("没能拆出"))
    }

    @Test fun claimRetriesThenMarkedUnfinished() = runBlocking {
        var judge = 0
        val env = FactPipeline.Env({ _, _, j -> if (!j) claimJson("丁玲在上海") else { judge++; throw java.io.IOException("断网") } }, emptyList())
        val r = FactPipeline.run("t", "丁玲在上海", env)
        assertEquals(3, judge)
        val d = r.claims.first().details.first()
        assertEquals(FactPipeline.UNK, d.verdict); assertTrue(d.reason.startsWith(FactPipeline.UNDONE))
        assertTrue(r.status.contains("没查完"))
    }

    @Test fun wikiSkippedWhenUnreachable() {
        val chain = listOf(FactQuotes.Src("Kimi 搜索", false, { emptyList() }), FactQuotes.Src("中文维基", false, { emptyList() }), FactQuotes.Src("英文维基", true, { emptyList() }))
        val st = FactPipeline.SourceStatus()
        FactNet.probe = { _, _ -> false }
        assertEquals(listOf("Kimi 搜索"), FactPipeline.wikiGate(chain, st).map { it.label })
        assertTrue(st.summary().contains("中文维基 连不上（已跳过）"))
        FactNet.probe = { _, _ -> true }
        assertEquals(3, FactPipeline.wikiGate(chain, FactPipeline.SourceStatus()).size)
    }

    @Test fun sourcesQueriedConcurrentlyAndBadKeyDisablesSource() = runBlocking {
        val st = FactPipeline.SourceStatus()
        var badCalls = 0
        val slow = { _: String -> Thread.sleep(400); listOf(FactHit("丁玲传", "https://a/1", "丁玲1928年在杭州", "网页", "A")) }
        val chain = listOf(FactQuotes.Src("A", false, slow), FactQuotes.Src("B", false, slow),
            FactQuotes.Src("C", false, { badCalls++; throw FactNet.Fail(401, "x") }))
        val env = FactPipeline.Env({ _, _, _ -> "" }, chain, status = st)
        val d = FactDetail("c1.d1", "人物", "丁玲", FactPipeline.T_FACT, listOf("丁玲"), listOf("丁玲 1928"))
        val t = System.currentTimeMillis()
        val ev = FactPipeline.search(d, listOf("丁玲 1928", "丁玲 杭州"), env, mutableSetOf())
        assertTrue("应并发：${System.currentTimeMillis() - t}", System.currentTimeMillis() - t < 1500)
        assertEquals(1, ev.size)
        assertEquals(1, badCalls)
        assertTrue(st.summary(), st.summary().contains("C Key 错") && st.summary().contains("A 成功"))
    }

    @Test fun statusClassification() {
        assertEquals("限流", FactPipeline.SourceStatus.classify(FactNet.Fail(429, "")))
        assertEquals("余额不足", FactPipeline.SourceStatus.classify(FactNet.Fail(402, "")))
        assertEquals("超时", FactPipeline.SourceStatus.classify(java.net.SocketTimeoutException()))
        assertEquals("连不上", FactPipeline.SourceStatus.classify(java.net.UnknownHostException()))
        assertEquals(8000, FactNet.TIMEOUT_MS)
    }

    @Test fun gbkPagesDecoded() {
        val txt = "丁玲与胡也频在北平"
        val gbk = ("<html><head><meta charset=\"gbk\"></head><body>$txt</body></html>").toByteArray(charset("GBK"))
        assertTrue(FactNet.decode(gbk, "text/html").contains(txt))
        assertTrue(FactNet.decode(txt.toByteArray(charset("GB2312")), "text/html; charset=GB2312").contains(txt))
        assertTrue(FactNet.decode(txt.toByteArray(charset("GBK")), "").contains(txt))
        assertEquals(txt, FactNet.decode(txt.toByteArray(), ""))
    }

    @Test fun fuzzyQuoteAcceptedWithNote() {
        val text = "1928年6月28日，南京国民政府将北京改名为北平，直到1949年。"
        assertTrue(FactPipeline.quoteNear("1928年6月28日南京国民政府把北京改名为北平", text))
        assertFalse(FactPipeline.quoteNear("鲁迅在上海写了狂人日记一书", text))
        val c = FactClaim("c1", "北平", "北平", listOf(FactDetail("c1.d1", "地名", "北平", FactPipeline.T_FACT, listOf("北平"), emptyList())))
        val ev = mapOf("c1.d1" to listOf(FactPipeline.Evidence("E1", "t", "https://x", "v", text)))
        val r = FactPipeline.applyJudge(c, """{"details":[{"id":"c1.d1","verdict":"已印证","evidence":"E1","quote":"1928年6月28日南京国民政府把北京改名为北平","reason":"对"}]}""", ev)
        assertEquals(FactPipeline.OK, r.details[0].verdict); assertTrue(r.details[0].reason.endsWith(FactPipeline.FUZZY_NOTE))
    }

    @Test fun aliasesMatchForeignNameParts() {
        assertTrue(FactPipeline.relevant("埃利亚斯认为文明进程……", listOf("诺贝特·埃利亚斯")).isNotEmpty())
    }

    @Test fun preflightOnlyBlocksKeyProblems() {
        assertNotNull(FactPreflight.verdict(401, "DeepSeek")); assertNotNull(FactPreflight.verdict(402, "DeepSeek"))
        assertNull(FactPreflight.verdict(-1, "x")); assertNull(FactPreflight.verdict(404, "x")); assertNull(FactPreflight.verdict(200, "x"))
    }

    @Test fun recheckReplacesStuckWork() {
        assertEquals(ExistingWorkPolicy.REPLACE, FactWorker.policy(true))
        assertEquals(ExistingWorkPolicy.KEEP, FactWorker.policy(false))
    }

    @Test fun reportStatusRoundTrips() {
        val r = FactPipeline.report(emptyList(), listOf("Kimi 搜索"), "", "Kimi 搜索 成功")
        assertEquals("Kimi 搜索 成功", FactCheck.decodeReport(FactCheck.encodeReport(r))!!.status)
    }
}
