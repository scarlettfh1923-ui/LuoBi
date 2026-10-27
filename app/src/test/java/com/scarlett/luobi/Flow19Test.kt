package com.scarlett.luobi

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.9：模板头迁移、最终提示词顺序、她的正文一个字不丢 */
class TplHeader19Test {
    // 她手机上的自定义正文（含 {疑问}{观点}{想写什么} 连写、空行、全角标点、行尾空格、CRLF 之外的各种字符）
    private val hers = "一、任务\n创作的主题围绕{疑问}{观点}{想写什么}，你要写一篇财经非虚构。  \n\n我的观点：{观点}\n二、要求\n1. 不编造数据；\n篇幅：2000—5000字\n参考原文：{原文}\n"

    @Test fun migrationKeepsBodyByteIdentical() {
        val m = TplHeader.migrate("财经非虚构", hers)
        assertTrue(m.startsWith("【你是谁】\n你是一名财经非虚构作者"))
        assertTrue(m.contains("【用户的意图】\n想写什么：{想写什么}\n我的观点：{观点}\n我的疑惑：{疑问}\n\n"))
        assertEquals(hers, m.removePrefix(TplHeader.header("财经非虚构")))
        assertArrayEquals(hers.toByteArray(), TplHeader.split(m)!!.second.toByteArray())
    }
    @Test fun migrationIdempotent() {
        val once = TplHeader.migrate("观点表达", hers); val twice = TplHeader.migrate("观点表达", once)
        assertEquals(once, twice)
        assertEquals(1, Regex("【你是谁】").findAll(twice).count())
    }
    @Test fun previewKeepsAllHerText() {
        val tpl = TplHeader.migrate("财经非虚构", hers)
        val out = Compose.prompt("财经非虚构", "desc", tpl, mapOf("想写什么" to "W"), "口播原话", 1)
        // 她正文的每一行都在（空占位只去掉占位本身，行保留——包括「我的观点：」这种短行）
        listOf("一、任务", "创作的主题围绕W，你要写一篇财经非虚构。  ", "我的观点：", "二、要求", "1. 不编造数据；", "篇幅：2000—5000字", "参考原文：口播原话")
            .forEach { assertTrue(it, out.lines().contains(it)) }
        // 2.2：意图单独一段（空的省略），模板头里的意图段不再重复
        assertTrue(out.contains("想写什么：W")); assertFalse(out.contains("我的疑惑：")); assertFalse(out.contains("【用户的意图】"))
        val idx = listOf("【这篇要表达什么】", "【篇幅】", "<<<\n口播原话\n>>>", "【这类文章怎么写】", "一、任务").map { out.indexOf(it) }
        assertEquals(idx.sorted(), idx); assertTrue(idx.all { it >= 0 })
        assertTrue(out.contains("以这里为准"))   // 模板自带篇幅时，以设置为准并说明
    }
    @Test fun allIntentEmptyDropsIntentBlock() {
        val out = Compose.prompt("x", "", TplHeader.migrate("x", "正文"), emptyMap(), "r", 0)
        assertFalse(out.contains("【这篇要表达什么】")); assertTrue(out.contains("【这类文章怎么写】")); assertTrue(out.contains("正文"))
    }
    @Test fun noHeaderTemplateGetsBuilderSections() {   // 2.2 段落顺序：意图 → 篇幅 → 素材 → 写法 → 自检
        val out = Compose.prompt("答疑解惑", "", "我的看法：{观点}\n要求：讲明白", mapOf("想写什么" to "W", "观点" to ""), "r", 0)
        val idx = listOf("【这篇要表达什么】", "【篇幅】", "【素材", "【这类文章怎么写】", "【交稿前").map { out.indexOf(it) }
        assertEquals(idx.sorted(), idx); assertTrue(idx.all { it >= 0 })
        assertTrue(out.lines().contains("我的看法：")); assertTrue(out.contains("短篇"))
    }
    @Test fun builtinsHaveHeaderAndKnownVars() {   // 2.2：8 个类型模块，不再带旧的模板头
        assertEquals(8, Defaults.templates.size)
        assertEquals(Prompts22.TYPES.map { it.name }, Defaults.templates.map { it.name })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class MigrateDb19Test {
    @Test fun dbMigrationOnceAndTwice() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java).allowMainThreadQueries().build()
        val body = "我自己的模板\n读者：{疑问}\n结尾要有力。"
        db.templates().insert(Template(name = "财经非虚构", desc = "", builtIn = true, sort = 0, prompt = body))
        db.templates().insert(Template(name = "我的", desc = "", builtIn = false, sort = 9, prompt = TplHeader.migrate("我的", "已经有头")))
        assertEquals(1, Defaults.migrateHeaders(db))
        val after = db.templates().list().associate { it.name to it.prompt }
        assertEquals(body, TplHeader.split(after["财经非虚构"]!!)!!.second)
        assertEquals(0, Defaults.migrateHeaders(db))
        assertEquals(after, db.templates().list().associate { it.name to it.prompt })
    }
    @Test fun writeStateSurvivesRecreate() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = WriteState(ctx); a.raw = "口播"; a.values["想写什么"] = "W"; a.extractedFor = "口播"; a.templateId = 3; a.sheet = true; a.save()
        val b = WriteState(ctx)
        assertEquals("口播", b.raw); assertEquals("W", b.values["想写什么"]); assertTrue(b.extracted); assertEquals(3L, b.templateId); assertTrue(b.sheet)
        b.raw = "改了"; assertFalse(b.extracted)
    }
}
