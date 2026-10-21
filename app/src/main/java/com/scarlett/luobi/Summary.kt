package com.scarlett.luobi

import android.content.Context

/** 1.15：发布时现生成 100–150 字摘要（用这篇文章的写作模型，不预先保存） */
object Summary {
    const val SYSTEM = "你是中文编辑。用 100–150 个汉字概括这篇文章的核心问题和主要观点，只输出摘要正文，不要标题、引号、列表或「本文」开头。"
    fun prompt(title: String, body: String) = "标题：$title\n\n正文：\n" + body.take(8000)
    /** 把模型输出清理成一段纯文本，最长 200 字 */
    fun clean(s: String): String = s.replace(Regex("<think>[\\s\\S]*?</think>"), "").replace(Regex("[#*>`]"), "")
        .replace(Regex("\\s+"), " ").trim().removePrefix("摘要：").removePrefix("摘要:").trim().trim('"', '“', '”').take(200)
    suspend fun make(ctx: Context, title: String, body: String, writerDisplay: String): String {
        val s = Settings(ctx)
        val cfg = s.configured(); if (cfg.isEmpty()) throw ConfigError("没有可用的写作模型")
        val disp = writerDisplay.trim()
        val m = cfg.firstOrNull { disp.isNotBlank() && ("${it.label} · ${it.sub}" == disp || (disp.startsWith(it.label) && it.sub.isNotBlank() && disp.contains(it.sub))) }
            ?: s.defaultModel.takeIf { it.isNotBlank() }?.let { r -> cfg.firstOrNull { it.ref == r } } ?: cfg.first()
        return clean(Ai.chatText(Ai.target(ctx, m.ref), SYSTEM, prompt(title, body), false))
    }
}
