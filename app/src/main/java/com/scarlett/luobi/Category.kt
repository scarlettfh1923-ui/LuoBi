package com.scarlett.luobi

/** 1.15：文章分类（单选，固定 15 个，没有「其他」）。与渠道无关：Notion / 语雀等共用。 */
object Categories {
    val LIST = listOf(
        "学习方法与知识管理", "文学", "电影", "哲学思考", "思想史与观念史", "历史", "文化与艺术", "政治",
        "社会观察", "经济与制度", "社会学与现代性", "心理与关系", "生活与自我认知", "写作与表达", "AI 科技",
    )
    /** AI 输出的分类：去掉引号/书名号/空白后必须与列表完全一致，否则返回空 */
    fun validate(raw: String?): String {
        val s = raw.orEmpty().trim().trim('「', '」', '"', '“', '”', '《', '》', '\'', '#').trim()
        return LIST.firstOrNull { it == s } ?: ""
    }
}

/** 文章信息行：模板 · 模型 · 第 N 版（1.15 从阅读页移到「更多」面板） */
object ArticleInfo {
    fun line(n: Note): String = listOf(n.templateName.ifBlank { "文章" },
        n.model.substringBefore(" · ") + n.model.substringAfter(" · ", "").let { if (it.isNotBlank()) " $it" else "" }, "第 ${n.version} 版")
        .filter { it.isNotBlank() }.joinToString(" · ")
}

/** 1.15 案头：按日期分组标题（今天 / 本周 / 10月 / 2025年10月）与分类筛选 */
object Desk {
    fun section(t: Long, now: Long = System.currentTimeMillis()): String {
        val c = java.util.Calendar.getInstance(java.util.Locale.CHINA).apply { firstDayOfWeek = java.util.Calendar.MONDAY; timeInMillis = now }
        val d = java.util.Calendar.getInstance(java.util.Locale.CHINA).apply { firstDayOfWeek = java.util.Calendar.MONDAY; timeInMillis = t }
        val sameDay = c.get(java.util.Calendar.YEAR) == d.get(java.util.Calendar.YEAR) && c.get(java.util.Calendar.DAY_OF_YEAR) == d.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay) return "今天"
        val weekStart = (c.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            val back = (get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7; add(java.util.Calendar.DAY_OF_MONTH, -back)
        }.timeInMillis
        if (t in weekStart..now) return "本周"
        val m = d.get(java.util.Calendar.MONTH) + 1
        return if (d.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)) "${m}月" else "${d.get(java.util.Calendar.YEAR)}年${m}月"
    }
    /** 筛选 + 按日期新→旧（cat 为空 = 全部） */
    fun filter(notes: List<Note>, cat: String): List<Note> = notes.filter { cat.isBlank() || it.category == cat }.sortedByDescending { it.createdAt }
    /** 菜单计数：全部 + 15 个分类（含 0） */
    fun counts(notes: List<Note>): List<Pair<String, Int>> = listOf("" to notes.size) + Categories.LIST.map { c -> c to notes.count { it.category == c } }
}
