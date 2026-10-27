package com.scarlett.luobi

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class V22Test {
    private fun db() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java).allowMainThreadQueries().build()

    @Test fun sanitizerStripsJunk() {
        val b = Sanitize.body("标题\n\n第一段,有半角标点.然后[1]继续。\n\n### 小节\n\n看这里 https://x.com/a 。\n\n参考文献\n[1] 某论文", "标题")
        assertFalse(b.contains("参考文献")); assertFalse(b.contains("[1]")); assertFalse(b.contains("http")); assertFalse(b.startsWith("标题"))
        assertTrue(b.contains("第一段，有半角标点。然后继续。")); assertTrue(b.contains("## 小节"))
        assertEquals("3.14 和 12:30 不动", Sanitize.punct("3.14 和 12:30 不动"))
    }
    @Test fun parseSalvagesBrokenJson() {
        val a = ArticleParse.parse("""{"title":"好标题","body":"第一段,内容。\n\n第二段"tags":["a"]""", "备用")
        assertEquals("好标题", a.title); assertTrue(a.body.startsWith("第一段，内容。")); assertFalse(a.body.contains("\"tags\""))
    }
    @Test fun migrationKeepsEditedAndIsIdempotent() = runBlocking {
        val d = db()
        val fac = Defaults.factoryTexts("豆瓣影评").first()
        d.templates().insert(Template(name = "豆瓣影评", desc = "", builtIn = true, prompt = fac))
        d.templates().insert(Template(name = "专栏评论", desc = "", builtIn = true, prompt = "我自己改过的内容"))
        d.templates().insert(Template(name = "通用非虚构", desc = "", builtIn = true, prompt = Defaults.factoryTexts("通用非虚构").first()))
        Defaults.migrate22(d)
        val l = d.templates().list()
        assertTrue(l.any { it.name == "影评剧评" && it.builtIn })
        assertTrue(l.any { it.name == "专栏评论" && !it.builtIn && it.prompt == "我自己改过的内容" })
        assertFalse(l.any { it.name == "通用非虚构" })
        assertTrue(Prompts22.TYPES.all { t -> l.any { it.name == t.name } })
        Defaults.migrate22(d); assertEquals(l.size, d.templates().list().size)
        d.close()
    }
    @Test fun lengthHintHasParagraphsAndCap() {
        val t = Prompts22.TYPES[0]; val p = Compose.prompt(t.name, t.desc, t.module, mapOf("观点" to "我的观点"), "素材", 0)
        assertTrue(p.contains("不要超过 300 字")); assertTrue(p.indexOf("【这篇要表达什么】") < p.indexOf("【素材"))
        assertTrue(Prompts22.TYPES.all { it.module.contains("【语气示范") })
    }
}
