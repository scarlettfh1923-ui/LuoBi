package com.scarlett.luobi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 2.4 技能包：解析 / 导入 / 成稿拆分 / 会话 / 检索回退 / 整条流水线（假网络） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class Skill24Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val origT = FactNet.transport

    @Before fun setUp() {
        File(ctx.filesDir, "skills").deleteRecursively(); SkillFlow.clear(ctx)
        ctx.getSharedPreferences("refs", 0).edit().clear().commit(); GenCard.clear(ctx)
        Settings(ctx).wsaKey = ""; Settings(ctx).setKey("deepseek", "")
    }
    @After fun tearDown() { FactNet.transport = origT; Ai.chatTransport = null }

    private val body = "# 我的写作技能\n\n" + "你是一名严谨的写作者。先检索、再立角度、再写作。".repeat(12)
    private fun skillMd(name: String = "demo-skill", extra: String = "") = "---\nname: $name\ndescription: >\n  一个演示技能包。\n  第二行。\nversion: 0.3.1\n$extra---\n$body"
    private fun zip(vararg e: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bo -> ZipOutputStream(bo).use { z -> e.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } } }.toByteArray()
    private fun err(f: () -> Unit): String = try { f(); fail("应该报错"); "" } catch (e: SkillImportError) { e.text }

    // ---- frontmatter ----
    @Test fun frontmatterBlockScalarsQuotesAndBom() {
        val (fm, b) = SkillParse.frontmatter("\uFEFF---\r\nname: \"a-b\"\r\ndescription: >\r\n  第一行\r\n  第二行\r\nversion: 1.4.0-luobi\r\n---\r\n# 正文\r\n")
        assertEquals("a-b", fm["name"]); assertEquals("第一行 第二行", fm["description"]); assertEquals("1.4.0-luobi", fm["version"]); assertTrue(b.contains("# 正文"))
        assertTrue(SkillParse.frontmatter("没有头\n正文").first.isEmpty())
        assertTrue(SkillParse.frontmatter("---\nname: x\n没有结束").first.isEmpty())
    }

    @Test fun builtInsAreThe14Packs() {
        val b = Skills.builtIns(ctx)
        assertEquals(listOf("人物传记与时代特稿写作", "财经非虚构深度写作"), b.map { it.displayName })
        assertTrue(b.all { it.builtIn && it.version == "1.4.0-luobi" && it.versionChip == "v1.4.0" })
        b.forEach { assertTrue(Skills.text(ctx, it)!!.length > 2000) }
        assertFalse(Skills.delete(ctx, b[0].id)); assertEquals(2, Skills.list(ctx).size)
    }

    // ---- 导入 ----
    @Test fun importMdAndZipNestedAndReplace() {
        val r1 = Skills.importBytes(ctx, skillMd().toByteArray(), "x.md")
        assertFalse(r1.replaced); assertEquals("demo-skill", r1.pack.name); assertEquals("0.3.1", r1.pack.version); assertFalse(r1.pack.builtIn)
        val r2 = Skills.importBytes(ctx, zip("wrap/inner/SKILL.md" to skillMd(extra = "").replace("0.3.1", "0.4.0").toByteArray(), "wrap/readme.txt" to "hi".toByteArray(), "__MACOSX/._SKILL.md" to ByteArray(4)), "pack.zip")
        assertTrue(r2.replaced); assertEquals(r1.pack.id, r2.pack.id); assertEquals("0.4.0", Skills.find(ctx, r2.pack.id)!!.version)
        assertEquals(3, Skills.list(ctx).size)
        assertTrue(Skills.delete(ctx, r2.pack.id)); assertEquals(2, Skills.list(ctx).size)
        assertFalse(Skills.delete(ctx, "u-../../x")); assertFalse(Skills.delete(ctx, "b-finance-nonfiction-writer"))
    }

    @Test fun importCannotShadowOrEscape() {
        // 同名于内置的导入不会覆盖内置，只是另一个 u- 包
        val p = Skills.importBytes(ctx, skillMd("finance-nonfiction-writer").toByteArray(), "f.md").pack
        assertTrue(p.id.startsWith("u-")); assertEquals(2, Skills.builtIns(ctx).size)
        // zip 里的路径穿越名只当字符串，不落盘到别处
        Skills.importBytes(ctx, zip("../../evil/SKILL.md" to skillMd("evil").toByteArray()), "e.zip")
        assertFalse(File(ctx.filesDir.parentFile, "evil").exists())
        assertTrue(File(ctx.filesDir, "skills").listFiles()!!.all { it.name.startsWith("u-") })
    }

    @Test fun importRejectsBadInputs() {
        err { Skills.importBytes(ctx, "PK\u0003\u0004垃圾".toByteArray(), "bad.zip") }.let { assertTrue(it, it.contains("打不开") || it.contains("没有找到")) }
        assertTrue(err { Skills.importBytes(ctx, zip("a.txt" to "x".toByteArray()), "n.zip") }.contains("没有找到 SKILL.md"))
        assertTrue(err { Skills.importBytes(ctx, zip("a.md" to skillMd().toByteArray(), "b.md" to skillMd("o").toByteArray()), "n.zip") }.contains("多个 .md"))
        assertTrue(err { Skills.importBytes(ctx, "太短".toByteArray(), "s.md") }.contains("太短"))
        assertTrue(err { Skills.importBytes(ctx, byteArrayOf(1, 2, 0, 3, 4, 5), "s.md") }.contains("不是文本"))
        assertTrue(err { Skills.importBytes(ctx, "x".toByteArray(), "s.exe") }.contains("只支持"))
        assertTrue(err { Skills.importBytes(ctx, zip("SKILL.md" to ByteArray(2_100_000) { 'a'.code.toByte() }), "big.zip") }.contains("太大"))
        assertTrue(err { Skills.readLimited(ByteArray(2000).inputStream(), 1000) }.contains("太大"))
        assertTrue(err { Skills.importBytes(ctx, "a".repeat(SkillParse.MAX_CHARS + 1).toByteArray(), "s.md") }.contains("太长"))
        assertEquals(2, Skills.list(ctx).size)   // 失败不留残渣
        assertTrue(File(ctx.filesDir, "skills").listFiles().orEmpty().isEmpty())
    }

    // ---- 成稿拆分 ----
    @Test fun articleSplitsTitleBodyRefs() {
        val raw = "---\ntitle: 备用标题\n---\n# 一个人与他的时代\n\n第一段。\n\n## 小节\n\n第二段。\n\n## 参考资料\n\n[1] 作者. 书名[M]. 北京: 出版社, 2020.\n2. 网页标题. https://example.com/a\n   续行内容\n\n## 附录\n不要这个"
        val a = SkillArticle.parse(raw)
        assertEquals("一个人与他的时代", a.title); assertTrue(a.body.contains("第二段")); assertFalse(a.body.contains("参考资料")); assertFalse(a.body.contains("# 一个人"))
        assertEquals("1. 作者. 书名[M]. 北京: 出版社, 2020.\n2. 网页标题. https://example.com/a 续行内容", a.refs)
    }

    @Test fun articleVariants() {
        val a = SkillArticle.parse("```markdown\n# 标题甲\n\n正文。\n\n**参考资料**\n\n1. 甲\n2. 乙\n```")
        assertEquals("标题甲", a.title); assertEquals("1. 甲\n2. 乙", a.refs)
        val b = SkillArticle.parse("正文没有标题。\n\n参考文献：\n[1] 丙", "兜底标题")
        assertEquals("兜底标题", b.title); assertEquals("1. 丙", b.refs); assertEquals("正文没有标题。", b.body.trim())
        val c = SkillArticle.parse("<think>想一想</think># 只有正文\n\n内容")
        assertEquals("只有正文", c.title); assertEquals("", c.refs); assertFalse(c.body.contains("think"))
        // 正文里出现同名小标题时以文末为准
        val d = SkillArticle.parse("# T\n\n正文\n\n## 参考资料的价值\n\n更多正文\n\n## 参考资料\n1. 某")
        assertTrue(d.body.contains("参考资料的价值")); assertEquals("1. 某", d.refs)
    }

    @Test fun peekProgressStopsAtReferences() {
        val p = SkillPeek.progress("# 标题X\n\n" + "字".repeat(300) + "\n\n## 参考资料\n1. " + "引".repeat(500))
        assertEquals("标题X", p.title); assertTrue(p.words in 250..320); assertEquals("整理参考资料…", p.line)
    }

    // ---- 计划 / 会话 ----
    @Test fun planParseVariants() {
        val j = JSONObject().put("object", "张雪峰").put("angle", "角度").put("alt", "").put("outline", JSONArray(listOf("1. 开篇", "## 二、成名", JSONObject().put("title", "扩张").put("point", "生意")))).put("gaps", "").put("words", "4500 字")
            .put("tags", JSONArray(listOf("#教育", "焦虑"))).put("category", "不存在的分类")
        val p = SkillPlan.parse("```json\n$j\n```")
        assertEquals(listOf("开篇", "二、成名", "扩张：生意"), p.outline); assertEquals(listOf("教育", "焦虑"), p.tags); assertEquals(Categories.validate("不存在的分类"), p.category)
        assertFailsWith { SkillPlan.parse("没有 JSON") }
        assertFailsWith { SkillPlan.parse(JSONObject().put("angle", "a").put("outline", JSONArray(listOf("只有一条"))).toString()) }
    }
    private fun assertFailsWith(f: () -> Unit) { try { f(); fail("应该抛出") } catch (_: AiFormatError) {} }

    @Test fun sessionRoundTripAndLoad() {
        val plan = SkillPlan("o", "a", "b", listOf("x", "y"), "g", "w", listOf("t"), "社会观察", "note")
        val s = SkillSession("confirm", "题目", "b-x", "名", "1.4.0-luobi", "p:deepseek", yb = 2, ds = 1, notices = listOf("n"), queries = listOf("q"),
            sources = listOf(SkillSource(1, "t", "https://u", "站", "2024-01-01", "元宝", "文")), plan = plan, err = Friendly("标", "详", "原", true), noteId = 3, words = 9, title = "标题")
        SkillFlow.set(ctx, s)
        SkillFlow.state.value = null; SkillFlow.load(ctx)
        val l = SkillFlow.state.value!!
        assertEquals(s.copy(startedAt = l.startedAt), l); assertEquals("元宝", l.sources[0].via)
        SkillFlow.clear(ctx); SkillFlow.load(ctx); assertNull(SkillFlow.state.value)
        ctx.getSharedPreferences("skillflow", 0).edit().putString("s", "{坏的").commit(); SkillFlow.load(ctx); assertNull(SkillFlow.state.value)
    }

    // ---- 检索 ----
    private fun ybResult(vararg p: Triple<String, String, String>) = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject().put("structuredContent", JSONObject().put("Response", JSONObject().put("Pages",
        JSONArray(p.map { JSONObject().put("title", it.first).put("url", it.second).put("passage", it.third).put("content", "").put("site", "站").put("score", .8) }))))).toString()
    private fun dsResult(vararg p: Pair<String, String>) = JSONObject().put("results", JSONArray(p.map { JSONObject().put("title", it.first).put("url", it.second).put("summary", "摘要" + it.first).put("site_name", "S") })).toString()
    private fun fakeNet(yb: () -> FactNet.Resp, ds: () -> FactNet.Resp) { FactNet.transport = { _, u, _, _ -> if (u == Yuanbao.URL) yb() else if (u.contains("deepseek")) ds() else FactNet.Resp(200, "{}") } }

    @Test fun researchYuanbaoFirstDeepSeekSupplementsAndDedups() {
        fakeNet({ FactNet.Resp(200, ybResult(Triple("甲", "https://a.com/1", "甲文"), Triple("乙", "https://b.com/2", "乙文"))) }, { FactNet.Resp(200, dsResult("甲重复" to "http://www.a.com/1/", "丙" to "https://c.com/3")) })
        val r = runBlocking { SkillRun.research(null, listOf("q1", "q2"), "yb", "ds") }
        assertEquals(listOf("元宝", "元宝", "DeepSeek"), r.sources.map { it.via }); assertEquals((1..3).toList(), r.sources.map { it.n })
        assertEquals(2, r.yb); assertEquals(1, r.ds); assertTrue(r.notices.isEmpty())
    }

    @Test fun researchWithoutYuanbaoKeyFallsBackWithNotice() {
        fakeNet({ error("不该调元宝") }, { FactNet.Resp(200, dsResult("丙" to "https://c.com/3")) })
        val r = runBlocking { SkillRun.research(null, listOf("q1"), "", "ds") }
        assertEquals(1, r.sources.size); assertEquals("DeepSeek", r.sources[0].via); assertTrue(r.notices.single().contains("没填元宝搜索 Key"))
    }

    @Test fun researchYuanbaoErrorFallsBackToDeepSeek() {
        fakeNet({ FactNet.Resp(500, "boom") }, { FactNet.Resp(200, dsResult("丙" to "https://c.com/3")) })
        val r = runBlocking { SkillRun.research(null, listOf("q1", "q2"), "yb", "ds") }
        assertEquals(listOf("DeepSeek"), r.sources.map { it.via }); assertTrue(r.notices.any { it.contains("元宝搜索没能用") && it.contains("已改用 DeepSeek") })
    }

    @Test fun researchNoKeysAtAllIsEmptyWithNotice() {
        val r = runBlocking { SkillRun.research(null, listOf("q"), "", "") }
        assertTrue(r.sources.isEmpty()); assertTrue(r.notices.any { it.contains("没有可用的搜索") })
    }

    // ---- 整条流水线（跑真的 CopyWorker，假网络） ----
    private val planJson = JSONObject().put("object", "张雪峰").put("angle", "写他把升学焦虑产品化的路径").put("alt", "").put("outline", JSONArray(listOf("开篇", "成名", "争议"))).put("gaps", "缺财务数据")
        .put("words", "约 4500 字").put("tags", JSONArray(listOf("教育"))).put("category", "社会观察").toString()
    private val article = "# 焦虑的生意\n\n第一段正文。\n\n第二段正文。\n\n## 参考资料\n\n[1] 甲. 示例[EB/OL]. https://a.com/1.\n[2] 乙. 示例二[EB/OL]. https://b.com/2."
    private fun sse(s: String) = s.chunked(9).joinToString("") { "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", it)))) + "\n\n" } + "data: [DONE]\n"
    private fun fakeChat(writeBody: String = sse(article)) {
        Ai.chatTransport = { _, b ->
            if (b.contains("\"stream\":true")) 200 to writeBody
            else if (!b.contains("确认页内容") && b.contains("检索词")) 200 to JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "{\"queries\":[\"张雪峰 简介\",\"张雪峰 争议\"]}")))).toString()
            else 200 to JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", planJson)))).toString()
        }
    }
    private fun runKind(kind: String): ListenableWorker.Result {
        ctx.getSharedPreferences("gen", Context.MODE_PRIVATE).edit().putString("job", GenJob(kind = kind, raw = "张雪峰", model = "p:deepseek", templateName = "财经非虚构深度写作").json()).commit()
        return runBlocking { TestListenableWorkerBuilder<CopyWorker>(ctx).build().doWork() }
    }
    private fun begin() {
        Settings(ctx).setKey("deepseek", "sk-test")
        val pack = Skills.builtIns(ctx).first { it.kind == "fin" }
        SkillFlow.start(ctx, "张雪峰", pack, "p:deepseek")
    }

    @Test fun pipelinePlanThenWriteSavesNoteRefsAndOutline() {
        begin()
        fakeNet({ FactNet.Resp(200, "{}") }, { FactNet.Resp(200, dsResult("甲" to "https://a.com/1", "乙" to "https://b.com/2")) }); fakeChat()
        val pr = runKind("skill-plan"); assertTrue(pr.toString() + SkillFlow.state.value.toString(), pr is ListenableWorker.Result.Success)
        val s = SkillFlow.state.value!!
        assertEquals("confirm", s.phase); assertEquals(2, s.sources.size); assertEquals(3, s.plan!!.outline.size); assertEquals(0, runBlocking { AppDb.get(ctx).notes().latest().first().size })
        val w = runKind("skill-write"); assertTrue(w is ListenableWorker.Result.Success)
        val id = w.outputData.getLong(CopyWorker.K_NOTE, 0)
        val n = runBlocking { AppDb.get(ctx).notes().get(id)!! }
        assertEquals("焦虑的生意", n.title); assertFalse(n.body.contains("参考资料")); assertEquals("财经非虚构深度写作", n.templateName); assertTrue(n.finalPrompt.startsWith("skill:b-finance"))
        assertEquals("1. 甲. 示例[EB/OL]. https://a.com/1.\n2. 乙. 示例二[EB/OL]. https://b.com/2.", Refs.get(ctx, id))
        assertNotNull(Ai.Outline.get(ctx, id)); assertEquals("done", SkillFlow.state.value!!.phase); assertEquals(id, SkillFlow.state.value!!.noteId)
        assertEquals(id, GenCard.state.value!!.noteId)
        // 改写出的新版本取不到自己的参考资料时回到首版
        assertEquals(Refs.get(ctx, id), Refs.forNote(ctx, id + 100, id))
        assertNull(Refs.forNote(ctx, 999, 999))
    }

    @Test fun planFailureGoesToFailAndKeepsTopic() {
        begin(); fakeNet({ FactNet.Resp(200, "{}") }, { FactNet.Resp(200, dsResult()) }); fakeChat()
        assertTrue(runKind("skill-plan") is ListenableWorker.Result.Failure)
        val s = SkillFlow.state.value!!
        assertEquals("fail", s.phase); assertEquals("张雪峰", s.topic); assertTrue(s.err!!.title.contains("没有搜到"))
    }

    @Test fun noSearchKeyIsAConfigErrorWithSettingsLink() {
        val pack = Skills.builtIns(ctx).first(); SkillFlow.start(ctx, "题", pack, "p:deepseek"); fakeChat()
        assertTrue(runKind("skill-plan") is ListenableWorker.Result.Failure)
        val s = SkillFlow.state.value!!; assertEquals("fail", s.phase); assertTrue(s.err!!.settings)
    }

    @Test fun writeFailureReturnsToConfirmAndKeepsPlanAndSources() {
        begin(); fakeNet({ FactNet.Resp(200, "{}") }, { FactNet.Resp(200, dsResult("甲" to "https://a.com/1")) }); fakeChat()
        runKind("skill-plan")
        Ai.chatTransport = { _, _ -> 500 to "{\"error\":\"boom\"}" }
        val wr = runKind("skill-write"); assertTrue(wr.toString() + SkillFlow.state.value, wr is ListenableWorker.Result.Failure)
        val s = SkillFlow.state.value!!
        assertEquals(s.toString(), "confirm", s.phase); assertNotNull(s.err); assertNotNull(s.plan); assertEquals(1, s.sources.size)
        assertEquals(0, runBlocking { AppDb.get(ctx).notes().latest().first().size })
    }

    @Test fun deletedSkillDuringConfirmIsReportedNotCrashed() {
        val p = Skills.importBytes(ctx, skillMd().toByteArray(), "d.md").pack
        Settings(ctx).setKey("deepseek", "sk-test"); SkillFlow.start(ctx, "题", p, "p:deepseek")
        SkillFlow.update(ctx) { it.copy(phase = "confirm", plan = SkillPlan.parse(planJson)) }
        Skills.delete(ctx, p.id); fakeChat()
        assertTrue(runKind("skill-write") is ListenableWorker.Result.Failure)
        assertTrue(SkillFlow.state.value!!.err!!.title.contains("技能包不见了"))
    }

    // ---- 旧数据 ----
    @Test fun oldNotesAndSettingsStillWork() {
        val db = AppDb.get(ctx)
        val id = runBlocking { val n = Note(title = "2.3 的文章", body = "正文", tags = "", templateName = "通用", category = "社会观察"); db.notes().insert(n) }
        assertEquals("2.3 的文章", runBlocking { db.notes().get(id)!!.title }); assertNull(Refs.get(ctx, id))
        assertTrue(runBlocking { db.templates().all().let { true } })   // 模板表仍在（只是不再用于创作）
        assertNull(SkillFlow.state.value)
    }
}
