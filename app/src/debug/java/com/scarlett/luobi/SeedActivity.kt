package com.scarlett.luobi

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import java.io.File

/** 仅 debug：写入一篇示例文章（含金句、引用、列表、一张本地插图），供模拟器截图 */
class SeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val f = File(PicCache.dir(this), "seed.jpg")
        val b = Bitmap.createBitmap(1200, 520, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 1200f, 520f, 0xFFDCE4F0.toInt(), 0xFFB9C7DC.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(860f, 160f, 62f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FFFFFF.toInt() })
        c.drawPath(Path().apply { moveTo(0f, 520f); lineTo(380f, 240f); lineTo(660f, 420f); lineTo(830f, 310f); lineTo(1200f, 520f); close() }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB39FB1CB.toInt() })
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val body = """很多人以为成长靠的是工作时长。**真正拉开差距的，是下班后那段没人管的时间。**

## 一、先把时间「看见」

连续记录一周，你会发现大部分时间被短视频和无目的的刷屏吃掉了。把它们写下来，浪费就有了形状。

> 时间不会因为你忙而变多，只会因为你看见它而变得有用。

### 每天 30 分钟就够

不用宏大，读完一章、写下三行，关键是连续 21 天不断。

- 固定一个开始时间
- 手机放到另一个房间
- 做完在日历上打个勾

---

**小步快跑，比一次用力更重要。**"""
        val pics = listOf(Pic(3, "https://example.com/seed.jpg", "", "", "", "Pexafy", f.path))
        runBlocking {
            val id = AppDb.get(this@SeedActivity).notes().insert(Note(title = "下班后的 2 小时，决定了你三年后的样子", body = body, tags = "成长 时间管理 自律", templateName = "观点表达", model = "DeepSeek · deepseek-chat", images = Pics.encode(pics)))
            AppDb.get(this@SeedActivity).notes().update(AppDb.get(this@SeedActivity).notes().get(id)!!.copy(rootId = id))
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish()
    }
}
