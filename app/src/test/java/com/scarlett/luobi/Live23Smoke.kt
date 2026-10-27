package com.scarlett.luobi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 2.3 真机前冒烟：只在 LIVE23=1 且有 DEEPSEEK_API_KEY 时跑；Key 只写进测试沙箱的 Secure，不打印不落盘到仓库 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class Live23Smoke {
    @Test fun oneDirectionLive() = runBlocking {
        val key = System.getenv("DEEPSEEK_API_KEY").orEmpty()
        assumeTrue(System.getenv("LIVE23") == "1" && key.isNotBlank())
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val s = Settings(ctx); s.setKey("deepseek", key); s.polish23 = false
        val db = AppDb.get(ctx); Defaults.ensure(db, ctx)
        val tpl = db.templates().list().first { it.name == "社会观察" }
        val v = mapOf("想写什么" to "年轻人为什么越来越不爱去商场", "观点" to "商场卖的不是货，是一种被安排好的周末；年轻人退出的是那种安排", "疑问" to "线上便宜只是表面原因吗？")
        val stages = mutableListOf<String>(); val t0 = System.currentTimeMillis()
        val id = Ai.write(ctx, GenJob(raw = "周末在商场里看到的：人少，奶茶店排队，服装店空。", values = v, templateId = tpl.id, templateName = tpl.name, model = "p:deepseek"), {}) { a, n -> stages += "$a/$n@${System.currentTimeMillis() - t0}ms" }
        val n = db.notes().get(id)!!
        val out = File(System.getProperty("user.dir"), "build/live23.md")
        out.writeText("stages: $stages\ntotal: ${System.currentTimeMillis() - t0}ms\nwords: ${n.body.length}\n\n# ${n.title}\n\n${n.body}\n\n---\n提纲：\n${Ai.Outline.pretty(Ai.Outline.get(ctx, id).orEmpty())}\n")
    }
}
