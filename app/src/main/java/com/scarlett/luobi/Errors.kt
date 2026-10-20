package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 服务商返回非 2xx（或 2xx 但带 error）——raw 为原始响应体 */
class ApiError(val code: Int, val raw: String, val host: String = "") : Exception() {
    override val message: String get() = Errors.explain(this).text
}
/** AI 回复了，但不是 App 能读懂的格式 */
class AiFormatError(val what: String, val raw: String = "") : Exception() {
    override val message: String get() = Errors.explain(this).text
}
/** 服务商内容安全审核拦截（HTTP 200 但 finish_reason=content_filter 等） */
class ContentBlocked(val raw: String = "") : Exception() {
    override val message: String get() = Errors.explain(this).text
}
/** 设置里没填 Key / 模型 */
class ConfigError(val hint: String) : Exception(hint)
/** 异步出图任务失败（万相等），raw 为服务商给的原因 */
class TaskError(val raw: String) : Exception() {
    override val message: String get() = Errors.explain(this).text
}
/** 连不上需要海外网络的服务商（Google / OpenAI） */
class OverseasNetError(val host: String, cause: Throwable) : Exception(cause) {
    override val message: String get() = Errors.explain(this).text
}
/** 文章太长（配图 / 长图） */
class TooLongError(msg: String) : Exception(msg)

/** 给人看的错误：标题 + 详细说明（发生了什么、可能原因、怎么办）+ 原始信息 */
data class Friendly(val title: String, val detail: String, val raw: String = "", val settings: Boolean = false, val code: Int = 0) {
    val text get() = "$title：$detail"
}

object Errors {
    enum class Kind { NETWORK, DNS, TIMEOUT, SSL, BAD_REQUEST, KEY, NOT_FOUND, BALANCE, RATE, SERVER, CONTENT, TOO_LONG, MODEL, FORMAT, BAD_RESPONSE, CONFIG, UNKNOWN }

    /** 从服务商错误体里抽出 error.message / message / msg / code / type */
    fun providerMessage(raw: String): String {
        if (raw.isBlank()) return ""
        val t = raw.trim()
        return try {
            val j = if (t.startsWith("[")) JSONArray(t).optJSONObject(0) ?: JSONObject() else JSONObject(t)
            val parts = mutableListOf<String>()
            when (val e = j.opt("error")) {
                is JSONObject -> listOf("message", "msg", "code", "type").forEach { k -> e.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let(parts::add) }
                is String -> if (e.isNotBlank()) parts += e
            }
            listOf("message", "msg", "error_msg", "code", "error_code", "detail").forEach { k -> j.optString(k).takeIf { it.isNotBlank() && it != "null" && it != "0" }?.let(parts::add) }
            j.optJSONObject("base_resp")?.optString("status_msg")?.takeIf { it.isNotBlank() }?.let(parts::add)
            parts.distinct().joinToString(" · ").ifBlank { t.take(200) }
        } catch (_: Exception) {
            // 非 JSON（如网关 HTML 页面）：去掉标签取一点文字
            t.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(200)
        }
    }

    private fun has(s: String, vararg k: String) = k.any { s.contains(it) }

