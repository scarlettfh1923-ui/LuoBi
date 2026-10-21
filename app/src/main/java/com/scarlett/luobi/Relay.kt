package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject

/** 1.15.1 自定义中转站（OpenAI 兼容） */
object Relay {
    const val HINT = "中转站模型用于写作、意图提取和事实校对的判断。事实校对的联网搜索仍由 DeepSeek / Kimi 搜索工具完成，中转站模型只根据搜到的资料判断对错。"

    /** 允许粘贴 https://x.com、https://x.com/v1、…/v1/chat/completions；统一成「…/v1」，调用 {base}/chat/completions */
    fun base(raw: String): String {
        var u = raw.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        if (u.isBlank()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        for (suf in listOf("/chat/completions", "/completions", "/models", "/chat")) if (u.endsWith(suf)) u = u.removeSuffix(suf)
        u = u.trimEnd('/')
        if (!Regex("/v\\d+[a-z]*$").containsMatchIn(u)) u += "/v1"
        return u
    }

    /** 流式（SSE「data: …」）回复合成为普通 chat.completion JSON；reasoning_content 只记在旁边，不并入 content */
    fun fromSse(txt: String): JSONObject? {
        if (!txt.trimStart().startsWith("data:")) return null
        val content = StringBuilder(); var fr = ""; var model = ""
        txt.lineSequence().map { it.trim() }.filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
            .filter { it.isNotBlank() && it != "[DONE]" }.forEach { line ->
                val j = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                model = j.optString("model", model)
                val ch = j.optJSONArray("choices")?.optJSONObject(0) ?: return@forEach
                ch.optJSONObject("delta")?.optString("content")?.takeIf { it != "null" }?.let { content.append(it) }
                ch.optJSONObject("message")?.optString("content")?.takeIf { it != "null" }?.let { content.append(it) }
                ch.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { fr = it }
            }
        return JSONObject().put("model", model).put("choices", JSONArray().put(JSONObject().put("finish_reason", fr)
            .put("message", JSONObject().put("role", "assistant").put("content", content.toString()))))
    }

    /** 中转站报错 → 中文 */
    fun explain(e: Throwable, t: Ai.Target): String? = when (e) {
        is ApiError -> {
            val raw = e.raw.lowercase()
            val modelMissing = (e.code == 400 || e.code == 404 || e.code == 503) && raw.contains("model") &&
                (raw.contains("not found") || raw.contains("not exist") || raw.contains("does not exist") || raw.contains("no available") || raw.contains("invalid model") || raw.contains("不存在") || raw.contains("无可用"))
            when {
                modelMissing -> "中转站没有「${t.model}」这个模型。请核对模型名（可点「获取模型列表」）。"
                e.code == 401 || e.code == 403 -> "中转站 API Key 无效或没有权限（HTTP ${e.code}）。请重新粘贴 Key。"
                e.code == 404 -> "接口地址不对：${t.url}/chat/completions 不存在（HTTP 404）。请检查「接口地址」。"
                e.code == 429 -> "中转站限流或额度用完（HTTP 429），稍后再试。"
                e.code == 402 -> "中转站余额不足（HTTP 402）。"
                e.code >= 500 -> "中转站服务出错（HTTP ${e.code}），稍后再试。"
                else -> null
            }
        }
        is java.net.SocketTimeoutException -> "连接中转站超时。请检查接口地址或网络。"
        is java.net.UnknownHostException -> "找不到中转站地址「${t.url}」。请检查接口地址。"
        is java.net.ConnectException -> "连不上中转站「${t.url}」。"
        else -> null
    }
}
