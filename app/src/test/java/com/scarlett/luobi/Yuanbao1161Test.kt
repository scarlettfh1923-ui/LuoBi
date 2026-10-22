package com.scarlett.luobi

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scarlett.luobi.FactCheck.SearchProvider.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.URLDecoder

/** 1.16.1：真实校对路径（不是测试按钮）上的元宝搜索回归 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class Yuanbao1161Test {
    private val calls = mutableListOf<Pair<String, String>>()
    private val origT = FactNet.transport; private val origS = FactNet.sleep
    @Before fun s() { FactNet.sleep = { }; Yuanbao.resetSession() }
    @After fun r() { FactNet.transport = origT; FactNet.sleep = origS; Ai.chatTransport = null; Yuanbao.resetSession() }

    private fun ok(vararg pages: Pair<String, String>) = FactNet.Resp(200, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text")
        .put("text", JSONObject().put("Response", JSONObject().put("Pages", JSONArray(pages.map { (t, p) -> JSONObject().put("title", t).put("url", "https://baike.baidu.com/item/$t").put("passage", p).put("content", p).put("score", .9).toString() }))).toString())))).toString())
    private fun tcErr(code: String, msg: String) = FactNet.Resp(200, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text")
        .put("text", JSONObject().put("Response", JSONObject().put("Error", JSONObject().put("Code", code).put("Message", msg))).toString())))).toString())
    private fun mw(vararg t: Pair<String, String>) = FactNet.Resp(200, JSONObject().put("query", JSONObject().put("search", JSONArray().apply { t.forEach { put(JSONObject().put("title", it.first).put("snippet", it.second)) } })).toString())
    private fun extract(t: String) = FactNet.Resp(200, JSONObject().put("query", JSONObject().put("pages", JSONObject().put("1", JSONObject().put("extract", t)))).toString())

    private val body = "《百年孤独》的开头写道：“多年以后，面对行刑队，奥雷里亚诺·布恩迪亚上校将会回想起父亲带他去见识冰块的那个遥远的下午。”\n" +
        "《了不起的盖茨比》的结尾：“于是我们奋力向前划，逆流向上的小船，不停地倒退，进入过去。”"
    private val solitude = "多年以后，面对行刑队，奥雷里亚诺·布恩迪亚上校将会回想起父亲带他去见识冰块的那个遥远的下午。这是《百年孤独》的开头。"
    private val gatsby = "So we beat on, boats against the current, borne back ceaselessly into the past. — The Great Gatsby, final line"

    /** 她的版本不支持 Site / Industry：带参数返回 UnauthorizedOperation，裸 Query 正常 */
    private fun planWithoutOptionalParams(u: String, b: String): FactNet.Resp? {
        if ("wsa" !in u) return null
        val args = JSONObject(b).optJSONObject("params")?.optJSONObject("arguments") ?: return ok()
        if (args.has("Site") || args.has("Industry")) return tcErr("UnauthorizedOperation", "当前版本不支持该参数")
        val q = args.getString("Query")
        return when { "百年孤独" in q -> ok("百年孤独" to solitude); "盖茨比" in q || "Gatsby" in q -> ok("了不起的盖茨比" to "盖茨比的结尾：于是我们奋力向前划，逆流向上的小船"); else -> ok() }
    }

    @Test fun realGatherPathRetriesWithoutOptionalParamsAndNeverSaysKeyWrong() {
        FactNet.transport = { _, u, _, b -> calls += URLDecoder.decode(u, "UTF-8") to b.orEmpty(); planWithoutOptionalParams(u, b.orEmpty()) ?: mw() }
        val g = FactCheck.gather(listOf(YUANBAO, WIKI), { if (it == YUANBAO) "wsa-key" else "" }, "名著开头与结尾", body)
        assertFalse(g.note, g.note.contains("Key 不对"))
        assertTrue(Yuanbao.bareOnly)
        assertTrue(g.hits.any { it.via.contains("元宝") && it.text.contains("行刑队") })
        val found = g.quotes.count { it.found != null }
        assertEquals("引语：" + g.quotes.map { it.item.book + "/" + it.tried }, 2, found)
        assertTrue(g.note, g.note.contains("引语出处 2/2 找到"))
        assertTrue(calls.count { "wsa" in it.first } <= Yuanbao.BUDGET_PER_ARTICLE + 2)
        // 查询短：没有整句
        calls.filter { "wsa" in it.first }.forEach { val q = JSONObject(it.second).getJSONObject("params").getJSONObject("arguments").getString("Query"); assertTrue(q, q.length <= 40) }
    }

    @Test fun yuanbaoTotallyFailingFallsBackToWikiquoteAndShowsRealCode() {
        FactNet.transport = { _, u, _, b ->
            calls += URLDecoder.decode(u, "UTF-8") to b.orEmpty()
            when {
                "wsa" in u -> tcErr("InvalidParameter.Query", "query invalid")
                "zh.wikipedia" in u && "list=search" in u -> mw("百年孤独" to "加西亚·马尔克斯的小说")
                "zh.wikipedia" in u -> extract(solitude)
                "en.wikiquote" in u && "list=search" in u -> mw("The Great Gatsby" to "boats against the current")
                "en.wikiquote" in u -> extract(gatsby)
                else -> mw()
            }
        }
        val g = FactCheck.gather(listOf(YUANBAO), { if (it == YUANBAO) "wsa-key" else "" }, "名著", body)
        assertTrue(g.note, g.note.contains("InvalidParameter.Query"))
        assertFalse(g.note, g.note.contains("Key 不对"))
        val byBook = g.quotes.associate { it.item.book to it }
        assertNotNull(byBook["百年孤独"]!!.found); assertNotNull(byBook["了不起的盖茨比"]!!.found)
        assertTrue(byBook["了不起的盖茨比"]!!.found!!.url.contains("wikiquote"))
        assertTrue(byBook["了不起的盖茨比"]!!.tried.contains("元宝搜索"))
    }

    @Test fun onlyUnauthorizedMeansKeyWrongAndMessageCarriesCode() {
        assertTrue(Yuanbao.plain("UnauthorizedOperation", "x").contains("Key 不对"))
        listOf("InvalidParameter", "RequestLimitExceeded", "ResourceNotFound", "ResourceUnavailable", "InternalError", "HTTP500").forEach { c ->
            val m = Yuanbao.plain(c, "原始提示"); assertFalse(m, m.contains("Key 不对")); assertTrue(m, m.contains(c) && m.contains("原始提示"))
        }
        val e = Yuanbao.parseErr("""{"Response":{"Error":{"Code":"RequestLimitExceeded","Message":"too many"}}}""", 200)
        assertEquals("RequestLimitExceeded", e.code); assertEquals("too many", e.raw)
    }

    @Test fun requestLimitIsRetriedWithBackoff() {
        var n = 0
        FactNet.transport = { _, _, _, _ -> n++; if (n <= 2) tcErr("RequestLimitExceeded", "slow down") else ok("A" to "a") }
        assertEquals(1, Yuanbao.search("k", "q").size); assertEquals(3, n)
    }

    /** 整条 FactCheck.run（设定里读 Key → 检索 → 模型），确保读的是同一个 Key 槽位并且去掉空白 */
    @Test fun fullRunReadsTrimmedKeyAndFeedsEvidence() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = Settings(ctx); s.wsaKey = "  wsa-key  ".trim(); s.factSearch = "yuanbao,wiki"; s.setKey("deepseek", "sk-test")
        var auth = ""; var prompt = ""
        FactNet.transport = { _, u, h, b -> if ("wsa" in u) auth = h["Authorization"].orEmpty(); planWithoutOptionalParams(u, b.orEmpty()) ?: mw() }
        Ai.chatTransport = { _, b -> prompt = JSONObject(b).getJSONArray("messages").let { it.getJSONObject(it.length() - 1).getString("content") }
            200 to JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", "{\"issues\":[]}")))).toString() }
        val r = FactCheck.run(ctx, "名著", body)
        assertEquals("Bearer wsa-key", auth)
        assertTrue(prompt.contains("行刑队"))
        assertFalse(r.searchNote, r.searchNote.contains("Key 不对"))
        assertTrue(r.issues.any { it.verdict == "已印证" && it.links.isNotEmpty() })
    }
}
