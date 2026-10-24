package com.scarlett.luobi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class V202Test {
    @Test fun inputClearedAndNotRestored() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        val ws = WriteState(c)
        ws.raw = "我想写：减负\n我的观点：负担转移了"; ws.saveRaw(); ws.values["观点"] = "x"; ws.extractedFor = ws.raw.trim(); ws.save()
        val job = GenJob("write", ws.raw.trim())
        ws.clearAfterSubmit()
        assertEquals("", ws.raw); assertTrue(ws.values.isEmpty()); assertFalse(ws.sheet)
        val again = WriteState(c)   // 模拟重启 / 重新进入页面
        assertEquals("", again.raw); assertTrue(again.values.isEmpty())
        assertEquals("我想写：减负\n我的观点：负担转移了", job.raw)
    }
    @Test fun sideMarginsSymmetricGrid() {
        for (w in listOf(320f, 360f, 393f, 412f)) for (f in listOf(14f, 15f, 16f, 17f, 18f, 20f, 22f, 24f)) for (fs in listOf(1f, 1.15f, 1.3f)) {
            val s = readerSide(w, f, fs); val adv = (f + .2f) * fs
            assertTrue(s >= 23.7f)
            val rem = (w - 2 * s) / adv; assertTrue("w=$w f=$f", rem - Math.floor(rem.toDouble()) < 0.05 || rem - Math.floor(rem.toDouble()) > 0.95)
        }
    }
}
