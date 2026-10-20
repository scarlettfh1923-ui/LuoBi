package com.scarlett.luobi
import org.junit.Assert.*
import org.junit.Test
class SnippetTest {
    @Test fun skipsHeadings() {
        val s = Md.snippet("## 01\n\n## 为什么要写\n\n第一段正文在这里。\n\n第二段。")
        assertTrue(s, s.startsWith("第一段正文"))
        assertFalse(s.contains("为什么要写"))
    }
}
