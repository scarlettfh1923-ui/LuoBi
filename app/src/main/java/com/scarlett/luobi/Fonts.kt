package com.scarlett.luobi

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReadFont(val id: String, val name: String, val sizeMb: Int, val urls: List<String>)

object Fonts {
    private fun cdn(path: String) = listOf("https://cdn.jsdelivr.net/$path", "https://fastly.jsdelivr.net/$path", "https://gcore.jsdelivr.net/$path")
    val list = listOf(
        ReadFont("sans", "思源黑体", 0, emptyList()),   // 系统自带 Noto Sans CJK（即思源黑体）
        ReadFont("serif", "思源宋体", 0, emptyList()),
        ReadFont("wenkai", "霞鹜文楷", 24, cdn("npm/@fontpkg/lxgw-wen-kai@1.520.0/LXGWWenKai-Regular.ttf") +
            "https://registry.npmmirror.com/@fontpkg/lxgw-wen-kai/1.520.0/files/LXGWWenKai-Regular.ttf" + "https://unpkg.com/@fontpkg/lxgw-wen-kai@1.520.0/LXGWWenKai-Regular.ttf"),
        ReadFont("puhui", "阿里普惠", 8, cdn("npm/alibabapuhuiti-3-55-regular@1.0.0/AlibabaPuHuiTi-3-55-Regular.ttf") +
            "https://registry.npmmirror.com/alibabapuhuiti-3-55-regular/1.0.0/files/AlibabaPuHuiTi-3-55-Regular.ttf" + "https://unpkg.com/alibabapuhuiti-3-55-regular@1.0.0/AlibabaPuHuiTi-3-55-Regular.ttf"),
    )
    /** 下载进度 0..1；-1 表示失败 */
    val progress = mutableStateMapOf<String, Float>()
    private val cache = HashMap<String, FontFamily>()
    val serif = FontFamily(Font(R.font.source_han_serif_regular), Font(R.font.source_han_serif_bold, FontWeight.Bold))

    fun file(c: Context, id: String) = File(c.filesDir, "fonts/$id.font")
    fun ready(c: Context, id: String) = id == "serif" || id == "sans" || file(c, id).let { it.exists() && it.length() > 1_000_000 }
    fun family(c: Context, id: String): FontFamily? {
        if (id == "serif") return serif
        if (id == "sans") return FontFamily.Default
        cache[id]?.let { return it }
        if (!ready(c, id)) return null
        return try { FontFamily(Typeface.createFromFile(file(c, id))).also { cache[id] = it } } catch (_: Exception) { null }
    }

    /** 给 Canvas 出图用的 Typeface */
    fun typeface(c: Context, id: String, bold: Boolean): Typeface {
        val base = try {
            if (id == "serif") androidx.core.content.res.ResourcesCompat.getFont(c, if (bold) R.font.source_han_serif_bold else R.font.source_han_serif_regular)
            else if (id == "sans") Typeface.DEFAULT
            else if (ready(c, id)) Typeface.createFromFile(file(c, id)) else null
        } catch (_: Exception) { null } ?: Typeface.DEFAULT
        return if (bold && id != "serif") Typeface.create(base, 700, false) else base
    }

    suspend fun download(c: Context, f: ReadFont): Unit = withContext(Dispatchers.IO) {
        val out = file(c, f.id); out.parentFile?.mkdirs()
        val tmp = File(out.path + ".part")
        var last: Exception? = null
        progress[f.id] = 0f
        for (u in f.urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) XhsHelper/1.2")
                if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: (f.sizeMb * 1_000_000L)
                var done = 0L
                conn.inputStream.use { ins -> tmp.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = ins.read(buf); if (n < 0) break; os.write(buf, 0, n); done += n; progress[f.id] = (done.toFloat() / total).coerceAtMost(0.99f) }
                } }
                if (tmp.length() < 1_000_000) throw Exception("文件不完整")
                Typeface.createFromFile(tmp)
                tmp.renameTo(out); progress.remove(f.id); return@withContext
            } catch (e: Exception) { last = e; tmp.delete() }
        }
        progress[f.id] = -1f
        throw Exception("字体下载失败：${last?.message ?: "网络错误"}，请检查网络后重试")
    }
}
