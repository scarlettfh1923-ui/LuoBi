package com.scarlett.luobi

import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2：每一个内置提示词都要带防编造规则（底座一整块 + 各提示词一句） */
class NoFabTest {
    private val m = Prompts22.NO_FAB_MARK
    @Test fun baseHasBlock() { assertTrue(Prompts22.system().contains("【不编造｜")); assertTrue(Prompts22.NO_FAB_LINE.startsWith(m)) }
    @Test fun everyTypeModule() = Prompts22.TYPES.forEach { assertTrue(it.name, it.module.contains(m)) }
    @Test fun defaultsTemplates() = Defaults.templates.forEach { assertTrue(it.name, it.prompt.contains(m)) }
    @Test fun standalonePrompts() {
        val all = mapOf("INTENT" to Prompts22.INTENT, "XHS" to Prompts22.XHS, "continue" to Prompts22.continuePrompt("t", "b"),
            "CLEAN" to Defaults.CLEAN, "Summary" to Summary.SYSTEM, "FactCheck" to FactCheck.DEFAULT_PROMPT,
            "Inspire" to Inspire.DEFAULT_PROMPT, "Expand" to Inspire.expandPrompt(Topic("x", "微博")),
            "Extract" to FactPipeline.EXTRACT_SYS, "Judge" to FactPipeline.JUDGE_SYS, "Alt" to FactFix.ALT_SYS, "Style" to FactFix.STYLE_SYS)
        all.forEach { (k, v) -> assertTrue(k, v.contains(m) || v.contains("编造")) }
    }
    @Test fun composedPromptCarriesIt() {
        val t = Prompts22.TYPES[0]
        assertTrue(Compose.prompt(t.name, t.desc, t.module, mapOf("观点" to "x"), "素材", 1).contains(m))
    }
}
