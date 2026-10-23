package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject

/** 1.17 测试夹具：丁玲文章 + 模拟的元宝 HTTP + 模拟的校对模型 */
object FactFixture {
    val article: String = FactFixture::class.java.classLoader!!.getResource("dingling.md")!!.readText()
    val title = article.lines().first().trim()
    val body = article.substringAfter("\n").trim()

    const val GOV = "http://www.beijing.gov.cn/gate/big5/www.beijing.gov.cn/renwen/lsfm/lsmc/201901/t20190122_1869483.html"
    const val WIKI_DL = "https://zh.wikipedia.org/zh-cn/丁玲"
    const val SAGE = "https://journals.sagepub.com/doi/10.1177/1477370820977889"
    const val ZI5 = "https://zi5.net/library/chapter/179217/11.html"
    const val GENERIC = "https://example.com/minguo-fengqi"
    const val GENERIC_TEXT = "民国社会风气开放，婚恋自由成为潮流，新女性大量涌现。"

    val queries = mutableListOf<String>()
    val prompts = mutableListOf<String>()
    var extractCalls = 0; var judgeCalls = 0

    fun reset() { queries.clear(); prompts.clear(); extractCalls = 0; judgeCalls = 0 }

    private fun page(t: String, u: String, passage: String, score: Double = .8) = JSONObject().put("title", t).put("url", u).put("passage", passage).put("content", "").put("site", "站").put("score", score).put("authority_level", 2)
    private fun result(pages: List<JSONObject>) = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject().put("structuredContent",
        JSONObject().put("Response", JSONObject().put("Pages", JSONArray(pages))))).toString()

    /** 元宝 MCP：按查询内容返回固定页面；每次都夹一条只讲「民国风气」的泛泛资料 */
    val transport: (String, String, Map<String, String>, String?) -> FactNet.Resp = { _, url, _, body ->
        if (url == Yuanbao.URL) {
            val q = JSONObject(body!!).getJSONObject("params").getJSONObject("arguments").getString("Query")
            synchronized(queries) { queries += q }
            val ps = mutableListOf(page("民国风气", GENERIC, GENERIC_TEXT, .99))
            if (q.contains("北平") || q.contains("北京") && q.contains("1928")) ps += page("北京，北平，北京", GOV, "1928年6月28日，南京國民政府改北京為北平，北京的名稱被廢止。")
            if (q.contains("丁玲") || q.contains("胡也频") || q.contains("冯雪峰")) {
                ps += page("丁玲 - 维基百科", WIKI_DL, "1925年，与胡也频在北京同居……直到二人三年后结婚。2月底三人到杭州长谈，冯雪峰退出，丁玲与胡也频结婚。")
                ps += page("丁玲传 第十一章", ZI5, "1928年2月，冯雪峰南下上海……丁玲也接踵而至……在葛岭为他们租了几间房子。")
            }
            if (q.contains("Elias") || q.contains("埃利亚斯")) ps += page("Norbert Elias and the civilizing process", SAGE,
                "Elias: as chains of interdependence lengthen, people must look further ahead, to develop more habitual foresight. This is not a simple movement towards 'more' self-control. A tilting of the balance from external constraints (Fremdzwänge) in favour of self-constraints (Selbstzwänge).")
            if (q.contains("民国") && q.contains("1912")) ps += page("中华民国", "https://zh.wikipedia.org/zh-cn/中华民国", "中华民国于1912年1月1日建立。")
            FactNet.Resp(200, result(ps))
        } else FactNet.Resp(200, "{}")
    }

    val extractReply = JSONObject().put("claims", JSONArray()
        .put(JSONObject().put("span", "1920年代，她与胡也频、冯雪峰之间那段说不清道不明的关系，在当时的北平文人圈里并非绝无仅有").put("claim", "丁玲与胡也频、冯雪峰的关系发生在1920年代的北平").put("details", JSONArray()
            .put(d("人物", "丁玲与胡也频、冯雪峰在1920年代有一段三人关系", "可核实事实", listOf("丁玲", "胡也频", "冯雪峰"), listOf("民国 开放 保守", "丁玲 胡也频 冯雪峰 1920年代")))
            .put(d("地点", "这段关系发生在北平", "可核实事实", listOf("丁玲", "北平"), listOf("民国那点开放照见的是我们的保守", "丁玲 冯雪峰 1928 杭州 上海")))
            .put(d("范围", "当时北平文人圈还有类似的关系", "修辞渲染", listOf("北平文人圈"), listOf("民国 文人 恋爱")))))
        .put(JSONObject().put("span", "社会越复杂，对个体行为的可预期性要求越高，规范就越密集").put("claim", "埃利亚斯认为社会越复杂规范越密集").put("details", JSONArray()
            .put(d("引述归属", "埃利亚斯主张：社会越复杂 → 对可预期性要求越高 → 规范越密集", "引述归属", listOf("埃利亚斯", "Elias"), listOf("埃利亚斯 文明的进程 自我约束", "Elias civilizing process foresight self-constraint")))
            .put(d("作者引申", "其中的机制具有普遍性", "观点", listOf("埃利亚斯"), emptyList())))))
        .toString()
    private fun d(a: String, t: String, ty: String, e: List<String>, q: List<String>) = JSONObject().put("aspect", a).put("text", t).put("type", ty).put("entities", JSONArray(e)).put("queries", JSONArray(q))

    /** 模拟裁决：按细节内容给结论；「时间口径」故意编一句资料里没有的引文，看会不会被降级 */
    fun judge(prompt: String): String {
        val ids = Regex("\\[(c\\d+\\.d\\d+)] ([^｜]+)｜[^｜]+｜([^\\n]+)").findAll(prompt).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
        val arr = JSONArray()
        ids.forEach { (id, aspect, _) ->
            val o = JSONObject().put("id", id)
            when {
                aspect == "地名" -> o.put("verdict", "部分对").put("evidence", "E9").put("quote", "1928年6月28日，南京國民政府改北京為北平").put("reason", "1928年6月28日以前叫北京").put("rewrite", "在北京（后称北平）的新文学圈子里并非绝无仅有")
                aspect == "地点" -> o.put("verdict", "部分对").put("evidence", "E1").put("quote", "1928年2月，冯雪峰南下上海……丁玲也接踵而至").put("reason", "始于北京，高潮在杭州、上海").put("rewrite", "1920年代后期，她与胡也频、冯雪峰之间那段说不清道不明的关系，始于北京、在杭州达到顶点，在当时的新文学圈子里并非绝无仅有")
                aspect == "人物" -> o.put("verdict", "已印证").put("evidence", "E1").put("quote", "1925年，与胡也频在北京同居")
                aspect == "范围" -> o.put("verdict", "渲染无出处").put("reason", "没找到写当时北平有同类关系的资料")
                aspect == "引述归属" -> o.put("verdict", "部分对").put("evidence", "E1").put("quote", "people must look further ahead, to develop more habitual foresight").put("reason", "原意是更长远的预见与自我克制，不是「规范越密集」").put("rewrite", "社会越复杂，相互依存的链条越长，人越需要预见和自我克制")
                aspect == "时间口径" -> o.put("verdict", "已印证").put("evidence", "E1").put("quote", "民国初年就是1920年代（编造的）")
                else -> o.put("verdict", "观点不校")
            }
            arr.put(o)
        }
        return JSONObject().put("details", arr).toString()
    }

    fun env(chain: List<FactQuotes.Src> = FactQuotes.chain(listOf(FactCheck.SearchProvider.YUANBAO), { if (it == FactCheck.SearchProvider.YUANBAO) "TEST-KEY" else "" })) =
        FactPipeline.Env(FactPipeline.Llm { _, user, judge ->
            synchronized(prompts) { prompts += user }
            if (judge) { synchronized(this) { judgeCalls++ }; judge(user) } else { synchronized(this) { extractCalls++ }; extractReply }
        }, chain, { "" }, "校对模型 测试")

    fun install() { reset(); FactNet.transport = transport; FactNet.sleep = {}; FactNet.gapMs = 0; Yuanbao.resetSession() }
}
