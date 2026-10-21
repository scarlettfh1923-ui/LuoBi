package com.scarlett.luobi

import org.junit.Assert.*
import org.junit.Test

/** 1.12：校对结果缓存、手改建议、勾选应用后保留未选项 */
class FactCheck112Test {
    private fun sample(): FactReport {
        val a = FactIssue("1", "王家卫执导", "导演", "高", "王家卫执导", "由陈可辛执导", "维基百科", note = "本片导演是陈可辛", selected = true, aiCorrection = "由陈可辛执导")
        val b = FactIssue("2", "梁朝伟主演", "主演", "中", "梁朝伟主演", "黎明主演", "维基百科", note = "男主是黎明", selected = true, aiCorrection = "黎明主演")
        return FactReport(listOf(a, b), listOf("最动人"), "检索：DeepSeek", listOf("甜蜜蜜"), checkedAt = 42L)
    }

    @Test fun encodeDecodeRoundTripPreservesEditsAndChecks() {
        val r = sample()
        r.issues[1].selected = false
        val edited = r.copy(issues = listOf(
            r.issues[0].copy(correction = "由陈可辛导演"),
            r.issues[1].copy(selected = false),
        ))
        val json = FactCheck.encodeReport(edited)
        val back = FactCheck.decodeReport(json)!!
        assertEquals(42L, back.checkedAt)
        assertEquals("由陈可辛导演", back.issues[0].correction)
        assertEquals("由陈可辛执导", back.issues[0].aiCorrection)
        assertFalse(back.issues[1].selected)
        assertEquals(listOf("最动人"), back.skipped)
        assertEquals(listOf("甜蜜蜜"), back.queries)
        assertEquals(2, back.pendingCount())
    }

    @Test fun emptyJsonIsNull() {
        assertNull(FactCheck.decodeReport(""))
        assertNull(FactCheck.decodeReport("   "))
    }

    @Test fun afterApplyKeepsUncheckedAndMarksApplied() {
        val r = sample()
        val next = FactCheck.afterApply(r, setOf("1"))
        assertTrue(next.issues[0].applied)
        assertFalse(next.issues[0].selected)
        assertFalse(next.issues[1].applied)
        assertTrue(next.issues[1].selected)
        assertEquals(1, next.pendingCount())
    }

    @Test fun applyUsesEditedCorrectionAndSkipsApplied() {
        val body = "影片由王家卫执导，梁朝伟主演。"
        val a = FactIssue("1", "王家卫执导", "导演", "高", "", "陈可辛执导", "", selected = true, aiCorrection = "由陈可辛执导")
        val b = FactIssue("2", "梁朝伟主演", "主演", "中", "", "黎明主演", "", selected = true, applied = true)
        assertEquals("影片由陈可辛执导，梁朝伟主演。", FactCheck.applyFixes(body, listOf(a, b)))
    }

    @Test fun pendingCountIgnoresApplied() {
        val r = FactCheck.afterApply(sample(), setOf("1", "2"))
        assertEquals(0, r.pendingCount())
    }
}
