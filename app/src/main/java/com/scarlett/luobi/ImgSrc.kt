package com.scarlett.luobi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 一个图片来源。type：pexafy / pexels / ai / custom。Key 存 Secure("imgkey_<id>") */
data class ImgSource(val id: String, val type: String, val name: String, val on: Boolean = true,
                     val url: String = "", val auth: String = "header", val authName: String = "", val imgPath: String = "", val thumbPath: String = "", val creditPath: String = "") {
    fun json(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name).put("on", on).put("url", url).put("auth", auth).put("authName", authName)
        .put("imgPath", imgPath).put("thumbPath", thumbPath).put("creditPath", creditPath)
    companion object {
        fun from(o: JSONObject) = ImgSource(o.optString("id"), o.optString("type"), o.optString("name"), o.optBoolean("on", true), o.optString("url"), o.optString("auth", "header"),
            o.optString("authName"), o.optString("imgPath"), o.optString("thumbPath"), o.optString("creditPath"))
        val DEFAULT = listOf(ImgSource("pexafy", "pexafy", "Pexafy"), ImgSource("pexels", "pexels", "Pexels"), ImgSource("ai", "ai", "AI 生图模型（可选）"))
    }
}

object ImgSources {
    fun list(ctx: Context): List<ImgSource> = Settings(ctx).imgSources
    fun save(ctx: Context, l: List<ImgSource>) { Settings(ctx).imgSources = l }
    fun key(ctx: Context, id: String) = Secure.get(ctx, "imgkey_$id")
    fun ready(ctx: Context, s: ImgSource): Boolean = s.on && when (s.type) {
        "ai" -> Settings(ctx).let { st -> st.imgKey(st.imgProv).isNotBlank() }
        "custom" -> s.url.contains("{query}") && s.imgPath.isNotBlank()
        else -> key(ctx, s.id).isNotBlank()
    }
    fun usable(ctx: Context) = list(ctx).filter { ready(ctx, it) }

    private fun get(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        val t = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw PublishError(when (code) { 401, 403 -> "Key 无效或没有权限（HTTP $code）"; 429 -> "请求太频繁，稍后再试（HTTP 429）"; else -> "HTTP $code ${t.take(120)}" })
        return t
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** 搜一个来源；page 从 1 开始，每页 4 张。AI 来源生成 1 张存本地 */
    fun search(ctx: Context, s: ImgSource, query: String, page: Int, orient: String): List<Cand> {
        val per = 4
        return when (s.type) {
            "pexafy" -> {
                val o = when (orient) { "landscape" -> "&orientation=landscape"; "portrait" -> "&orientation=portrait"; else -> "" }
                val t = get("https://api.pexafy.com/api/v1/search/photos?q=${enc(query)}&per_page=${(per * page).coerceAtMost(100)}$o", mapOf("x-api-key" to key(ctx, s.id)))
                val j = JSONObject(t); val a = j.optJSONArray("data") ?: JSONArray()
                // Pexafy 用游标分页；这里换一批 = 取更大的 per_page 再跳过前几页，免得保存游标
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.drop(per * (page - 1)).mapNotNull { x ->
                    val u = x.optJSONObject("urls") ?: return@mapNotNull null
                    val reg = u.optString("regular").ifBlank { return@mapNotNull null }
                    val at = x.optJSONObject("attribution")
                    Cand(reg, u.optString("thumb").ifBlank { u.optString("small").ifBlank { reg } }, at?.optString("plain").orEmpty().ifBlank { x.optJSONObject("user")?.optString("name").orEmpty() }, "Pexafy")
                }
            }
            "pexels" -> {
                val o = when (orient) { "landscape" -> "&orientation=landscape"; "portrait" -> "&orientation=portrait"; else -> "" }
                val t = get("https://api.pexels.com/v1/search?query=${enc(query)}&page=$page&per_page=$per$o", mapOf("Authorization" to key(ctx, s.id)))
                val a = JSONObject(t).optJSONArray("photos") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
                    val src = x.optJSONObject("src") ?: return@mapNotNull null
                    Cand(src.optString("large").ifBlank { return@mapNotNull null }, src.optString("medium"), x.optString("photographer"), "Pexels")
                }
            }
            "ai" -> {
                val bmp = kotlinx.coroutines.runBlocking { Ai.image(ctx, "Photorealistic editorial photo, no text: $query", if (orient == "portrait") "3:4" else "4:3") }
                val f = File(PicCache.dir(ctx), "ai_${System.currentTimeMillis()}.jpg"); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                val u = "file://" + f.absolutePath
                listOf(Cand(u, u, "AI 生成", "AI"))
            }
            else -> {
                val k = key(ctx, s.id)
                var url = s.url.replace("{query}", enc(query)).replace("{page}", page.toString())
                val h = mutableMapOf<String, String>()
                if (k.isNotBlank() && s.authName.isNotBlank()) { if (s.auth == "query") url += (if ('?' in url) "&" else "?") + enc(s.authName) + "=" + enc(k) else h[s.authName] = k }
                val t = get(url, h)
                val root: Any = t.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
                JsonPath.zip(root, s.imgPath, s.thumbPath, s.creditPath).take(per).map { (u, th, cr) -> Cand(u, th, cr, s.name) }
            }
        }
    }

    /** 按顺序搜：第一个失败或无结果顺延下一个 */
    fun searchChain(ctx: Context, query: String, page: Int, orient: String): Pair<List<Cand>, String> {
        val srcs = usable(ctx)
        if (srcs.isEmpty()) throw ConfigError("还没有配置图片来源，请到「设置 → 图片来源」填写 Key")
        val (ok, r, errs) = Pics.chain(srcs) { search(ctx, it, query, page, orient) }
        if (ok == null) throw PublishError(errs.joinToString("；") { "${it.first.name}：${it.second}" })
        return r to ok.name
    }
}

/** 插图下载缓存：filesDir/pics/<hash>.jpg */
object PicCache {
    fun dir(ctx: Context) = File(ctx.filesDir, "pics").apply { mkdirs() }
    fun fileFor(ctx: Context, url: String) = if (url.startsWith("file://")) File(url.removePrefix("file://")) else File(dir(ctx), Integer.toHexString(url.hashCode()) + "_" + url.length + ".jpg")
    /** 下载到本地，返回路径（已有就直接返回） */
    fun fetch(ctx: Context, url: String): File {
        val f = fileFor(ctx, url)
        if (f.exists() && f.length() > 0) return f
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        if (c.responseCode !in 200..299) throw PublishError("图片下载失败（HTTP ${c.responseCode}）")
        val tmp = File(f.path + ".tmp"); c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }; tmp.renameTo(f)
        return f
    }
    fun decode(f: File, maxW: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1; while (o.outWidth / (sample * 2) >= maxW) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    fun load(ctx: Context, pic: Pic, maxW: Int = 1080): Bitmap? = try {
        val f = pic.local.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() } ?: fetch(ctx, pic.url)
        decode(f, maxW)
    } catch (_: Exception) { null }
    fun thumb(ctx: Context, url: String): Bitmap? = try { decode(fetch(ctx, url), 400) } catch (_: Exception) { null }
    /** 长图用：段落号 → (图, 图注+来源) */
    fun bitmaps(ctx: Context, pics: List<Pic>, paraCount: Int): Map<Int, List<Pair<Bitmap, String>>> =
        Pics.byPara(pics, paraCount).mapValues { (_, l) -> l.mapNotNull { p -> load(ctx, p, 1000)?.let { it to listOf(p.caption, p.creditLine()).filter { s -> s.isNotBlank() }.joinToString(" · ") } } }
}
