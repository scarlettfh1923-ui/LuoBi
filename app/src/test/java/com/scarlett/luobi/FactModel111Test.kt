package com.scarlett.luobi

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.11.1：校对模型选择。
 * 复现 1.11.0 bug：prefer-different 会在已配置 OpenAI 时选中 gpt-5.5（即使用户以为选了 DeepSeek 检索）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class FactModel111Test {
    private lateinit var s: Settings
    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        s = Settings(ctx)
        // 清空
        s.factModel = ""; s.defaultModel = ""; s.factSearch = "deepseek"
        s.setKey("deepseek", "sk-ds-test"); s.setKey("openai", "sk-oai-test")
        s.setModel("deepseek", "deepseek-flash"); s.setModel("openai", "gpt-5.5")
    }

    @Test fun defaultFollowsWritingDeepseekNotGpt() {
        // 文章是 DeepSeek 写的；即使 OpenAI 也配了 Key，也绝不能挑 gpt-5.5
        val m = FactCheck.resolveCheckModel(s, "DeepSeek · deepseek-flash")
        assertEquals("p:deepseek", m.ref)
        assertEquals("deepseek-flash", m.sub)
    }

    @Test fun emptyFactModelUsesDefaultWritingModel() {
        s.defaultModel = "p:deepseek"
        val m = FactCheck.resolveCheckModel(s, "")
        assertEquals("p:deepseek", m.ref)
    }

    @Test fun explicitFactModelHonored() {
        s.factModel = "p:deepseek"
        val m = FactCheck.resolveCheckModel(s, "OpenAI · gpt-5.5") // 写作是 GPT 也尊重设定
        assertEquals("p:deepseek", m.ref)
    }

    @Test fun neverAutoPicksDifferentProvider() {
        // 1.11.0 的逻辑：firstOrNull { ref != writer && ref != deepseek } → 会拿到 openai
        // 新逻辑：写作展示串对得上 DeepSeek 就用 DeepSeek
        val m = FactCheck.resolveCheckModel(s, "DeepSeek · deepseek-flash")
        assertNotEquals("p:openai", m.ref)
        assertFalse(m.sub.contains("gpt"))
    }

    @Test fun refine403MentionsModel() {
        val err = ApiError(403, """{"error":{"message":"token not authorized for model gpt-5.5"}}""")
        val m = ModelRef("p:openai", "OpenAI", "gpt-5.5")
        val c = FactCheck.refineFactModelError(err, m)
        assertNotNull(c)
        assertTrue(c!!.hint.contains("gpt-5.5") || c.hint.contains("OpenAI"))
        assertTrue(c.hint.contains("设定"))
    }

    @Test fun errorsExplain403ModelUnauthorized() {
        val f = Errors.explain(ApiError(403, """{"error":{"message":"token not authorized for model gpt-5.5"}}"""))
        assertTrue(f.title.contains("校对模型") || f.detail.contains("gpt-5.5") || f.detail.contains("校对模型"))
        assertTrue(f.detail.contains("事实校对") || f.detail.contains("DeepSeek") || f.detail.contains("与写作相同"))
    }
}
