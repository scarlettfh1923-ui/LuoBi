package com.scarlett.luobi

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
    private fun http(method: String, url: String, key: String, body: JSONObject?, headers: Map<String, String> = emptyMap(), bearer: Boolean = true): JSONObject {
        val u = URL(url); val host = u.host.orEmpty()
        val code: Int; val txt: String
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
        val j = try { JSONObject(txt.ifBlank { "{}" }) } catch (_: Exception) {
            // Gemini 兼容接口偶尔把对象包在数组里
            try { org.json.JSONArray(txt).optJSONObject(0) ?: throw ApiError(-1, txt, host) } catch (_: Exception) { throw ApiError(-1, txt, host) }
        }
        // 少数服务商 HTTP 200 也把错误放在 error 字段里
        if (j.opt("error") != null && j.opt("error") != JSONObject.NULL && !j.has("choices") && !j.has("data") && !j.has("output") && !j.has("images") && !j.has("candidates")) throw ApiError(code, txt, host)
        return j
    }
    private fun chatBody(pid: String, model: String, msgs: JSONArray, json: Boolean, maxTokens: Int?, plain: Boolean = false): JSONObject {
        val b = JSONObject().put("model", model).put("messages", msgs)
        if (plain) { if (maxTokens != null) b.put(if (pid == "openai") "max_completion_tokens" else "max_tokens", maxTokens); return b }
        if (json) b.put("response_format", JSONObject().put("type", "json_object"))
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
            if (key.isBlank()) throw ConfigError("还没有填写「${cm.name}」的 Key。请到「设置 → 模型与密钥」里填写。")
            Target("custom", cm.name, cm.url.trim().trimEnd('/'), key, cm.model.trim())
        } else {
            val pid = ref.removePrefix("p:"); val p = Providers.text(pid)
            val key = s.key(pid)
            if (key.isBlank()) throw ConfigError("还没有填写 ${p.name} 的 API Key。请到「设置 → 模型与密钥」里粘贴你的 Key。")
            Target(pid, p.name, s.url(pid).trim().trimEnd('/'), key, s.model(pid).trim())
        }
    }

    /** OpenAI 兼容 chat/completions，返回文本 */
    suspend fun chatText(t: Target, system: String?, user: String, json: Boolean, maxTokens: Int? = null): String = withContext(Dispatchers.IO) {
        val msgs = JSONArray()
        if (system != null) msgs.put(JSONObject().put("role", "system").put("content", system))
        msgs.put(JSONObject().put("role", "user").put("content", user))
        val r = try { http("POST", t.url + "/chat/completions", t.key, chatBody(t.pid, t.model, msgs, json, maxTokens, plain = t.pid == "custom" && !json)) }
        catch (e: ApiError) { if (e.code == 400) http("POST", t.url + "/chat/completions", t.key, chatBody(t.pid, t.model, msgs, false, maxTokens, true)) else throw e }
        val ch = r.optJSONArray("choices")?.optJSONObject(0) ?: throw AiFormatError("没有返回任何回答", r.toString().take(200))
        val content = ch.optJSONObject("message")?.optString("content").orEmpty()
        val fr = ch.optString("finish_reason").lowercase()
        if (fr.contains("content_filter") || fr.contains("sensitive")) throw ContentBlocked("finish_reason=$fr")
        if (content.isBlank()) throw AiFormatError(if (fr == "length") "回答被截断，内容为空" else "回答是空的", r.toString().take(200))
        content
    }

    /** 真实最小调用：返回 (耗时ms, 说明) */
    suspend fun test(t: Target): Pair<Long, String> {
        val t0 = System.currentTimeMillis()
        val mt = when (t.pid) { "openai" -> 16; "gemini" -> 64; else -> 8 }
        chatText(t, null, "你好，回复“好”", false, mt)
        return (System.currentTimeMillis() - t0) to "${t.model} 已回复"
    }

    /** 第 ① 步：固定用 DeepSeek 清洗 + 识别意图 + 推荐模板 */
    suspend fun clean(ctx: Context, raw: String): Pair<CleanResult, CleanTpl> {
        val db = AppDb.get(ctx); Defaults.ensure(db)
        val ct = db.cleans().active() ?: throw ConfigError("没有选中的清洗模板，请到「设置 → 清洗模板」选一个。")
        val t = try { target(ctx, "p:deepseek") } catch (e: ConfigError) { throw ConfigError("第 ① 步固定用 DeepSeek 清洗，请先到「设置 → 模型与密钥」填写 DeepSeek Key。") }
        val list = db.templates().list().joinToString("\n") { "- ${it.name}：${it.desc}" }
        val prompt = Vars.fillClean(ct.prompt, raw, list)
        val txt = chatText(t, "你是中文文字编辑。只输出一个合法 JSON 对象。", prompt, true)
        return CleanParse.parse(txt, ct.fieldList()) to ct
    }

    private val lengthHint = listOf("篇幅：短，约 500 字。", "篇幅：中，约 1200 字。", "篇幅：长，约 2500 字。")
    const val OUT_RULE = "只输出一个 JSON 对象：{\"title\":\"标题\",\"body\":\"正文，段落之间空一行，小标题单独一行并以 ## 开头\",\"tags\":[\"标签1\",\"标签2\"]}，tags 为 3–8 个不带 # 的话题词，不要输出其它内容。"

    /** 最终提示词 = 模板填值（空变量整行删）+ 篇幅 */
    fun finalPrompt(tpl: String, values: Map<String, String>, cleaned: String, attachRaw: Boolean, length: Int): String {
        val v = values.toMutableMap(); if (attachRaw) v[Vars.RAW] = cleaned
        return Vars.fill(tpl, v).trimEnd() + "\n" + lengthHint[length.coerceIn(0, 2)]
    }

    /** 第 ③ 步：生成并存入文案库，返回 note id */
    suspend fun write(ctx: Context, d: GenJob): Long {
        val t = target(ctx, d.model)
        val txt = chatText(t, "你是专业的中文写作者。$OUT_RULE", d.finalPrompt, true)
        val a = ArticleParse.parse(txt, d.cleaned.take(20))
        if (a.body.isBlank()) throw AiFormatError("缺少正文", txt.take(200))
        val db = AppDb.get(ctx)
        val n = Note(title = a.title, body = a.body, tags = a.tags.joinToString(" "), raw = d.raw, cleaned = d.cleaned,
            intent = JSONObject(d.values as Map<*, *>).toString(), templateId = d.templateId, templateName = d.templateName, model = "${t.name} · ${t.model}", finalPrompt = d.finalPrompt)
        val id = db.notes().insert(n); db.notes().update(n.copy(id = id, rootId = id))
        return id
    }

    /** 改写：基于某版本 + 指令，存成新版本 */
    suspend fun rewrite(ctx: Context, noteId: Long, instruction: String, modelRef: String): Long {
        val db = AppDb.get(ctx)
        val n = db.notes().get(noteId) ?: throw ConfigError("这篇文章已被删除")
        val ref = modelRef.ifBlank { Settings(ctx).defaultModel.ifBlank { Settings(ctx).configured().firstOrNull()?.ref ?: "p:deepseek" } }
        val t = target(ctx, ref)
        val prompt = "下面是一篇文章，请按要求修改。\n修改要求：$instruction\n保持事实和观点不变，不要添加原文没有的信息。\n\n标题：${n.title}\n\n正文：\n${n.body}\n\n标签：${n.tagList().joinToString(" ")}"
        val txt = chatText(t, "你是专业的中文编辑。$OUT_RULE", prompt, true)
        val a = ArticleParse.parse(txt, n.title)
        val v = (db.notes().maxVersion(n.root) ?: n.version) + 1
        return db.notes().insert(n.copy(id = 0, createdAt = System.currentTimeMillis(), title = a.title, body = a.body,
            tags = (a.tags.ifEmpty { n.tagList() }).joinToString(" "), rootId = n.root, version = v, instruction = instruction, model = "${t.name} · ${t.model}"))
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
        if (key.isBlank()) throw ConfigError("还没有填写配图模型 ${Providers.image(pid).name} 的 Key。请到「设置 → 模型与密钥 → 配图模型」填写。")
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
