package com.scarlett.copytool

/** overseas：需要海外网络才能连；note：选择列表里的一句话说明 */
data class Provider(val id: String, val name: String, val url: String, val models: List<Pair<String, String>>, val hint: String = "",
                    val note: String = "", val overseas: Boolean = false, val isNew: Boolean = false)

object Providers {
    val text = listOf(
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com", listOf("deepseek-flash" to "V4.1 Flash · 快、便宜，写文案推荐", "deepseek-v4-pro" to "V4 Pro · 更强，更贵"), note = "写文案推荐，便宜"),
        Provider("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen3.7-plus" to "均衡 · 推荐", "qwen3.8-flash" to "轻量低成本", "qwen3.8-max" to "旗舰最强", "qwen-plus" to "旧版通用"), "阿里云百炼 Key。若你的账号只能用业务空间专属地址，请在「高级」里改成 https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", note = "阿里云百炼 Key"),
        Provider("doubao", "豆包", "https://ark.cn-beijing.volces.com/api/v3", listOf("doubao-seed-1-6-251015" to "示例模型 ID，需先在方舟控制台开通"), "豆包需要先在火山方舟控制台开通模型。请在「自定义模型」里填控制台里的模型 ID 或 ep- 开头的推理接入点 ID", note = "需先在火山方舟开通模型"),
        Provider("kimi", "Kimi", "https://api.moonshot.cn/v1", listOf("kimi-k3" to "最新旗舰 · 推荐", "kimi-k2.6" to "通用 · 支持关闭思考"), note = "长文稳定"),
        Provider("glm", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", listOf("glm-5.3" to "最新旗舰", "glm-4.6" to "上一代 · 稳定"), note = "智谱开放平台 Key"),
        Provider("silicon", "硅基流动", "https://api.siliconflow.cn/v1", listOf("deepseek-ai/DeepSeek-V4-Flash" to "DeepSeek V4 Flash", "deepseek-ai/DeepSeek-V4-Pro" to "DeepSeek V4 Pro"), note = "一个 Key 用多家模型"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-5.4-mini" to "快、便宜", "gpt-5.5" to "旗舰"), "国内网络通常无法直连 OpenAI，需要开启海外网络", note = "GPT 系列", overseas = true),
        // Gemini 官方 OpenAI 兼容接口：https://ai.google.dev/gemini-api/docs/openai ；模型 ID 见 https://ai.google.dev/gemini-api/docs/models
        Provider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            listOf("gemini-3.8-flash" to "3.8 Flash · 最新，推荐", "gemini-3.6-flash" to "3.6 Flash · 上一代，稳定", "gemini-3.5-flash-lite" to "3.5 Flash-Lite · 最便宜"),
            "用 Google AI Studio（aistudio.google.com）申请的 Key。国内网络通常无法直连 Google，需要开启海外网络；香港节点也不支持", note = "Google AI Studio Key", overseas = true, isNew = true),
    )
    val image = listOf(
        Provider("silicon", "硅基流动", "https://api.siliconflow.cn/v1", listOf("Kwai-Kolors/Kolors" to "可图 · 便宜稳定", "Qwen/Qwen-Image" to "通义 Qwen-Image"), note = "可图 / Qwen-Image"),
        Provider("wanx", "通义万相", "https://dashscope.aliyuncs.com", listOf("wan2.2-t2i-flash" to "万相 2.2 Flash · 快", "wanx2.1-t2i-turbo" to "万相 2.1 Turbo"), "使用阿里云百炼 Key，异步出图，约 10-30 秒", note = "异步出图 10–30 秒"),
        Provider("seedream", "豆包 Seedream", "https://ark.cn-beijing.volces.com/api/v3", listOf("doubao-seedream-5-0-260128" to "Seedream 5.0 lite", "doubao-seedream-4-5-251128" to "Seedream 4.5", "doubao-seedream-4-0-250828" to "Seedream 4.0"), "需先在火山方舟控制台开通对应模型；也可在自定义模型里填 ep- 接入点 ID", note = "需先开通模型"),
        Provider("cogview", "智谱 CogView", "https://open.bigmodel.cn/api/paas/v4", listOf("cogview-3-flash" to "免费", "cogview-4-250304" to "CogView-4", "glm-image" to "GLM-Image · 高质量"), note = "有免费模型"),
        // Gemini 原生出图（Nano Banana），generateContent：https://ai.google.dev/gemini-api/docs/image-generation ；Imagen 已关停
        Provider("google", "Google", "https://generativelanguage.googleapis.com/v1beta",
            listOf("gemini-nano-banana-2.1" to "Nano Banana 2.1 · 推荐", "gemini-3.1-flash-lite-image" to "Nano Banana 2 Lite · 最快最便宜", "gemini-3-pro-image" to "Nano Banana Pro · 质量最高，更贵"),
            "用 Google AI Studio 申请的 Key（和 Gemini 文案可以是同一个）。国内网络通常无法直连 Google，需要开启海外网络", note = "Nano Banana 出图", overseas = true, isNew = true),
    )
    fun text(id: String) = text.firstOrNull { it.id == id } ?: text[0]
    fun image(id: String) = image.firstOrNull { it.id == id } ?: image[0]
}
