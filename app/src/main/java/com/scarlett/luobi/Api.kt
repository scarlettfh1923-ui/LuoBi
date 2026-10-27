package com.scarlett.luobi

import kotlinx.coroutines.ensureActive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Ai {
    /** Google / OpenAI 这类需要海外网络的服务商：连不上时给出专门的中文说明 */
    private fun overseas(host: String) = host.endsWith("googleapis.com") || host.endsWith("openai.com") || host.endsWith("google.com")
    /** 测试用：替换 chat 请求的 HTTP（返回 code 与正文）；正常为 null */
    internal var chatTransport: ((url: String, body: String) -> Pair<Int, String>)? = null
    private fun http(method: String, url: String, key: String, body: JSONObject?, headers: Map<String, String> = emptyMap(), bearer: Boolean = true): JSONObject {
        val u = URL(url); val host = u.host.orEmpty()
        var code: Int; var txt: String
        val fake = chatTransport
        if (fake != null) { val r = fake(url, body?.toString().orEmpty()); code = r.first; txt = r.second } else
        try {
            val c = u.openConnection() as HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 180000
            c.setRequestProperty("Content-Type", "application/json")
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            code = c.responseCode
            txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        } catch (e: java.io.IOException) {
            if (overseas(host)) throw OverseasNetError(host, e) else throw e
        }
        if (code !in 200..299) throw ApiError(code, txt, host)
        Relay.fromSse(txt)?.let { return it }  // 1.15.1：中转站可能强制流式
        val j = try { JSONObject(txt.ifBlank { "{}" }) } catch (_: Exception) {
            // Gemini 兼容接口偶尔把对象包在数组里
            try { org.json.JSONArray(txt).optJSONObject(0) ?: throw ApiError(-1, txt, host) } catch (_: Exception) { throw ApiError(-1, txt, host) }
        }
        // 少数服务商 HTTP 200 也把错误放在 error 字段里
        if (j.opt("error") != null && j.opt("error") != JSONObject.NULL && !j.has("choices") && !j.has("data") && !j.has("output") && !j.has("images") && !j.has("candidates")) throw ApiError(code, txt, host)
        return j
    }
    // ---------- 1.15.1 Kimi ----------
    // 官方「模型参数参考」：kimi-k3 始终推理（reasoning_effort low/high/max，默认 max），temperature/top_p 固定、不要传；
    // kimi-k2.6 默认开启思考，可 thinking:{type:disabled}；kimi-k2.7-code 始终思考、不接受 disabled。
    // 「思考模型」：reasoning_content 的 token 也算进 max_tokens，建议 ≥16000；max_tokens 已弃用，改用 max_completion_tokens。
    const val KIMI_MIN_TOKENS = 16000
    const val KIMI_DEFAULT_TOKENS = 32768
    val KIMI_RETIRED = setOf("kimi-k2.5", "kimi-latest", "kimi-thinking-preview", "kimi-k2-0905-preview", "kimi-k2-0711-preview", "kimi-k2-turbo-preview", "kimi-k2-thinking", "kimi-k2-thinking-turbo")
    /** 已下线的 Kimi 型号自动换成 kimi-k3 */
    fun kimiModel(m: String): String = m.trim().let { if (it.isBlank() || it in KIMI_RETIRED || it.startsWith("moonshot-v1")) "kimi-k3" else it }
    fun kimiBody(model: String, msgs: JSONArray, json: Boolean, maxTokens: Int?, quick: Boolean): JSONObject {
        val m = kimiModel(model)
        val b = JSONObject().put("model", m).put("messages", msgs)
        if (json) b.put("response_format", JSONObject().put("type", "json_object"))
        when {
            m.startsWith("kimi-k3") -> if (quick) b.put("reasoning_effort", "low")
            m.startsWith("kimi-k2.6") -> b.put("thinking", JSONObject().put("type", "disabled"))
            // k2.7-code 等：始终思考，不传 thinking
        }
        // 不传 temperature / top_p / n / penalty（这些模型固定，传别的值会报错）
        b.put("max_completion_tokens", maxOf(maxTokens ?: KIMI_DEFAULT_TOKENS, KIMI_MIN_TOKENS))
        return b
    }
    /** 1.16.2 文案生成用的 DeepSeek 设置：深度思考开关 + 温度 */
    data class DsGen(val think: Boolean, val temp: Double)
    const val DS_THINK_TOKENS = 65536
    /** 2.2：非思考模式的成稿额度（DeepSeek 默认只有 8K）：篇幅上限 ×4，夹在 8192–32768 */
    fun writeTokens(rangeMax: Int) = (rangeMax * 4).coerceIn(8192, 32768)
    /** 2.2：被截断（finish_reason=length）的文章 id，存在 SharedPreferences，不动数据库 */
    object Truncated {
        private fun p(ctx: Context) = ctx.getSharedPreferences("truncated", Context.MODE_PRIVATE)
        fun mark(ctx: Context, id: Long) = p(ctx).edit().putBoolean(id.toString(), true).apply()
        fun clear(ctx: Context, id: Long) = p(ctx).edit().remove(id.toString()).apply()
        fun has(ctx: Context, id: Long) = p(ctx).getBoolean(id.toString(), false)
    }
    fun dsGen(ctx: Context) = Settings(ctx).let { DsGen(it.dsThink, it.dsTemp.toDouble()) }
    fun chatBody(pid: String, model: String, msgs: JSONArray, json: Boolean, maxTokens: Int?, plain: Boolean = false, ds: DsGen? = null): JSONObject {
        if (pid == "kimi") return kimiBody(model, msgs, json && !plain, maxTokens, quick = maxTokens != null && maxTokens < 1000)
        val b = JSONObject().put("model", model).put("messages", msgs)
        if (plain) { if (maxTokens != null) b.put(if (pid == "openai") "max_completion_tokens" else "max_tokens", maxTokens); return b }
        if (json) b.put("response_format", JSONObject().put("type", "json_object"))
        if (pid == "deepseek" && ds != null) {
            if (ds.think) {
                // 官方：thinking enabled + reasoning_effort；思考模式不支持 temperature（传了也无效），不传
                b.put("thinking", JSONObject().put("type", "enabled")).put("reasoning_effort", "high")
                b.put("max_tokens", maxOf(maxTokens ?: 0, DS_THINK_TOKENS))
            } else {
                b.put("thinking", JSONObject().put("type", "disabled")).put("temperature", Math.round(ds.temp.coerceIn(0.0, 2.0) * 10) / 10.0)
                if (maxTokens != null) b.put("max_tokens", maxTokens)
            }
            return b
        }
        when (pid) {
            "deepseek" -> b.put("thinking", JSONObject().put("type", "disabled"))
            "qwen" -> b.put("enable_thinking", false)
            "glm" -> b.put("thinking", JSONObject().put("type", "disabled"))
            "doubao" -> b.put("thinking", JSONObject().put("type", "disabled"))
            "silicon" -> b.put("enable_thinking", false)
        }
        if (pid in setOf("deepseek", "qwen", "glm", "silicon", "doubao")) b.put("temperature", 1.0)
        if (maxTokens != null) b.put(if (pid == "openai") "max_completion_tokens" else "max_tokens", maxTokens)
        return b
    }
    /** 一个具体的调用目标 */
    data class Target(val pid: String, val name: String, val url: String, val key: String, val model: String)

    fun target(ctx: Context, ref: String): Target {
        val s = Settings(ctx)
        return if (ref.startsWith("c:")) {
            val cm = s.customs.firstOrNull { it.id == ref.removePrefix("c:") } ?: throw ConfigError("这个自定义模型已被删除，请在第 ③ 步重新选一个模型。")
            val key = s.customKey(cm.id)
            if (key.isBlank()) throw ConfigError("还没有填写「${cm.name}」的 Key。请到「设定 → 模型与密钥」里填写。")
            Target("custom", cm.name.ifBlank { "中转站" }, Relay.base(cm.url), key, cm.model.trim())
        } else {
            val pid = ref.removePrefix("p:"); val p = Providers.text(pid)
            val key = s.key(pid)
            if (key.isBlank()) throw ConfigError("还没有填写 ${p.name} 的 API Key。请到「设定 → 模型与密钥」里粘贴你的 Key。")
            Target(pid, p.name, s.url(pid).trim().trimEnd('/'), key, s.model(pid).trim())
        }
    }

    /** OpenAI 兼容 chat/completions，返回文本 */
    suspend fun chatText(t: Target, system: String?, user: String, json: Boolean, maxTokens: Int? = null, ds: DsGen? = null, fin: Array<String>? = null): String = withContext(Dispatchers.IO) {
        val msgs = JSONArray()
        if (system != null) msgs.put(JSONObject().put("role", "system").put("content", system))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val r = try {
            try { http("POST", t.url + "/chat/completions", t.key, chatBody(t.pid, t.model, msgs, json, maxTokens, plain = t.pid == "custom" && !json, ds = ds)) }
            catch (e: ApiError) { if (e.code == 400) http("POST", t.url + "/chat/completions", t.key, chatBody(t.pid, t.model, msgs, false, maxTokens, true)) else throw e }
        } catch (e: Exception) {
            if (t.pid == "custom") Relay.explain(e, t)?.let { throw ConfigError(it) }
            throw e
        }
        val ch = r.optJSONArray("choices")?.optJSONObject(0) ?: throw AiFormatError("没有返回任何回答", r.toString().take(200))
        val content = ch.optJSONObject("message")?.optString("content").orEmpty()
        val fr = ch.optString("finish_reason").lowercase()
        fin?.set(0, fr)
        if (fr.contains("content_filter") || fr.contains("sensitive")) throw ContentBlocked("finish_reason=$fr")
        if (content.isBlank()) {
            // 推理模型：思考过程在 reasoning_content 里，绝不当成回答
            val thought = ch.optJSONObject("message")?.optString("reasoning_content").orEmpty()
            val msg = when {
                fr == "length" && thought.isNotBlank() -> "${t.name} 把输出额度都用在思考上了，回答被截断（finish_reason=length）。请重试，或换「通用」型号"
                fr == "length" -> "回答被截断，内容为空"
                else -> "回答是空的"
            }
            throw AiFormatError(msg, "finish_reason=$fr")
        }
        content
    }

    /**
     * 1.18：流式生成（stream:true，逐行读 data:），每收到一段就把累计文本回调给 onDelta，用于进度卡的实时字数 / 打字行。
     * 任何异常或没拿到正文时退回普通（非流式）请求，保证不比 1.17 差。
     */
    suspend fun chatStream(t: Target, system: String?, user: String, json: Boolean, ds: DsGen? = null, maxTokens: Int? = null, fin: Array<String>? = null, onDelta: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val msgs = JSONArray()
        if (system != null) msgs.put(JSONObject().put("role", "system").put("content", system))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val acc = StringBuilder()
        fun eat(line: String) {
            val l = line.trim(); if (!l.startsWith("data:")) return
            val d = l.removePrefix("data:").trim(); if (d.isEmpty() || d == "[DONE]") return
            val j = try { JSONObject(d) } catch (_: Exception) { return }
            val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: return
            if (!ch.isNull("finish_reason")) ch.optString("finish_reason").takeIf { it.isNotBlank() }?.let { fin?.set(0, it.lowercase()) }
            val dl = ch.optJSONObject("delta") ?: return
            val piece = if (dl.isNull("content")) "" else dl.optString("content")
            if (piece.isNotEmpty()) { acc.append(piece); onDelta(acc.toString()) }
        }
        val got = try {
            val body = chatBody(t.pid, t.model, msgs, json, maxTokens, plain = t.pid == "custom" && !json, ds = ds).put("stream", true)
            val fake = chatTransport
            if (fake != null) {
                val r = fake(t.url + "/chat/completions", body.toString())
                if (r.first !in 200..299) throw ApiError(r.first, r.second, "")
                if (r.second.contains("data:")) r.second.lineSequence().forEach { eat(it) }
                else JSONObject(r.second).optJSONArray("choices")?.optJSONObject(0)?.let { c0 -> c0.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { fin?.set(0, it.lowercase()) }
                    c0.optJSONObject("message")?.optString("content")?.let { acc.append(it); onDelta(acc.toString()) } }
            } else {
                val c = URL(t.url + "/chat/completions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 180000
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "text/event-stream")
                c.setRequestProperty("Authorization", "Bearer ${t.key}")
                c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode !in 200..299) throw ApiError(c.responseCode, c.errorStream?.bufferedReader()?.readText().orEmpty(), "")
                c.inputStream.bufferedReader().useLines { ls -> ls.forEach { ensureActive(); eat(it) } }
            }
            acc.toString()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
        if (got.isNotBlank()) got else chatText(t, system, user, json, maxTokens, ds, fin)
    }

    /** 中转站模型列表：GET {base}/models */
    fun listModels(base: String, key: String): List<String> {
        val j = http("GET", Relay.base(base) + "/models", key, null)
        val a = j.optJSONArray("data") ?: j.optJSONArray("models") ?: org.json.JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } ?: a.optString(it).takeIf { s -> s.isNotBlank() && !s.startsWith("{") } }.distinct().sorted()
    }

    /** 真实最小调用：返回 (耗时ms, 说明) */
    suspend fun test(t: Target): Pair<Long, String> {
        val t0 = System.currentTimeMillis()
        val mt = when (t.pid) { "openai" -> 16; "gemini" -> 64; "custom" -> 512; else -> 8 } // 中转站后面可能是推理模型，给点余量
        chatText(t, null, "你好，回复“好”", false, mt)
        return (System.currentTimeMillis() - t0) to "${t.model} 已回复"
    }

    /** 1.9：意图提取。优先 DeepSeek；没填 DeepSeek Key 时用所选模型 */
    suspend fun extract(ctx: Context, raw: String, fallbackRef: String): CleanResult {
        val db = AppDb.get(ctx); Defaults.ensure(db, ctx)
        val ct = db.cleans().active()
        val t = try { target(ctx, "p:deepseek") } catch (e: ConfigError) {
            val s = Settings(ctx)
            val ref = fallbackRef.ifBlank { s.defaultModel }.ifBlank { s.configured().firstOrNull()?.ref ?: "" }
            if (ref.isBlank()) throw ConfigError("还没有可用的模型。请到「设定 → 模型与密钥」填写 DeepSeek 或其它模型的 Key。")
            target(ctx, ref)
        }
        val list = db.templates().list().joinToString("\n") { "- ${it.name}：${it.desc}" }
        // 1.10：旧版「清洗」提示词（输出 cleaned、可能把三项融合成一段）不再用于提取，改用意图提取默认提示词；她的旧文本已备份
        val base = ct?.prompt?.takeIf { it.isNotBlank() && !Defaults.isOldClean(it) } ?: Defaults.INTENT
        val prompt = Vars.fillClean(base, raw, list) + IntentParse.FORMAT
        val txt = chatText(t, "你是中文写作助理。只输出一个合法 JSON 对象。" + Prompts22.NO_FAB_LINE, prompt, true)
        val r = IntentParse.parse(txt, raw)
        // 2.3：模型偶尔还会推荐旧类型名 → 换成最接近的方向
        val names = db.templates().list().map { it.name }.toSet()
        return if (r.recommend.isNotBlank() && r.recommend !in names && Prompts23.MAP[r.recommend] in names) r.copy(recommend = Prompts23.MAP[r.recommend]!!) else r
    }

    val OUT_RULE = "只输出一个 JSON 对象：{\"title\":\"标题\",\"body\":\"正文 Markdown：段落之间空一行；小标题单独一行，以 ## 开头（下一级用 ###）；小标题必须是有内容的短语（概括这一节讲什么），不要只写数字或序号（如 ## 1、## 一、## 01）；全文挑 1–3 句最有力的金句用 **加粗**（金句放在段落里加粗，或单独一行用 > 引用；金句绝不能用 # 当标题）；需要时可用 > 引用、- 列表、--- 分隔线；不要表格和代码块\",\"tags\":[\"标签1\",\"标签2\"],\"category\":\"分类\"}，tags 为 4–5 个不带 # 的主题标签；category 必须从下列 15 个里原样选 1 个：" + Categories.LIST.joinToString("、") + "，不要输出其它内容。"

    /** 最终提示词 = 模板填值（空变量整行删）+ 篇幅 */
    fun finalPrompt(tpl: String, values: Map<String, String>, cleaned: String, attachRaw: Boolean, length: Int, range: Pair<Int, Int> = Lengths.DEFAULT[length.coerceIn(0, 2)], known: Set<String> = Vars.defaultKnown): String {
        val v = values.toMutableMap(); if (attachRaw) v[Vars.RAW] = cleaned
        val body = Vars.fill(tpl, v, known + Vars.RAW).trimEnd()
        return if (Lengths.inTemplate(tpl)) body else body + "\n" + Lengths.hint(length, range)
    }

    /** 用模板库里当前版本重建提示词；模板已删或是旧类型 / 已移除的模板 → 按名字落到最接近的 2.3 方向 */
    suspend fun current(ctx: Context, d: GenJob): String? {
        if (d.kind != "write") return null
        val db = AppDb.get(ctx); val s = Settings(ctx)
        val tpl = if (d.templateId > 0) db.templates().get(d.templateId) else null
        if (tpl != null) return Compose.prompt(tpl.name, tpl.desc, tpl.prompt, d.values, d.raw, s.length, s.lengthRange(), xhs = s.xhsMode)
        val name = tpl?.name ?: d.templateName
        if (name.isBlank()) return null
        val dir = Prompts23.nearest(name)
        val live = db.templates().list().firstOrNull { it.builtIn && it.name == dir.name }
        return Compose.prompt(dir.name, live?.desc ?: dir.desc, live?.prompt ?: dir.module, d.values, d.raw, s.length, s.lengthRange(), xhs = s.xhsMode || Prompts23.isXhs(name))
    }

    /** 2.3 生成阶段：1 定提纲，2 成文，3 校对（可选） */
    object Stage { const val OUTLINE = 1; const val WRITE = 2; const val POLISH = 3
        fun names(total: Int) = listOf("定提纲", "成文", "校对").take(total) }
    /** 2.3：提纲存在本机（按文章 id），阅读页可折叠查看；不进数据库 */
    object Outline {
        private fun p(c: Context) = c.getSharedPreferences("outline", Context.MODE_PRIVATE)
        fun save(c: Context, id: Long, o: String) { if (o.isNotBlank()) p(c).edit().putString(id.toString(), o).apply() }
        fun get(c: Context, id: Long): String? = p(c).getString(id.toString(), null)
        /** 提纲 JSON → 给人看的几行（解析失败就原样） */
        fun pretty(o: String): String = try {
            val j = JSONObject(o.substring(o.indexOf('{'), o.lastIndexOf('}') + 1))
            val lab = linkedMapOf("core" to "核心观点", "angle" to "切口", "hook" to "开头", "conflict" to "冲突", "answer" to "回答疑惑", "sections" to "小节", "gold" to "金句", "ending" to "结尾")
            lab.mapNotNull { (k, n) -> val v = j.opt(k) ?: return@mapNotNull null
                val t = if (v is JSONArray) (0 until v.length()).joinToString("\n") { "· " + v.optString(it) } else v.toString()
                if (t.isBlank()) null else "$n：" + (if (v is JSONArray) "\n$t" else t) }.joinToString("\n\n")
        } catch (_: Exception) { o.trim() }
    }

    /**
     * 第 ③ 步：生成并存入文案库，返回 note id。
     * 2.3：两步——① 定提纲（JSON，非流式）→ ② 按提纲成文（流式，进度卡实时字数）；设置里打开「校对」再加 ③ 最小改动校对（解析失败就用初稿）。
     * 任何一步失败都整篇失败（不存半成品）；取消时协程取消直接抛出。
     */
    suspend fun write(ctx: Context, d: GenJob, onDelta: ((String) -> Unit)? = null, onStage: (Int, Int) -> Unit = { _, _ -> }): Long {
        val t = target(ctx, d.model)
        val s = Settings(ctx)
        // 1.4：每次（含失败重试）都按「当前」模板重新拼提示词，不用排队时存下的旧快照
        val prompt = current(ctx, d) ?: d.finalPrompt
        val polish = s.polish23
        val total = if (polish) 3 else 2
        val fin = arrayOf("")
        val mt = if (t.pid == "deepseek") writeTokens(s.lengthRange().second) else null
        val sys = Prompts23.system()
        onStage(Stage.OUTLINE, total)
        val outline = chatText(t, sys, Prompts23.outlinePrompt(prompt), true, if (t.pid == "deepseek") 4096 else null, dsGen(ctx))
        kotlin.coroutines.coroutineContext.ensureActive()
        onStage(Stage.WRITE, total)
        val wp = Prompts23.writePrompt(prompt, outline)
        var txt = if (onDelta != null) chatStream(t, sys, wp, true, dsGen(ctx), mt, fin, onDelta) else chatText(t, sys, wp, true, mt, dsGen(ctx), fin)
        var a = ArticleParse.parse(txt, d.cleaned.take(20))
        if (a.body.isBlank()) throw AiFormatError("缺少正文", txt.take(200))
        if (polish && fin[0] != "length") {
            kotlin.coroutines.coroutineContext.ensureActive()
            onStage(Stage.POLISH, total)
            val f2 = arrayOf("")
            val p = try { chatText(t, sys, Prompts23.polishPrompt(prompt, txt), true, mt, dsGen(ctx), f2) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "" }
            val b = if (p.isBlank() || f2[0] == "length") null else ArticleParse.parse(p, a.title)
            // 校对稿明显变短（少于初稿六成）或没正文 → 当它失败，用初稿
            if (b != null && b.body.isNotBlank() && b.body.length >= a.body.length * 0.6) { a = b.copy(tags = b.tags.ifEmpty { a.tags }, category = b.category.ifBlank { a.category }); txt = p }
        }
        val db = AppDb.get(ctx)
        val n = Note(title = a.title, body = a.body, tags = a.tags.joinToString(" "), raw = d.raw, cleaned = d.cleaned,
            intent = JSONObject(d.values as Map<*, *>).toString(), category = a.category, categoryAi = a.category, templateId = d.templateId, templateName = d.templateName, model = "${t.name} · ${t.model}", finalPrompt = prompt)
        val id = db.notes().insert(n); db.notes().update(n.copy(id = id, rootId = id))
        Outline.save(ctx, id, outline)
        if (fin[0] == "length") Truncated.mark(ctx, id)
        return id
    }

    /** 2.2：「续写」用的特殊改写指令 */
    const val CONTINUE = "__continue__"
    /** 改写：基于某版本 + 指令，存成新版本 */
    suspend fun rewrite(ctx: Context, noteId: Long, instruction: String, modelRef: String): Long {
        val db = AppDb.get(ctx)
        val n = db.notes().get(noteId) ?: throw ConfigError("这篇文章已被删除")
        val ref = modelRef.ifBlank { Settings(ctx).defaultModel.ifBlank { Settings(ctx).configured().firstOrNull()?.ref ?: "p:deepseek" } }
        val t = target(ctx, ref)
        val fin = arrayOf("")
        val mt = if (t.pid == "deepseek") writeTokens(maxOf(Settings(ctx).lengthRange().second, n.body.length + 800)) else null
        val cont = instruction == CONTINUE
        val txt: String; val a: Article
        if (cont) {
            txt = chatText(t, Prompts23.system(), Prompts22.continuePrompt(n.title, n.body), false, mt, dsGen(ctx), fin)
            val more = Sanitize.body(txt.replace(Regex("^\\s*[{\\[][\\s\\S]*\"body\"\\s*:\\s*\""), ""), n.title)
            a = Article(n.title, n.body.trimEnd() + more.let { if (n.body.trimEnd().lastOrNull()?.let { c -> c in "。！？”…」）" } == true) "\n\n$it" else it }, n.tagList(), n.category)
        } else {
            val prompt = "下面是一篇文章，请按要求修改。\n修改要求：$instruction\n保持事实和观点不变，不要添加原文没有的信息。\n\n标题：${n.title}\n\n正文：\n${n.body}\n\n标签：${n.tagList().joinToString(" ")}"
            txt = chatText(t, Prompts23.system() + "\n\n你现在是在修改一篇已有的文章。", prompt, true, mt, dsGen(ctx), fin)
            a = ArticleParse.parse(txt, n.title)
        }
        val v = (db.notes().maxVersion(n.root) ?: n.version) + 1
        val nid = db.notes().insert(n.copy(id = 0, createdAt = System.currentTimeMillis(), title = a.title, body = a.body,
            tags = (a.tags.ifEmpty { n.tagList() }).joinToString(" "), rootId = n.root, version = v, instruction = if (cont) "续写被截断的部分" else instruction, model = "${t.name} · ${t.model}", factReportJson = "", category = if (n.category.isNotBlank() && n.category != n.categoryAi) n.category else a.category.ifBlank { n.category }, categoryAi = a.category.ifBlank { n.categoryAi }))
        if (fin[0] == "length") Truncated.mark(ctx, nid)
        return nid
    }

    /** 在任意嵌套的 JSON 里找第一张 base64 图片（Gemini generateContent 的 inlineData / Interactions 的 image 块都适用） */
    private fun findImage(o: Any?): String? = when (o) {
        is JSONObject -> {
            val mime = o.optString("mimeType").ifBlank { o.optString("mime_type") }
            val d = o.optString("data")
            if (d.length > 100 && (mime.startsWith("image") || o.optString("type") == "image")) d
            else o.keys().asSequence().toList().firstNotNullOfOrNull { k -> if (k == "thought" ) null else findImage(o.opt(k)) }
        }
        is JSONArray -> (0 until o.length()).firstNotNullOfOrNull { findImage(o.opt(it)) }
        else -> null
    }
    private fun googleImage(url: String, key: String, model: String, prompt: String, aspect: String): String {
        val h = mapOf("x-goog-api-key" to key)
        fun body(img: Boolean) = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseModalities", if (img) JSONArray().put("IMAGE") else JSONArray().put("TEXT").put("IMAGE"))
                .apply { if (img) put("imageConfig", JSONObject().put("aspectRatio", aspect)) })
        val ep = "$url/models/$model:generateContent"
        val r = try { http("POST", ep, key, body(true), h, bearer = false) } catch (e: ApiError) {
            // 个别模型不认 imageConfig / 只出图：去掉再试一次
            val low = e.raw.lowercase()
            if (e.code == 400 && !low.contains("api_key") && !low.contains("location") && (low.contains("modalit") || low.contains("image_config") || low.contains("imageconfig") || low.contains("aspect")))
                http("POST", ep, key, body(false), h, bearer = false) else throw e
        }
        findImage(r.optJSONArray("candidates"))?.let { return it }
        val block = r.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        val fr = r.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
        if (block.isNotBlank() || fr.uppercase() in setOf("SAFETY", "IMAGE_SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_PROHIBITED_CONTENT"))
            throw ContentBlocked("blockReason=$block finishReason=$fr")
        val said = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.let { ps -> (0 until ps.length()).joinToString(" ") { ps.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
        throw AiFormatError("Google 只回了文字，没有返回图片", said.ifBlank { r.toString() }.take(200))
    }

    suspend fun image(ctx: Context, prompt: String, aspect: String = "3:4"): Bitmap = withContext(Dispatchers.IO) {
        val st = Settings(ctx); val pid = st.imgProv; val key = st.imgKey(pid); val model = st.imgModel(pid); val url = Providers.image(pid).url
        if (key.isBlank()) throw ConfigError("还没有填写配图模型 ${Providers.image(pid).name} 的 Key。请到「设定 → 模型与密钥 → 配图模型」填写。")
        var imgUrl: String? = null; var b64: String? = null
        when (pid) {
            "google" -> b64 = googleImage(url, key, model, prompt, aspect)
            "wanx" -> {
                val r = http("POST", "$url/api/v1/services/aigc/text2image/image-synthesis", key,
                    JSONObject().put("model", model).put("input", JSONObject().put("prompt", prompt))
                        .put("parameters", JSONObject().put("size", "768*1024").put("n", 1)), mapOf("X-DashScope-Async" to "enable"))
                val tid = r.optJSONObject("output")?.optString("task_id")?.takeIf { it.isNotBlank() } ?: throw AiFormatError("万相没有返回任务编号", r.toString().take(200))
                var u: String? = null
                for (i in 0 until 60) {
                    delay(2000)
                    val o = http("GET", "$url/api/v1/tasks/$tid", key, null).optJSONObject("output") ?: continue
                    when (o.optString("task_status")) {
                        "SUCCEEDED" -> { u = o.optJSONArray("results")?.optJSONObject(0)?.optString("url"); break }
                        "FAILED", "CANCELED", "UNKNOWN" -> throw TaskError(listOf(o.optString("code"), o.optString("message")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { o.optString("task_status") })
                    }
                }
                imgUrl = u ?: throw java.net.SocketTimeoutException("万相出图 2 分钟仍未完成")
            }
            else -> {
                val body = JSONObject().put("model", model).put("prompt", prompt)
                when (pid) {
                    "silicon" -> body.put("image_size", "768x1024").put("batch_size", 1)
                    "seedream" -> body.put("size", "1728x2304").put("watermark", false).put("response_format", "url")
                    "cogview" -> body.put("size", if (model == "glm-image") "1088x1472" else "864x1152")
                }
                val r = http("POST", "$url/images/generations", key, body)
                val o = (r.optJSONArray("data") ?: r.optJSONArray("images"))?.optJSONObject(0) ?: throw AiFormatError("生图接口没有返回图片", r.toString().take(200))
                b64 = o.optString("b64_json").ifEmpty { null }; imgUrl = o.optString("url").ifEmpty { null }
            }
        }
        val bytes = b64?.let { Base64.decode(it, Base64.DEFAULT) } ?: URL(imgUrl ?: throw AiFormatError("生图接口没有返回图片地址")).openStream().use { it.readBytes() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw AiFormatError("下载到的图片无法打开")
    }
}
