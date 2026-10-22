package com.scarlett.luobi

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object Share {
    fun text(n: Note) = n.title + "\n\n" + n.body + "\n\n" + n.tagList().joinToString(" ") { "#$it" }

    fun copy(ctx: Context, label: String, t: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, t))
    }

    /**
     * 1.16：分享和存相册用「同一个文件」：无损 PNG，原始分辨率。
     * 以前分享走 cache 里另外重新编码的 JPEG（92/95 质量 + 4:2:0 色度抽样，彩色小字发虚），
     * 再加上类型写成通配图片或 image/jpeg，接收方会按「普通照片」再压一次，看起来比相册里那张糊。
     */
    const val MIME = "image/png"
    private fun authority(@Suppress("UNUSED_PARAMETER") ctx: Context) = "com.scarlett.luobi.files"
    fun shareDir(ctx: Context) = File(ctx.cacheDir, "share/img").apply { mkdirs() }
    fun cleanShare(ctx: Context) { runCatching { shareDir(ctx).listFiles()?.forEach { it.delete() } } }
    fun pngName(name: String) = name.substringBeforeLast('.') + ".png"
    /** 位图 → 无损 PNG 文件（不缩放、不降质） */
    fun writePng(ctx: Context, b: Bitmap, name: String): File {
        val f = File(shareDir(ctx), pngName(name))
        f.outputStream().buffered().use { if (!b.compress(Bitmap.CompressFormat.PNG, 100, it)) throw java.io.IOException("图片编码失败") }
        return f
    }
    fun uriOf(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, authority(ctx), f)
    fun cacheJpeg(ctx: Context, b: Bitmap, name: String): Uri = uriOf(ctx, writePng(ctx, b, name))

    /** 分享 Intent：1 张 ACTION_SEND、多张 ACTION_SEND_MULTIPLE；EXTRA_STREAM + ClipData + 读权限，MIME = image/png */
    fun imageIntent(uris: List<Uri>): Intent {
        val it = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        it.type = MIME
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        it.clipData = ClipData(null, arrayOf(MIME), ClipData.Item(uris[0])).apply { uris.drop(1).forEach { u -> addItem(ClipData.Item(u)) } }
        return it
    }

    fun pageFiles(ctx: Context, imgs: List<Bitmap>): List<File> {
        cleanShare(ctx); val stamp = System.currentTimeMillis()
        return imgs.mapIndexed { i, b -> writePng(ctx, b, "xhs_${stamp}_${i + 1}.png") }
    }

    fun sendImages(ctx: Context, n: Note, imgs: List<Bitmap>) {
        val uris = pageFiles(ctx, imgs).map { uriOf(ctx, it) }
        if (uris.isEmpty()) return
        val it = imageIntent(uris)
        val x = Intent(it).setPackage("com.xingin.xhs")
        val ok = ctx.packageManager.queryIntentActivities(x, 0).isNotEmpty()
        if (!ok) Toast.makeText(ctx, "未检测到小红书，请选择分享方式", Toast.LENGTH_LONG).show()
        val target = if (ok) x else Intent.createChooser(it, "分享到")
        safeStart(ctx, target)
    }

    /** 系统分享单张长图（微信等） */
    fun sendLongImage(ctx: Context, bmp: Bitmap) {
        cleanShare(ctx)
        safeStart(ctx, Intent.createChooser(imageIntent(listOf(cacheJpeg(ctx, bmp, "long_${System.currentTimeMillis()}.png"))), "分享长图"))
    }

    /** 系统分享多张长图（文章太长被拆开时） */
    fun sendLongImages(ctx: Context, uris: ArrayList<Uri>) {
        if (uris.isEmpty()) return
        safeStart(ctx, Intent.createChooser(imageIntent(uris), "分享长图"))
    }

    /** 系统分享纯文本 */
    fun sendText(ctx: Context, n: Note) {
        val it = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, n.title)
            putExtra(Intent.EXTRA_TEXT, text(n))
        }
        safeStart(ctx, Intent.createChooser(it, "分享文案"))
    }

    /** 存相册：把同一个 PNG 文件原样拷进 MediaStore（与分享出去的字节完全一致） */
    fun saveFile(ctx: Context, f: File): Boolean {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Images.Media.MIME_TYPE, MIME)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/落笔")
        }
        val u = try { ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) } catch (_: Exception) { null } ?: return false
        val ok = try { ctx.contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) }; true } == true } catch (_: Exception) { false }
        if (!ok) runCatching { ctx.contentResolver.delete(u, null, null) }
        return ok
    }
    fun save(ctx: Context, imgs: List<Bitmap>): Int = pageFiles(ctx, imgs).count { saveFile(ctx, it) }
    fun saveOne(ctx: Context, b: Bitmap, name: String = "long_${System.currentTimeMillis()}.png"): Boolean = saveFile(ctx, writePng(ctx, b, name))

    /**
     * 1.10：Markdown / HTML 以「文件」分享：写到 cache/share/docs/<标题>.md|.html（UTF-8，先写临时文件再改名，不会留半截），
     * 再用 FileProvider 的 content:// + 正确 MIME + 读权限弹系统分享。不往用户存储里留任何东西；每次分享前清掉上一次的临时文件。
     */
    fun docFile(dir: File, title: String, ext: String, content: String): File {
        dir.listFiles()?.forEach { it.delete() }   // 清理旧的分享文件
        dir.mkdirs()
        val f = File(dir, FileNames.sanitize(title) + "." + ext)
        val tmp = File(dir, ".${System.nanoTime()}.tmp")
        try { tmp.writeText(content, Charsets.UTF_8); if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("无法写入分享文件") } }
        finally { if (tmp.exists()) tmp.delete() }
        return f
    }
    fun docsDir(ctx: Context) = File(ctx.cacheDir, "share/docs")
    fun cleanDocs(ctx: Context) { runCatching { docsDir(ctx).listFiles()?.forEach { it.delete() } } }
    fun shareDoc(ctx: Context, title: String, ext: String, mime: String, content: String): Boolean {
        val f = docFile(docsDir(ctx), title, ext, content)
        val uri = FileProvider.getUriForFile(ctx, "com.scarlett.luobi.files", f)
        val it = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData = ClipData.newRawUri(f.name, uri)
        }
        return try { ctx.startActivity(Intent.createChooser(it, "分享 ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        catch (_: Exception) { Toast.makeText(ctx, "没有可以接收这个文件的应用", Toast.LENGTH_LONG).show(); false }
    }

    /** 1.7：目标应用不存在 / 被禁用时不再崩溃 */
    fun safeStart(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        catch (_: Exception) { Toast.makeText(ctx, "没有可用的应用来完成分享", Toast.LENGTH_LONG).show(); false }
}
