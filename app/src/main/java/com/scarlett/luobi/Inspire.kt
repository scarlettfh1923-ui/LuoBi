package com.scarlett.luobi

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/** 1.10「灵感」：每天抓公开热榜 → 一次 DeepSeek 调用按她的风格挑 10 个选题。没有服务器，结果按天缓存。 */
data class Topic(val title: String, val source: String, val angle: String = "", val view: String = "")
data class HotItem(val title: String, val source: String, val extra: String = "")

/**
 * 热榜来源（2026-10-21 在盒子上实测，均无需登录）：
 * - 微博：weibo.com/ajax/side/hotSearch，必须带 Referer: https://weibo.com/ 与 X-Requested-With，否则 403
 * - 知乎：api.zhihu.com/topstory/hot-lists/total（www 域名的 v3 接口要登录，401）
 * - 抖音：www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/
 * - B站：api.bilibili.com/x/web-interface/wbi/search/square（热搜词；popular 是热门视频，不适合当选题）
 * - 小红书：没有可匿名访问的热榜接口（web 接口要签名 + 登录），不支持
 */
object HotSources {
    data class Src(val id: String, val name: String, val url: String, val headers: Map<String, String>, val parse: (String) -> List<HotItem>)
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun weibo(s: String): List<HotItem> = JSONObject(s).optJSONObject("data")?.optJSONArray("realtime").objs()
        .filter { it.optInt("is_ad", 0) == 0 && it.optString("word").isNotBlank() }.map { HotItem(it.optString("word"), "微博", it.optString("label_name")) }
    fun zhihu(s: String): List<HotItem> = JSONObject(s).optJSONArray("data").objs().mapNotNull { it.optJSONObject("target") }
        .filter { it.optString("title").isNotBlank() }.map { HotItem(it.optString("title"), "知乎", it.optString("excerpt").take(60)) }
    fun douyin(s: String): List<HotItem> = JSONObject(s).optJSONArray("word_list").objs().filter { it.optString("word").isNotBlank() }.map { HotItem(it.optString("word"), "抖音") }
    fun bili(s: String): List<HotItem> = JSONObject(s).optJSONObject("data")?.optJSONObject("trending")?.optJSONArray("list").objs()
        .map { it.optString("show_name").ifBlank { it.optString("keyword") } }.filter { it.isNotBlank() }.map { HotItem(it, "B站") }

    val all = listOf(
        Src("weibo", "微博热搜", "https://weibo.com/ajax/side/hotSearch", mapOf("Referer" to "https://weibo.com/", "X-Requested-With" to "XMLHttpRequest"), ::weibo),
        Src("zhihu", "知乎热榜", "https://api.zhihu.com/topstory/hot-lists/total?limit=50", emptyMap(), ::zhihu),
        Src("douyin", "抖音热榜", "https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/", emptyMap(), ::douyin),
        Src("bili", "B站热搜", "https://api.bilibili.com/x/web-interface/wbi/search/square?limit=30", mapOf("Referer" to "https://www.bilibili.com/"), ::bili),
    )