    /** 按原始文字的关键词判断类型（英文 / 中文都认） */
    fun byKeyword(rawIn: String): Kind? {
        val s = rawIn.lowercase()
        if (s.isBlank()) return null
        return when {
            has(s, "content_filter", "content filter", "content_policy", "content policy", "sensitive", "data_inspection", "datainspection", "inappropriate",
                "moderation", "safety", "risk control", "敏感", "安全审核", "违规", "不安全", "contentfilter", "1301") -> Kind.CONTENT
            has(s, "insufficient_quota", "insufficient quota", "insufficient balance", "insufficient_balance", "insufficient account balance", "insufficient_user_quota",
                "exceeded your current quota", "balance", "arrearage", "overdue", "billing", "payment required", "credit", "余额", "欠费", "额度不足", "充值", "accountoverdue") -> Kind.BALANCE
            has(s, "context_length", "context length", "maximum context", "context window", "too many tokens", "token limit", "tokens exceed", "range of input length",
                "input length", "input is too long", "prompt is too long", "too long", "超出最大长度", "长度超过") -> Kind.TOO_LONG
            has(s, "api key", "api_key", "apikey", "api-key", "令牌", "invalid token", "invalid_token") -> Kind.KEY
            has(s, "model_not_found", "model not found", "model_not_exist", "model not exist", "model does not exist", "does not exist", "invalid model", "unknown model",
                "no such model", "model is not supported", "unsupported model", "not support model", "model_not_support", "模型不存在", "模型不支持", "invalidendpoint", "endpoint not found",
                "not activated", "未开通", "modelnotopen", "access to model denied") -> Kind.MODEL
            has(s, "invalid api key", "invalid_api_key", "incorrect api key", "invalidapikey", "api key is invalid", "invalid token", "invalid_token", "authentication",
                "unauthorized", "unauthenticated", "permission denied", "access denied", "api_key", "apikey", "令牌", "身份验证", "鉴权", "无效的") -> Kind.KEY
            has(s, "rate limit", "rate_limit", "ratelimit", "too many requests", "throttl", "concurrency", "limit_requests", "requests limit", "qps", "tpm", "rpm",
                "请求过于频繁", "频率", "限流") -> Kind.RATE
            has(s, "overloaded", "server error", "internal error", "internal_error", "service unavailable", "bad gateway", "gateway timeout", "服务繁忙", "系统繁忙") -> Kind.SERVER
            else -> null
        }
    }

    private fun byCode(code: Int): Kind = when (code) {
        400, 422 -> Kind.BAD_REQUEST
        401, 403 -> Kind.KEY
        404 -> Kind.NOT_FOUND
        402 -> Kind.BALANCE
        408 -> Kind.TIMEOUT
        413 -> Kind.TOO_LONG
        429 -> Kind.RATE
        in 500..599 -> Kind.SERVER
        else -> Kind.UNKNOWN
    }

