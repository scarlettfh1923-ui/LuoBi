package com.scarlett.luobi

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FactPipeline117Test {
    private val t0 = FactNet.transport; private val s0 = FactNet.sleep; private val g0 = FactNet.gapMs
    @Before fun s() = FactFixture.install()
    @After fun r() { FactNet.transport = t0; FactNet.sleep = s0; FactNet.gapMs = g0; Yuanbao.resetSession() }

    private fun run() = runBlocking { FactPipeline.run(FactFixture.title, FactFixture.body, FactFixture.env()) }
    private fun FactReport.detail(aspect: String) = claims.flatMap { it.details }.first { it.aspect == aspect }

    @Test fun claimsSplitIntoAtomicDetailsWithImplicitChecks() {
        val r = run()
        val c1 = r.claims.first { it.span.startsWith("1920年代") }
        assertTrue(c1.details.map { it.aspect }.toString(), c1.details.map { it.aspect }.containsAll(listOf("人物", "地点", "范围", "地名")))
        assertTrue("民国初年 口径检查", r.claims.flatMap { it.details }.any { it.aspect == "时间口径" })
        assertEquals(1, FactFixture.extractCalls)
    }

    @Test fun implicitBeipingNotAddedWithoutEarlyYears() {
        assertTrue(FactPipeline.implicit("t", "1935年，他在北平教书。", emptyList()).none { c -> c.details.any { it.aspect == "地名" } })
        assertTrue(FactPipeline.implicit("t", "1926年，他在北平教书。", emptyList()).any { c -> c.details.any { it.aspect == "地名" } })
    }

    @Test fun queriesAreEntitySpecificNeverTopic() {
        run()
        assertTrue(FactFixture.queries.isNotEmpty())
        assertFalse(FactFixture.queries.toString(), FactFixture.queries.any { it.contains("开放") || it.contains("保守") })
        val ents = listOf("丁玲", "胡也频", "冯雪峰", "北平", "北京", "埃利亚斯", "Elias", "北平文人圈", "民国")
        FactFixture.queries.forEach { q -> assertTrue("查询没有实体：$q", ents.any { q.contains(it) }) }
    }

    @Test fun genericEvidenceIsFilteredOut() {
        run()
        val judges = FactFixture.prompts.filter { it.contains("逐个细节裁决") }
        assertTrue(judges.isNotEmpty())
        judges.forEach { assertFalse(it.contains(FactFixture.GENERIC_TEXT)); assertFalse(it.contains(FactFixture.GENERIC)) }
        assertTrue(judges.any { it.contains("南京國民政府改北京為北平") })
    }

    @Test fun beipingFlaggedPartialWithQuoteAndUrl() {
        val d = run().detail("地名")
        assertEquals(FactPipeline.PART, d.verdict)
        assertEquals(FactFixture.GOV, d.url)
        assertTrue(d.quote.contains("1928年6月28日"))
        assertTrue(d.rewrite.isNotBlank())
    }

    @Test fun eliasAttributionPartialOnMeaning() {
        val d = run().detail("引述归属")
        assertEquals(FactPipeline.PART, d.verdict)
        assertEquals(FactFixture.SAGE, d.url)
        assertTrue(d.reason.contains("规范越密集"))
    }

    @Test fun opinionNotCheckedAndRhetoricWithoutSource() {
        val r = run()
        assertEquals(FactPipeline.OPN, r.detail("作者引申").verdict)
        assertEquals(FactPipeline.RHET, r.detail("范围").verdict)
        assertEquals(FactPipeline.OK, r.detail("人物").verdict)
    }

    @Test fun fabricatedQuoteDowngradedAfterSecondRoundListsQueries() {
        val d = run().detail("时间口径")
        assertEquals(FactPipeline.UNK, d.verdict)
        assertTrue(d.tried.toString(), d.tried.size > d.queries.size)
        assertTrue(d.reason, d.reason.contains("找不到"))
    }

    @Test fun sourcesUsedAndReportRoundTrips() {
        val r = run()
        assertEquals(listOf("元宝搜索"), r.sources)
        val back = FactCheck.decodeReport(FactCheck.encodeReport(r))!!
        assertEquals(r.claims, back.claims)
        assertEquals(r.sources, back.sources)
        val legacy = FactCheck.decodeReport("""{"issues":[{"id":"a","span":"x","claim":"y","verdict":"有出入","correction":"z"}]}""")!!
        assertEquals(1, FactPipeline.claimsOf(legacy).single().details.size)
    }

    @Test fun resumeSkipsFinishedClaims() = runBlocking {
        val states = mutableListOf<FactPipeline.State>()
        FactPipeline.run(FactFixture.title, FactFixture.body, FactFixture.env(), save = { states += it })
        val partial = states.first { s -> s.claims.count { it.done } == 1 }
        FactFixture.reset()
        val r = FactPipeline.run(FactFixture.title, FactFixture.body, FactFixture.env(), resume = partial)
        assertEquals(0, FactFixture.extractCalls)
        assertTrue(r.claims.all { it.done })
        val doneIds = partial.claims.filter { it.done }.flatMap { c -> c.details.map { it.id } }
        assertTrue(FactFixture.prompts.none { p -> doneIds.any { p.contains("[$it]") } })
    }

    @Test fun applyRewriteReplacesSpan() {
        val r = run()
        val sel = r.issues.filter { it.id == r.detail("地点").id }
        val out = FactCheck.applyFixes(FactFixture.body, sel)
        assertTrue(out.contains("始于北京、在杭州达到顶点"))
    }
}