    private fun get(src: Src): String {
        val c = URL(src.url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000; c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "application/json")
        src.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try { if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
    /** 并行抓取；任何一个来源失败就跳过，返回 [条目, 失败的来源名] */
    suspend fun fetch(enabled: Set<String>, perSource: Int = 20): Pair<List<HotItem>, List<String>> = withContext(Dispatchers.IO) {
        coroutineScope {
            all.filter { it.id in enabled }.map { s -> async { s to runCatching { s.parse(get(s)).take(perSource) } } }.awaitAll()
        }.let { rs -> rs.flatMap { it.second.getOrDefault(emptyList()) } to rs.filter { it.second.isFailure || it.second.getOrNull().isNullOrEmpty() }.map { it.first.name } }
    }
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}

object Inspire {
    const val DEFAULT_PROMPT = """你是一名资深内容编辑，熟悉我的写作风格和关注领域。

下面是今天各平台的热榜（来源｜标题｜补充）：
{热榜}

我最近写过的文章（标题与开头）：
{我的文章}

我常用的写作模板（名称：说明）：
{我的模板}

请从热榜里挑出最适合我来写的 10 个选题，要求：
1. 优先选和我写过的领域、常用模板相契合，并且有讨论空间、能写出观点的话题；
2. 避开纯娱乐八卦、灾难伤亡、敏感时政和无法核实的传闻；
3. 同一件事只保留一条，尽量覆盖不同来源；
4. 每个选题给出：title（选题标题，可在热榜原题基础上改写得更像一篇文章的题目，不超过 24 字）、source（来源平台：微博/知乎/抖音/B站）、angle（可以写的角度，一句话）、view（一个可能的观点，一句话，要有立场）。

只输出一个 JSON 对象，不要解释：
{"topics": [{"title": "……", "source": "……", "angle": "……", "view": "……"}]}"""

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    data class Result(val topics: List<Topic>, val updatedAt: Long, val failed: List<String>, val note: String = "")

    fun cached(ctx: Context): Result? {
        val p = ctx.getSharedPreferences("inspire", Context.MODE_PRIVATE)
        if (p.getString("date", "") != today()) return null
        return runCatching { decode(p.getString("data", "") ?: "") }.getOrNull()
    }
    private fun store(ctx: Context, r: Result) = ctx.getSharedPreferences("inspire", Context.MODE_PRIVATE).edit().putString("date", today()).putString("data", encode(r)).apply()

    fun encode(r: Result): String = JSONObject().put("at", r.updatedAt).put("note", r.note).put("failed", JSONArray(r.failed))
        .put("topics", JSONArray(r.topics.map { JSONObject().put("title", it.title).put("source", it.source).put("angle", it.angle).put("view", it.view) })).toString()
    fun decode(s: String): Result { val j = JSONObject(s); val t = j.getJSONArray("topics")
        return Result((0 until t.length()).map { t.getJSONObject(it).let { o -> Topic(o.optString("title"), o.optString("source"), o.optString("angle"), o.optString("view")) } },
            j.optLong("at"), j.optJSONArray("failed")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(), j.optString("note")) }

    /** 解析模型回复（容错：```json 围栏、topics/选题/list 键、直接数组） */
    fun parse(reply: String): List<Topic> {
        val s = reply.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        val arr: JSONArray? = CleanParse.lenient(s)?.let { j -> listOf("topics", "选题", "list", "items", "data").firstNotNullOfOrNull { j.optJSONArray(it) } }
            ?: runCatching { JSONArray(s.substring(s.indexOf('['), s.lastIndexOf(']') + 1)) }.getOrNull()
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            fun g(vararg k: String) = k.firstNotNullOfOrNull { o.optString(it).takeIf { v -> v.isNotBlank() && v != "null" } } ?: ""
            Topic(g("title", "标题", "选题"), g("source", "来源", "平台"), g("angle", "可以写", "角度"), g("view", "观点", "opinion"))
        }.filter { it.title.isNotBlank() }.distinctBy { it.title }.take(10)
    }

    fun buildPrompt(tpl: String, hot: List<HotItem>, notes: List<Note>, templates: List<Template>): String = tpl
        .replace("{热榜}", hot.joinToString("\n") { "${it.source}｜${it.title}" + if (it.extra.isNotBlank()) "｜${it.extra}" else "" })
        .replace("{我的文章}", notes.take(12).joinToString("\n") { "- ${it.title}：${Md.snippet(it.body).take(40)}" }.ifBlank { "（还没有文章）" })
        .replace("{我的模板}", templates.joinToString("\n") { "- ${it.name}：${it.desc}" })

    /** 每天第一次自动跑；force=true 为下拉刷新 */
    suspend fun load(ctx: Context, force: Boolean): Result {
        if (!force) cached(ctx)?.let { return it }
        val s = Settings(ctx)
        val (hot, failed) = HotSources.fetch(s.inspireSources)
        if (hot.isEmpty()) throw PublishError("热榜都没抓到" + if (failed.isNotEmpty()) "（${failed.joinToString("、")}）" else "" + "，请检查网络后下拉重试")
        val db = AppDb.get(ctx)
        val prompt = buildPrompt(s.inspirePrompt, hot, db.notes().recent(12), db.templates().list())
        val target = runCatching { Ai.target(ctx, "p:deepseek") }.getOrNull()
            ?: s.defaultModel.ifBlank { s.configured().firstOrNull()?.ref ?: "" }.takeIf { it.isNotBlank() }?.let { runCatching { Ai.target(ctx, it) }.getOrNull() }
        val r = if (target == null) {
            // 没有任何 Key：如实显示原始热榜（不编角度/观点），提示去填 Key
            Result(interleave(hot).take(10).map { Topic(it.title, it.source) }, System.currentTimeMillis(), failed, "填写 DeepSeek Key 后，会按你的文章挑选并给出角度和观点")
        } else {
            val topics = parse(Ai.chatText(target, "你是资深内容编辑。只输出一个合法 JSON 对象。", prompt, true))
            if (topics.isEmpty()) throw CleanParse.BadFormat("选题结果格式不对")
            Result(topics, System.currentTimeMillis(), failed)
        }
        store(ctx, r); return r
    }
    /** 各来源轮流取，保证没有 AI 时也覆盖多个平台 */
    fun interleave(hot: List<HotItem>): List<HotItem> { val g = hot.groupBy { it.source }.values.map { it.toMutableList() }; val out = ArrayList<HotItem>()
        while (g.any { it.isNotEmpty() }) g.forEach { if (it.isNotEmpty()) out += it.removeAt(0) }; return out }

    /** 点选题 → 填进输入框的文字 */
    fun fillText(t: Topic) = buildString {
        append("我想写：").append(t.angle.ifBlank { t.title }); append("\n话题：").append(t.title).append("（").append(t.source).append("）")
        if (t.view.isNotBlank()) append("\n我的观点：").append(t.view)
    }
}
