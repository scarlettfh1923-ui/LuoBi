package com.scarlett.luobi

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 2.3：提示词拼装（每个方向）、旧类型映射 */
class Prompt23Test {
    private val v = mapOf("想写什么" to "W想写", "观点" to "V观点", "疑问" to "Q疑惑")
    @Test fun eachDirectionAssembles() {
        assertEquals(listOf("影视评论", "社会观察", "思想人文", "历史非虚构"), Prompts23.DIRS.map { it.name })
        val sys = Prompts23.system()
        assertFalse(sys.contains("{分类列表}")); assertTrue(sys.contains(Categories.LIST.first()))
        for (d in Prompts23.DIRS) {
            val ctx = Compose.prompt(d.name, d.desc, d.module, v, "素材R", 0)
            assertTrue(d.name, ctx.contains("W想写") && ctx.contains("V观点") && ctx.contains("Q疑惑") && ctx.contains("素材R"))
            assertTrue(d.name, ctx.contains(d.module.trim().lines().first()))
            assertFalse("自检已移到 STEP2", ctx.contains(Prompts22.SELF_CHECK.trim().lines().first()))
            assertTrue(d.module.contains("【不编造】"))
            assertTrue(Prompts23.outlinePrompt(ctx).startsWith(ctx) && Prompts23.outlinePrompt(ctx).endsWith(Prompts23.STEP1))
            val w = Prompts23.writePrompt(ctx, "{\"core\":\"OUT\"}")
            assertTrue(w.contains(Prompts23.STEP2) && w.contains("{\"core\":\"OUT\"}") && w.indexOf(ctx) < w.indexOf("OUT"))
            assertTrue(Prompts23.polishPrompt(ctx, "DRAFT").startsWith(Prompts23.STEP3) && Prompts23.polishPrompt(ctx, "DRAFT").endsWith("DRAFT"))
        }
        assertTrue(Compose.prompt("社会观察", "", Prompts23.dir("社会观察")!!.module, v, "r", 0, xhs = true).contains(Prompts22.XHS.trim().lines().first()))
    }
    @Test fun oldTypesMapToNearest() {
        val m = mapOf("影评剧评" to "影视评论", "豆瓣影评" to "影视评论", "口播转文章" to "影视评论", "犀利吐槽" to "社会观察", "观点评论" to "社会观察",
            "专栏评论" to "社会观察", "通用非虚构" to "社会观察", "小红书风格" to "社会观察", "个人随笔" to "思想人文", "干货科普" to "思想人文",
            "情感关系" to "思想人文", "书单好物推荐" to "思想人文", "历史非虚构" to "历史非虚构", "没听过的名字" to "社会观察")
        m.forEach { (k, d) -> assertEquals(k, d, Prompts23.nearest(k).name) }
        assertTrue(Prompts23.isXhs("小红书风格")); assertFalse(Prompts23.isXhs("豆瓣影评"))
        assertTrue(Prompts23.INTENT.contains("影视评论") && Prompts23.INTENT != Prompts22.INTENT)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class Migrate23Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun mem() = Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
    private val dirs = Prompts23.DIRS.map { it.name }

    @Test fun freshInstallIs4Plus1() = runBlocking {
        val db = mem(); Defaults.ensure(db, ctx)
        val l = db.templates().list()
        assertEquals(dirs.toSet() + "财经非虚构", l.map { it.name }.toSet()); assertEquals(5, l.size)
        assertFalse(l.first { it.name == "财经非虚构" }.builtIn)
        assertTrue(Defaults.migrate23(db).isEmpty())   // 幂等
    }
    @Test fun seedsUpgradeEditedKept() = runBlocking {
        val db = mem()
        Defaults.templates22.forEach { db.templates().insert(it) }
        val oldFin = Defaults.factory23("财经非虚构").first()
        db.templates().insert(Template(name = "财经非虚构", desc = "d", builtIn = false, prompt = oldFin))
        db.templates().insert(Template(name = "豆瓣影评", desc = "d", builtIn = false, prompt = Defaults.factory23("豆瓣影评").first()))
        db.templates().insert(Template(name = "小红书风格", desc = "d", builtIn = false, prompt = "我改过的小红书"))
        db.templates().insert(Template(name = "专栏评论", desc = "d", builtIn = false, prompt = "我改过的专栏"))
        val xs = db.templates().list().first { it.name == "犀利吐槽" }
        db.templates().update(xs.copy(prompt = xs.prompt + "\n我加了一句"))
        val socId = db.templates().list().first { it.name == "社会观察" }.id
        val bk = mutableListOf<Pair<String, String>>()
        Defaults.migrate23(db) { n, t -> bk += n to t }
        val l = db.templates().list()
        assertEquals(dirs.toSet(), l.filter { it.builtIn }.map { it.name }.toSet())
        Prompts23.DIRS.forEach { d -> assertEquals(d.module, l.single { it.builtIn && it.name == d.name }.prompt) }
        assertEquals(socId, l.single { it.builtIn && it.name == "社会观察" }.id)   // 保住 id
        assertEquals(Prompts23.FIN_TPL, l.single { it.name == "财经非虚构" }.prompt)
        assertTrue(bk.contains("财经非虚构" to oldFin))                      // 旧文可找回
        assertTrue(bk.contains("小红书风格" to "我改过的小红书") && bk.contains("专栏评论" to "我改过的专栏"))
        assertFalse(bk.any { it.first == "豆瓣影评" })                      // 没改过的种子直接删
        assertTrue(l.none { it.name in Prompts23.REMOVED })
        val mine = l.single { it.name == "犀利吐槽" }; assertFalse(mine.builtIn); assertTrue(mine.prompt.endsWith("我加了一句"))
        assertEquals(6, l.size)
        val before = l.map { it.copy(updatedAt = 0) }
        assertTrue(Defaults.migrate23(db).isEmpty())
        assertEquals(before, db.templates().list().map { it.copy(updatedAt = 0) })
    }
    @Test fun editedFinKeepsBody() = runBlocking {
        val db = mem()
        Defaults.templates22.forEach { db.templates().insert(it) }
        db.templates().insert(Template(name = "财经非虚构", desc = "d", builtIn = true, prompt = "她自己的财经正文"))
        Defaults.migrate23(db) { _, _ -> }
        val f = db.templates().list().single { it.name == "财经非虚构" }
        assertEquals("她自己的财经正文", f.prompt); assertFalse(f.builtIn)
        assertEquals(5, db.templates().list().size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class Write23Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<JSONObject>()
    @After fun r() { Ai.chatTransport = null; Settings(ctx).polish23 = false }
    private fun reply(c: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", c)))).toString()
    private val outline = """{"core":"核心OUT","sections":["一","二"]}"""
    private val draft = """{"title":"初稿题","body":"${"初稿正文。".repeat(40)}","tags":["a"],"category":"${Categories.LIST.first()}"}"""
    private fun setup(polishReply: String) = runBlocking {
        Settings(ctx).setKey("deepseek", "sk-test"); Defaults.ensure(AppDb.get(ctx), ctx)
        sent.clear()
        Ai.chatTransport = { _, b -> val j = JSONObject(b); sent += j
            val u = j.getJSONArray("messages").getJSONObject(1).getString("content")
            200 to reply(when { u.startsWith(Prompts23.STEP3) -> polishReply; u.contains("<<<写作判断") -> draft; else -> outline }) }
    }
    private fun job(name: String, id: Long = 0) = GenJob(raw = "素材", values = mapOf("想写什么" to "W"), templateId = id, templateName = name, model = "p:deepseek", finalPrompt = "FALLBACK")

    @Test fun twoStepsByDefault() = runBlocking {
        setup("x"); val st = mutableListOf<Pair<Int, Int>>()
        val id = Ai.write(ctx, job("影视评论"), {}) { a, b -> st += a to b }
        assertEquals(listOf(1 to 2, 2 to 2), st); assertEquals(2, sent.size)
        sent.forEach { assertEquals(Prompts23.system(), it.getJSONArray("messages").getJSONObject(0).getString("content")) }
        assertTrue(sent[1].getJSONArray("messages").getJSONObject(1).getString("content").contains("核心OUT"))
        assertEquals("初稿题", AppDb.get(ctx).notes().get(id)!!.title)
        assertEquals(outline, Ai.Outline.get(ctx, id)); assertTrue(Ai.Outline.pretty(outline).contains("核心观点：核心OUT"))
    }
    @Test fun polishToggleAddsThirdStep() = runBlocking {
        setup("""{"title":"校对题","body":"${"校对正文。".repeat(40)}","tags":[]}"""); Settings(ctx).polish23 = true
        val st = mutableListOf<Int>()
        val id = Ai.write(ctx, job("社会观察"), null) { a, _ -> st += a }
        assertEquals(listOf(1, 2, 3), st); assertEquals(3, sent.size)
        val n = AppDb.get(ctx).notes().get(id)!!
        assertEquals("校对题", n.title); assertEquals("a", n.tags)   // 校对没给标签 → 沿用初稿
    }
    @Test fun badPolishFallsBackToDraft() = runBlocking {
        setup("""{"title":"短","body":"太短"}"""); Settings(ctx).polish23 = true
        val id = Ai.write(ctx, job("社会观察"))
        assertEquals("初稿题", AppDb.get(ctx).notes().get(id)!!.title)
    }
    @Test fun removedTemplateRegeneratesViaDirection() = runBlocking {
        setup("x")
        val p = Ai.current(ctx, job("豆瓣影评", id = 99999))!!
        assertTrue(p.contains(Prompts23.dir("影视评论")!!.module.trim().lines().first()))
        val x = Ai.current(ctx, job("小红书风格", id = 99999))!!
        assertTrue(x.contains(Prompts23.dir("社会观察")!!.module.trim().lines().first()) && x.contains(Prompts22.XHS.trim().lines().first()))
        Ai.write(ctx, job("口播转文章", id = 99999))
        assertFalse(sent[0].getJSONArray("messages").getJSONObject(1).getString("content").contains("FALLBACK"))
    }
    @Test fun outlineFailureFailsWholeJob() = runBlocking {
        setup("x"); Ai.chatTransport = { _, _ -> 500 to "boom" }
        assertThrows(Exception::class.java) { runBlocking { Ai.write(ctx, job("社会观察")) } }; Unit
    }
}