    private fun build(k: Kind, code: Int, raw: String, extra: String = ""): Friendly {
        val http = if (code > 0) "（HTTP $code）" else ""
        return when (k) {
            Kind.NETWORK -> Friendly("网络连不上或超时", "连不上服务商的服务器。可能是手机没联网、网络不稳定，或者开着的 VPN / 代理有问题。请检查手机网络（Wi-Fi 或流量），然后点「重试」。", raw, false, code)
            Kind.DNS -> Friendly("网络连不上或超时", "找不到服务商的服务器（域名解析失败）。最常见是手机暂时没网；如果网络正常，可能是接口地址写错了。请先检查手机网络后点「重试」；还不行就到「设置 → 高级」点「恢复默认地址」。", raw, true, code)
            Kind.TIMEOUT -> Friendly("网络连不上或超时", "等了很久服务器都没有回应（超时）。可能是网络慢，也可能是服务商那边正忙。请检查手机网络，或者稍等一会儿再点「重试」。", raw, false, code)
            Kind.SSL -> Friendly("网络连不上或超时", "和服务器建立安全连接失败。常见原因：连的是需要网页登录的公共 Wi-Fi、开了代理，或者手机时间不对。请换个网络（比如切到流量）后再点「重试」。", raw, false, code)
            Kind.BAD_REQUEST -> Friendly("请求参数有误$http", "服务商说这次请求的参数不对。最常见的原因是模型名填错了，或者这个模型在你的账号里还没开通。请到「设置」检查模型名，可以从下拉列表里重新选一个。", raw, true, code)
            Kind.KEY -> Friendly("API Key 无效、过期或没权限$http", "服务商不认这个 API Key：可能是复制时多了空格或少了字符、Key 已过期或被删除，或者这个 Key 没有使用该模型的权限。请到「设置」重新粘贴一遍 Key；还不行就去服务商后台新建一个 Key。", raw, true, code)
            Kind.NOT_FOUND -> Friendly("接口地址或模型不存在$http", "按现在填的接口地址或模型名，服务商那边找不到。请到「设置」检查模型名是否拼写正确，并在「高级」里点「恢复默认地址」。", raw, true, code)
            Kind.MODEL -> Friendly("模型不存在或没开通$http", "服务商找不到你填的这个模型，或者你的账号还没开通它。请到「设置」从下拉列表里重新选一个模型；如果是豆包等需要开通的服务商，先去它的控制台开通该模型。", raw, true, code)
            Kind.BALANCE -> Friendly("账户余额或额度不足$http", "你在服务商那里的余额或免费额度已经用完，所以这次请求被拒绝了。请登录服务商官网后台充值（或领取额度），然后回来点「重试」。", raw, true, code)
            Kind.RATE -> Friendly("请求太频繁，被限流了$http", "短时间内请求太多，服务商暂时限制了你（免费或新账号的频率上限通常比较低）。请等 1～2 分钟再点「重试」。", raw, false, code)
            Kind.SERVER -> Friendly("服务商服务器出错$http", "这不是你这边的问题，是服务商的服务器暂时故障或太忙。请稍后再点「重试」；如果一直这样，可以到「设置」换一家服务商。", raw, true, code)
            Kind.CONTENT -> Friendly("内容被服务商安全审核拦截", "服务商的内容安全审核认为这次的选题或生成的内容可能涉及敏感话题，所以拒绝输出。请换个选题、换种说法，或者修改提示词后再试。", raw, false, code)
            Kind.TOO_LONG -> Friendly("文章或提示词太长，超出模型限制", "这次要处理的文字超过了模型一次能处理的长度上限。请缩短选题描述或提示词模板，或到「设置」换一个支持更长内容的模型。", raw, true, code)
            Kind.FORMAT -> Friendly("AI 返回的格式不对", "AI 回复了，但不是 App 能读懂的格式${if (extra.isNotBlank()) "（$extra）" else ""}。常见于提示词被改过，或者模型偶尔没按要求输出。请直接点「重试」；反复出现的话，到「设置 → 提示词模板」恢复默认模板，或换一个模型。", raw, false, code)
            Kind.BAD_RESPONSE -> Friendly("服务商返回的内容看不懂", "服务器有回应，但返回的不是 AI 数据，多半是接口地址填错了（连到了别的网页）。请到「设置 → 高级」点「恢复默认地址」后再试。", raw, true, code)
            Kind.CONFIG -> Friendly("还没配置好", extra, "", true, 0)
            Kind.UNKNOWN -> Friendly("未知错误${if (code > 0) "（HTTP $code）" else ""}", "出了一个 App 没能识别的问题${if (extra.isNotBlank()) "：$extra" else ""}。请稍后点「重试」；如果一直出现，可以截图下方的原始信息，方便排查。", raw, false, code)
        }
    }

    private fun isCjk(s: String) = s.any { it.code in 0x4E00..0x9FFF }

