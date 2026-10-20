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

    fun cacheJpeg(ctx: Context, b: Bitmap, name: String): Uri {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, name)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return FileProvider.getUriForFile(ctx, "com.scarlett.luobi.files", f)
    }

    fun sendImages(ctx: Context, n: Note, imgs: List<Bitmap>) {
        val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val uris = ArrayList<Uri>()
        imgs.forEachIndexed { i, b ->
            val f = File(dir, "xhs_${System.currentTimeMillis()}_$i.jpg")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            uris += FileProvider.getUriForFile(ctx, "com.scarlett.luobi.files", f)
        }
        val it = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { u -> addItem(ClipData.Item(u)) } }
        }
        val x = Intent(it).setPackage("com.xingin.xhs")
        val ok = ctx.packageManager.queryIntentActivities(x, 0).isNotEmpty()
        if (!ok) Toast.makeText(ctx, "未检测到小红书，请选择分享方式", Toast.LENGTH_LONG).show()
        val target = if (ok) x else Intent.createChooser(it, "分享到")
        safeStart(ctx, target)
    }

    /** 系统分享单张长图（微信等） */
    fun sendLongImage(ctx: Context, bmp: Bitmap) {
        val uri = cacheJpeg(ctx, bmp, "long_${System.currentTimeMillis()}.jpg")
        val it = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", uri)
        }
        safeStart(ctx, Intent.createChooser(it, "分享长图"))
    }

    /** 系统分享多张长图（文章太长被拆开时） */
    fun sendLongImages(ctx: Context, uris: ArrayList<Uri>) {
        if (uris.isEmpty()) return
        val it = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uris[0]) }
        else Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/jpeg"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        it.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { u -> addItem(ClipData.Item(u)) } }
        safeStart(ctx, Intent.createChooser(it, "分享长图"))
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

    fun save(ctx: Context, imgs: List<Bitmap>): Int {
        var n = 0
        imgs.forEachIndexed { i, b ->
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "xhs_${System.currentTimeMillis()}_$i.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/落笔")
            }
            val u = try { ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) } catch (_: Exception) { null }
            if (u != null) {
                val ok = try { ctx.contentResolver.openOutputStream(u)?.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) } == true } catch (_: Exception) { false }
                if (ok) n++ else try { ctx.contentResolver.delete(u, null, null) } catch (_: Exception) {}
            }
        }
        return n
    }

    fun saveOne(ctx: Context, b: Bitmap, name: String = "long_${System.currentTimeMillis()}.jpg"): Boolean {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/落笔")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
        val out = ctx.contentResolver.openOutputStream(uri) ?: run {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
            return false
        }
        out.use { stream ->
            if (!b.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                return false
            }
        }
        return true
    }

    /** 1.7：目标应用不存在 / 被禁用时不再崩溃 */
    fun safeStart(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        catch (_: Exception) { Toast.makeText(ctx, "没有可用的应用来完成分享", Toast.LENGTH_LONG).show(); false }
}
