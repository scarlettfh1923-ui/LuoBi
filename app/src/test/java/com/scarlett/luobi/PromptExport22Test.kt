package com.scarlett.luobi

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2.2：把 App 实际拼出来的提示词导出给 prompt-audit/eval/run.py（真正调用 DeepSeek 的评测脚本） */
class PromptExport22Test {
    @Test fun exportAssembledPrompts() {
        val dir = listOf("../../prompt-audit/eval", "../prompt-audit/eval").map { File(it) }.firstOrNull { File(it, "inputs.json").exists() } ?: return
        val inputs = JSONArray(File(dir, "inputs.json").readText())
        val out = JSONArray()
        for (i in 0 until inputs.length()) {
            val c = inputs.getJSONObject(i); val t = Prompts22.type(c.getString("type"))!!
            val vj = c.getJSONObject("v"); val v = vj.keys().asSequence().associateWith { vj.getString(it) }
            val len = c.getInt("len"); val r = Lengths.DEFAULT[len]
            for (xhs in listOf(false, true)) {
                if (xhs && i % 4 != 0) continue
                val user = Compose.prompt(t.name, t.desc, t.module, v, c.getString("raw"), len, r, xhs = xhs)
                out.put(JSONObject().put("id", c.getString("id") + if (xhs) "-xhs" else "").put("type", t.name).put("min", r.first).put("max", r.second)
                    .put("xhs", xhs).put("system", Prompts22.system()).put("user", user).put("raw", c.getString("raw")))
            }
        }
        File(dir, "prompts.json").writeText(out.toString(1))
        assertTrue(out.length() >= 16)
    }
}