    /** 任意异常 → 中文说明 */
    fun explain(e: Throwable): Friendly {
        // 解包 Worker / 协程包裹的原因
        val t = generateSequence(e) { it.cause }.firstOrNull { it is OverseasNetError || it is ApiError || it is AiFormatError || it is ContentBlocked || it is ConfigError || it is TaskError || it is IOException } ?: e
        return when (t) {
            is OverseasNetError -> overseasNet(t)
            is ApiError -> {
                if (t.host.endsWith("googleapis.com")) google(t)?.let { return it }
                val pm = providerMessage(t.raw)
                val k = (if (t.code == 401) Kind.KEY else if (t.code == -1) Kind.BAD_RESPONSE else null) ?: byKeyword(pm) ?: byKeyword(t.raw.take(600)) ?: byCode(t.code)
                build(k, t.code.coerceAtLeast(0), pm.ifBlank { t.raw.take(200) })
            }
            is ContentBlocked -> build(Kind.CONTENT, 0, t.raw)
            is AiFormatError -> build(Kind.FORMAT, 0, t.raw.take(200), t.what)
            is ConfigError -> build(Kind.CONFIG, 0, "", t.hint)
            is TaskError -> build(byKeyword(t.raw) ?: Kind.UNKNOWN, 0, t.raw, "出图任务失败")
            is java.net.MalformedURLException -> Friendly("接口地址格式不对", "设置里的接口地址不是一个有效的网址（应以 https:// 开头）。请到「设置 → 高级」点「恢复默认地址」。", t.message.orEmpty(), true)
            is UnknownHostException -> build(Kind.DNS, 0, t.message ?: "UnknownHost")
            is SocketTimeoutException -> build(Kind.TIMEOUT, 0, t.message ?: "timeout")
            is SSLException -> build(Kind.SSL, 0, t.message ?: "SSL")
            is ConnectException, is NoRouteToHostException, is SocketException -> build(Kind.NETWORK, 0, t.message ?: t.javaClass.simpleName)
            is org.json.JSONException -> build(Kind.FORMAT, 0, t.message ?: "", "缺少需要的字段")
            is IOException -> build(Kind.NETWORK, 0, t.message ?: t.javaClass.simpleName)
            is TooLongError -> Friendly("文章太长", t.message ?: "", "", false)
            is OutOfMemoryError -> Friendly("文章太长，图片生成不了", "手机内存不够画这么大的图。建议用配图分享，或把文章拆成两篇。")
            else -> {
                val m = t.message.orEmpty()
                when {
                    m.isNotBlank() && isCjk(m) -> Friendly("出错了", m, "", needsSettingsText(m))
                    else -> byKeyword(m)?.let { build(it, 0, m) } ?: build(Kind.UNKNOWN, 0, m.ifBlank { t.javaClass.simpleName })
                }
            }
        }
    }

    private fun overseasNet(t: OverseasNetError): Friendly {
        val who = if (t.host.contains("openai")) "OpenAI" else "Google"
        val c = t.cause
        val raw = (c?.javaClass?.simpleName ?: "") + (c?.message?.let { " · $it" } ?: "")
        val why = when (c) {
            is SocketTimeoutException -> "等了很久都没有回应（超时）"
            is UnknownHostException -> "找不到 $who 的服务器（域名解析失败）"
            is SSLException -> "安全连接被中断"
            else -> "连接被拒绝或中断"
        }
        return Friendly("连不上 $who 服务器", "$why。国内网络通常无法直连 $who，需要开启海外网络（VPN / 代理）后再点「重试」。已经开了的话，请确认代理对本 App 生效（部分代理只代理浏览器），或换一个节点；也可以到「设置」改用国内可直连的服务商。", raw, true)
    }

