package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject

/** 文中插图：插在第 after 段（Export.paras 索引）之后 */
data class Pic(val after: Int, val url: String, val thumb: String = "", val caption: String = "", val credit: String = "", val source: String = "", val local: String = "") {
    fun json(): JSONObject = JSONObject().put("after", after).put("url", url).put("thumb", thumb).put("caption", caption).put("credit", credit).put("source", source).put("local", local)
    /** 图注下的来源小字，如「图片：Ann / via Pexels」 */
    fun creditLine() = listOf(credit.takeIf { it.isNotBlank() }?.let { "图片：$it" }, source.takeIf { it.isNotBlank() }?.let { "via $it" }).filterNotNull().joinToString(" / ")
    val isWeb get() = url.startsWith("http://") || url.startsWith("https://")
}

/** 候选图 */
data class Cand(val url: String, val thumb: String, val credit: String, val source: String)

/** AI 规划：在第 after 段后插图，query 为英文场景描述 */
data class PicPlan(val after: Int, val query: String)

object Pics {
    fun encode(l: List<Pic>): String = JSONArray().apply { l.forEach { put(it.json()) } }.toString()
    fun decode(s: String?): List<Pic> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> Pic(o.optInt("after", -1), o.optString("url"), o.optString("thumb"), o.optString("caption"), o.optString("credit"), o.optString("source"), o.optString("local")).takeIf { it.after >= 0 && it.url.isNotBlank() } } }
    } catch (_: Exception) { emptyList() }
    /** 按段落归组；越界的放到最后一段后 */
    fun byPara(l: List<Pic>, paraCount: Int): Map<Int, List<Pic>> = l.groupBy { it.after.coerceIn(0, (paraCount - 1).coerceAtLeast(0)) }

    /** AI 返回的插图规划，容错：代码块围栏、前后废话、数组或 {images:[...]}、各种字段名、段落号越界、重复 */
    fun parsePlan(text: String, paraCount: Int, min: Int, max: Int): List<PicPlan> {
        val t = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").replace(Regex("```[a-zA-Z]*"), "")
        val arr: JSONArray? = run {
            val ob = CleanParse.lenient(t)
            val fromObj = ob?.let { o -> listOf("images", "illustrations", "items", "plan", "pictures", "data").firstNotNullOfOrNull { o.optJSONArray(it) } }
            fromObj ?: run {
                val a = t.indexOf('['); val b = t.lastIndexOf(']')
                if (a >= 0 && b > a) try { JSONArray(t.substring(a, b + 1)) } catch (_: Exception) { null } else null
            } ?: ob?.let { o -> if (o.has("query") || o.has("description")) JSONArray().put(o) else null }
        }
        if (arr == null || paraCount <= 0) return emptyList()
        val out = LinkedHashMap<Int, PicPlan>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = listOf("after", "index", "paragraph", "para", "position", "paragraph_index").firstNotNullOfOrNull { k -> if (o.has(k)) o.opt(k).toString().filter { it.isDigit() }.toIntOrNull() else null } ?: continue
            val q = listOf("query", "description", "scene", "prompt", "desc", "en").firstNotNullOfOrNull { k -> o.optString(k).trim().takeIf { it.isNotEmpty() } } ?: continue
            val p = idx.coerceIn(0, paraCount - 1)
            if (p !in out) out[p] = PicPlan(p, q.take(200))
        }
        return out.values.take(max.coerceAtLeast(1)).sortedBy { it.after }
    }

    fun planPrompt(paras: List<String>, min: Int, max: Int, orient: String): String {
        val list = paras.mapIndexed { i, p -> "[$i] ${p.replace("\n", " ").take(160)}" }.joinToString("\n")
        val o = when (orient) { "landscape" -> "横图"; "portrait" -> "竖图"; else -> "不限方向" }
        return """通读下面这篇文章（每段前是段落编号），挑出最适合配图的 $min–$max 个位置，图片插在该段之后。
为每处写一句英文场景描述，用于图库搜索（6–12 个英文单词，具体、可拍摄，不要抽象概念，不要文字）。偏好：$o。
只输出 JSON：{"images":[{"after":段落编号,"query":"english scene description"}]}

$list"""
    }

    /** 依次尝试来源：失败（异常）或无结果就顺延下一个；返回 (成功来源, 结果, 每个来源的错误) */
    fun <S, T> chain(sources: List<S>, fetch: (S) -> List<T>): Triple<S?, List<T>, List<Pair<S, String>>> {
        val errs = mutableListOf<Pair<S, String>>()
        for (s in sources) {
            try { val r = fetch(s); if (r.isNotEmpty()) return Triple(s, r, errs); errs += s to "无结果" }
            catch (e: Exception) { errs += s to (e.message ?: e.javaClass.simpleName) }
        }
        return Triple(null, emptyList(), errs)
    }
}

/** 字段路径：results[].urls.regular、data.items[0].src、[].url；返回所有命中的字符串 */
object JsonPath {
    fun get(root: Any?, path: String): List<String> {
        val parts = path.trim().removePrefix("$").removePrefix(".").split('.').filter { it.isNotEmpty() }
        var cur: List<Any?> = listOf(root)
        for (raw in parts) {
            val m = Regex("^([^\\[]*)((\\[\\d*\\])*)$").find(raw) ?: return emptyList()
            val key = m.groupValues[1]; val idx = Regex("\\[(\\d*)\\]").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            cur = cur.flatMap { v ->
                var x: List<Any?> = listOf(if (key.isEmpty()) v else (v as? JSONObject)?.opt(key))
                for (ix in idx) x = x.flatMap { a ->
                    val arr = a as? JSONArray ?: return@flatMap emptyList<Any?>()
                    if (ix.isEmpty()) (0 until arr.length()).map { arr.opt(it) } else listOfNotNull(arr.opt(ix.toInt()))
                }
                x
            }.filter { it != null && it != JSONObject.NULL }
        }
        return cur.mapNotNull { v -> when (v) { is String -> v; is Number, is Boolean -> v.toString(); else -> null } }.filter { it.isNotBlank() }
    }
    /** 由图片路径推出“每一项”的前缀：results[].urls.regular → 每个 results 元素；相对字段对齐取 */
    fun zip(root: Any?, img: String, thumb: String, credit: String): List<Triple<String, String, String>> {
        val imgs = get(root, img)
        val th = if (thumb.isBlank()) emptyList() else get(root, thumb)
        val cr = if (credit.isBlank()) emptyList() else get(root, credit)
        return imgs.mapIndexed { i, u -> Triple(u, th.getOrNull(i) ?: u, cr.getOrNull(i) ?: "") }
    }
}
