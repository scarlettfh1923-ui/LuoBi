package com.scarlett.luobi

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** 1.19：开始校对前 3 秒体检——Key 错 / 余额不足当场在界面上说，不再后台跑半天才失败 */
object FactPreflight {
    data class Result(val fatal: String? = null, val warn: String? = null)

    /** 测试可替换：(url, key) → HTTP 状态码；连不上返回 -1 */
    var http: (String, String) -> Int = { u, key -> runCatching {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        c.setRequestProperty("Authorization", "Bearer $key")
        try { c.responseCode } finally { c.disconnect() }
    }.getOrDefault(-1) }

    fun check(ctx: Context, writerDisplay: String): Result {
        val s = Settings(ctx)
        val m = FactCheck.resolveCheckModel(s, writerDisplay)
        val t = try { Ai.target(ctx, m.ref) } catch (e: ConfigError) { return Result(fatal = e.message) }
        val base = t.url.trimEnd('/')
        val fatal = verdict(http("$base/models", t.key), t.name)
        if (fatal != null) return Result(fatal = fatal)
        val sel = FactCheck.SearchProvider.many(s.factSearch)
        val noSearch = s.wsaKey.isBlank() && s.key("kimi").isBlank() && s.key("deepseek").isBlank() && s.bochaKey.isBlank()
        val warn = when {
            FactCheck.SearchProvider.YUANBAO in sel && s.wsaKey.isBlank() -> "元宝搜索没填 Key，会改用其他检索源"
            noSearch -> "没有可用的检索 Key，只能查维基（国内可能连不上）"
            else -> null
        }
        return Result(warn = warn)
    }

    /** 只有明确的 Key/余额问题才拦下；超时、404（有的中转站没有 /models）都放行 */
    fun verdict(code: Int, name: String): String? = when (code) {
        401, 403 -> "$name 的 API Key 无效或没有权限。请到「设定 → 模型与密钥」检查后再试。"
        402 -> "$name 账户余额不足，充值后再试。"
        else -> null
    }
}