    /** Google（Gemini API）错误体：{"error":{"code":400,"message":"…","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}} */
    private fun google(t: ApiError): Friendly? {
        val j = try { val x = t.raw.trim(); if (x.startsWith("[")) JSONArray(x).optJSONObject(0) else JSONObject(x) } catch (_: Exception) { null }
        val err = j?.optJSONObject("error")
        val status = err?.optString("status").orEmpty()
        val msg = err?.optString("message").orEmpty()
        val reasons = buildList { err?.optJSONArray("details")?.let { d -> for (i in 0 until d.length()) d.optJSONObject(i)?.optString("reason")?.takeIf { it.isNotBlank() }?.let(::add) } }
        val raw = listOf(status, reasons.joinToString(","), msg).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { t.raw.take(200) }
        val all = (raw + " " + t.raw.take(400)).lowercase()
        val http = "（HTTP ${t.code}）"
        return when {
            "api_key_invalid" in all || "api key not valid" in all || "api key expired" in all || "api_key_expired" in all ->
                Friendly("Gemini API Key 无效或已过期$http", "Google 不认这个 Key：可能复制时多了空格、少了字符，或者这个 Key 已被删除 / 过期。请到 Google AI Studio（aistudio.google.com → Get API key）重新复制或新建一个 Key，再到「设置」里粘贴。", raw, true, t.code)
            "user location is not supported" in all || "location is not supported" in all || "unsupported_location" in all || ("failed_precondition" in all && "location" in all) ->
                Friendly("所在地区不支持使用 Gemini$http", "Google 按你的网络出口判断地区，当前地区不在 Gemini API 支持范围内（中国大陆、香港节点都不行）。请把海外网络切到美国、日本、新加坡等支持的地区节点后再试。", raw, false, t.code)
            "api_key_service_blocked" in all || "service_disabled" in all || "has not been used in project" in all || "is disabled" in all ->
                Friendly("这个 Key 没有开通 Gemini API$http", "这个 Key 所在的 Google Cloud 项目没有启用 Generative Language API，或者 Key 被限制只能调用别的服务。最简单的办法：到 Google AI Studio 直接新建一个 Key。", raw, true, t.code)
            t.code == 403 || status == "PERMISSION_DENIED" ->
                Friendly("没有权限调用 Google$http", "Google 拒绝了这次请求：可能这个 Key 没有该模型的权限、被设置了 IP / 应用限制，或者所在地区不支持。请到 Google AI Studio 检查或新建 Key；若开着代理，换个支持的地区节点试试。", raw, true, t.code)
            t.code == 429 || status == "RESOURCE_EXHAUSTED" ->
                if ("limit: 0" in all) Friendly("这个模型没有免费额度$http", "你的 Google 账号在免费层级下对这个模型的额度是 0（多见于出图模型和 Pro 模型）。请到 Google AI Studio 开通付费（绑定结算账号），或在「设置」里换一个有免费额度的模型。", raw, true, t.code)
                else Friendly("Google 额度用完或请求太频繁$http", "超过了 Google 给你的每分钟 / 每天用量上限（免费层级上限很低）。请等 1～2 分钟再点「重试」；经常出现的话，到 Google AI Studio 查看用量，或开通付费提高上限。", raw, false, t.code)
            t.code == 404 || status == "NOT_FOUND" ->
                Friendly("Google 找不到这个模型$http", "你填的模型名 Google 那边不存在，或者已经下线（比如 Imagen 已停用）。请到「设置」从下拉列表重新选一个模型，并在「高级」里点「恢复默认地址」。", raw, true, t.code)
            "safety" in all || "blocked" in all ->
                Friendly("内容被 Google 安全审核拦截$http", "Google 认为这次的内容可能不合规，所以拒绝生成。请换个选题或说法后再试。", raw, false, t.code)
            t.code == 400 || status == "INVALID_ARGUMENT" ->
                Friendly("请求参数有误$http", "Google 说这次请求的参数不对，最常见是模型名填错，或这个模型不支持当前用法（比如拿文字模型去出图）。请到「设置」从下拉列表重新选模型。", raw, true, t.code)
            t.code in 500..599 || status == "UNAVAILABLE" || status == "INTERNAL" ->
                Friendly("Google 服务器暂时出错$http", "这不是你这边的问题，是 Google 那边暂时故障或太忙。请稍后再点「重试」。", raw, false, t.code)
            else -> null
        }
    }

    fun needsSettingsText(m: String) = listOf("Key", "key", "设置", "模型", "地址").any { m.contains(it) }

    /** 宽松解析 AI 返回的 JSON：去掉 ```json 围栏、取第一个完整的 {...} */
    fun lenientJson(sIn: String): JSONObject {
        var s = sIn.trim().removePrefix("\uFEFF")
        s = s.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```").find(s)?.let { s = it.groupValues[1].trim() }
        try { return JSONObject(s) } catch (_: Exception) {}
        if (s.startsWith("[")) try { return JSONObject().put("topics", JSONArray(s)) } catch (_: Exception) {}
        // 括号配平，跳过字符串里的括号
        val a = s.indexOf('{')
        if (a >= 0) {
            var depth = 0; var inStr = false; var esc = false
            for (i in a until s.length) {
                val c = s[i]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { try { return JSONObject(s.substring(a, i + 1)) } catch (_: Exception) {}; break } } }
            }
            val b = s.lastIndexOf('}')
            if (b > a) try { return JSONObject(s.substring(a, b + 1)) } catch (_: Exception) {}
        }
        // 顶层是数组：当作选题列表
        val la = s.indexOf('['); val lb = s.lastIndexOf(']')
        if (la >= 0 && lb > la) try { return JSONObject().put("topics", JSONArray(s.substring(la, lb + 1))) } catch (_: Exception) {}
        throw AiFormatError("没有找到 JSON 内容", sIn.take(200))
    }
}
