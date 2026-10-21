package com.scarlett.luobi

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

data class CardStyle(val name: String, val bg1: Int, val bg2: Int, val title: Int, val text: Int, val accent: Int, val paper: Int, val highlight: Int)

/** 配图竖屏比例：3:4 常用 · 9:16 更高 */
enum class CardRatio(val label: String, val sub: String, val w: Int, val h: Int) {
    R34("3:4", "竖版·常用", 1080, 1440),
    R916("9:16", "更高竖版", 1080, 1920);
    val aspect get() = w.toFloat() / h
    companion object {
        fun fromIndex(i: Int) = entries.getOrElse(i.coerceIn(0, entries.lastIndex)) { R34 }
    }
}

/** 长图配色（沿用阅读模式配色） */
data class LongPalette(val bg: Int = 0xFFFFFFFF.toInt(), val ink: Int = 0xFF111827.toInt(), val sub: Int = 0xFF8A94A6.toInt(), val accent: Int = 0xFF2F6BFF.toInt(), val tagBg: Int = 0xFFE6EEFF.toInt(), val tagText: Int = 0xFF1A3FA0.toInt())

object Render {
    const val W = 1080; const val H = 1440
    /** P3：默认白底 + 电光蓝；另可选 冷白 / 雾灰 / 冰蓝 / 石墨 / 纸张（只换配色，版式不变） */
    val styles = listOf(
        CardStyle("白底", 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF111827.toInt(), 0xFF1F2937.toInt(), 0xFF2F6BFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE6EEFF.toInt()),
        CardStyle("冷白", 0xFFF5F7FA.toInt(), 0xFFF5F7FA.toInt(), 0xFF111827.toInt(), 0xFF1F2937.toInt(), 0xFF2F6BFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE6EEFF.toInt()),
        CardStyle("雾灰", 0xFFEEF0F3.toInt(), 0xFFEEF0F3.toInt(), 0xFF1F2430.toInt(), 0xFF374151.toInt(), 0xFF2F6BFF.toInt(), 0xFFF7F8FA.toInt(), 0xFFDCE3EE.toInt()),
        CardStyle("冰蓝", 0xFFEAF1FF.toInt(), 0xFFEAF1FF.toInt(), 0xFF0F1F4A.toInt(), 0xFF1E2B4D.toInt(), 0xFF2F6BFF.toInt(), 0xFFF5F8FF.toInt(), 0xFFCFDDFF.toInt()),
        CardStyle("石墨", 0xFF1B1F27.toInt(), 0xFF1B1F27.toInt(), 0xFFF3F5F8.toInt(), 0xFFD9DEE6.toInt(), 0xFF6F9BFF.toInt(), 0xFF232833.toInt(), 0x556F9BFF),
        CardStyle("纸张", 0xFFF7F3EA.toInt(), 0xFFF7F3EA.toInt(), 0xFF2A2620.toInt(), 0xFF3A352D.toInt(), 0xFF2F6BFF.toInt(), 0xFFFBF8F2.toInt(), 0xFFE6EEFF.toInt()),
    )
    private fun dark(st: CardStyle) = st.name == "石墨"
    /** 细描边 */
    private fun frame(c: Canvas, w: Int, h: Int, st: CardStyle) {
        c.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = if (dark(st)) 0x22FFFFFF else 0xFFE3E8EF.toInt() })
    }
    private fun titleLayout(t: CharSequence, p: TextPaint, w: Int): StaticLayout {
        val b = StaticLayout.Builder.obtain(t, 0, t.length, p, w).setLineSpacing(0f, 1.12f).setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (android.os.Build.VERSION.SDK_INT >= 33) b.setLineBreakConfig(android.graphics.text.LineBreakConfig.Builder().setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE).build())
        return b.build()
    }
    /** 禁则：行首不出现收尾标点；开首标点不落在行尾。WORD JOINER 粘住相邻字。 */
    private val noLineStart = charArrayOf(
        '，', '。', '！', '？', '、', '；', '：', '…', '》', '」', '』', '）', '】', '〉',
        ',', '.', '!', '?', ';', ':', ')', ']', '}', '\'', '"', '％', '%', '°', '℃'
    )
    private val noLineEnd = charArrayOf('《', '「', '『', '（', '【', '〈', '(', '[', '{', '"')
    private fun kinsoku(t: String): String {
        if (t.isEmpty()) return t
        val sb = StringBuilder(t.length + 8)
        for (ch in t) {
            if (noLineStart.contains(ch) && sb.isNotEmpty() && sb.last() != '\u2060') sb.append('\u2060')
            sb.append(ch)
            if (noLineEnd.contains(ch)) sb.append('\u2060')
        }
        return sb.toString()
    }
    private fun prepTitle(t: String) = kinsoku(t)
    private fun layoutRaw(t: CharSequence, p: TextPaint, w: Int, mult: Float): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, w).setLineSpacing(0f, mult)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(true).setUseLineSpacingFromFallbacks(true).build()
    private fun layout(t: CharSequence, p: TextPaint, w: Int, mult: Float): StaticLayout =
        layoutRaw(kinsoku(t.toString()), p, w, mult)

    fun cover(title: String, tags: List<String>, st: CardStyle, bg: Bitmap?, titleTf: Typeface = Typeface.create(Typeface.DEFAULT, 900, false), width: Int = W, height: Int = H): Bitmap {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val dark = bg != null || dark(st)
        if (bg != null) {
            val s = maxOf(width.toFloat() / bg.width, height.toFloat() / bg.height)
            val m = Matrix().apply { setScale(s, s); postTranslate((width - bg.width * s) / 2, (height - bg.height * s) / 2) }
            c.drawBitmap(bg, m, Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0x55000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP) })
        } else {
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), st.bg1, st.bg2, Shader.TileMode.CLAMP) })
            frame(c, width, height, st)
        }
        val titleColor = if (bg != null) Color.WHITE else st.title
        val m = 96
        val labelY = (150f * height / 1440f).coerceAtLeast(120f)
        val lab = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; color = if (dark) st.accent else Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, 700, false) }
        val label = tags.firstOrNull()?.let { "# $it" } ?: "# 干货分享"
        val lw = lab.measureText(label)
        c.drawRoundRect(m.toFloat(), labelY, m + lw + 56, labelY + 82f, 41f, 41f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) 0x33FFFFFF else st.accent })
        c.drawText(label, m + 28f, labelY + 55f, lab)
        val titleMaxH = (height * 820f / 1440f).toInt()
        val titleBand = (height * 860f / 1440f).toInt()
        val titleTopBase = (300f * height / 1440f)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = titleColor; typeface = titleTf; letterSpacing = 0.02f }
        var size = 168f; var l: StaticLayout
        while (true) { tp.textSize = size; l = titleLayout(prepTitle(title), tp, width - 2 * m); if ((l.height <= titleMaxH && l.lineCount <= 5) || size < 70) break; size -= 6f }
        val top = titleTopBase + (titleBand - l.height) / 2f
        val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = st.highlight; if (bg != null) alpha = 150 }
        for (i in 0 until l.lineCount) {
            val base = top + l.getLineBaseline(i)
            val w0 = l.getLineLeft(i); val w1 = l.getLineRight(i)
            if (w1 - w0 > 4) c.drawRoundRect(m + w0 - 6, base - size * 0.34f, m + w1 + 6, base + size * 0.06f, 10f, 10f, hl)
        }
        c.save(); c.translate(m.toFloat(), top); l.draw(c); c.restore()
        val bp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 38f; color = if (dark) 0xDDFFFFFF.toInt() else st.text; alpha = 200 }
        c.drawRect(m.toFloat(), height - 230f, m + 80f, height - 222f, Paint().apply { color = st.accent })
        c.drawText(tags.drop(1).take(3).joinToString("  ") { "#$it" }, m.toFloat(), height - 150f, bp)
        return b
    }

    fun pages(title: String, body: String, st: CardStyle, tf: Typeface = Typeface.DEFAULT, sizePx: Float = 46f, spacing: Float = 1.7f, boldTf: Typeface = Typeface.create(Typeface.DEFAULT, 700, false), width: Int = W, height: Int = H): List<Bitmap> {
        val m = 100; val top = 230; val bottom = height - 170; val avail = bottom - top
        val contentW = width - 2 * m
        val paras = body.replace("\r", "").split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val headPx = sizePx * 1.25f
        val bodyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx; typeface = tf; color = st.text; letterSpacing = 0.03f }
        val headP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = headPx; typeface = boldTf; color = st.text; letterSpacing = 0.04f }
        // 小标题上方 > 下方；正文段间距偏紧
        val beforeHead = (sizePx * 0.55f).toInt()
        val afterHead = (sizePx * 0.28f).toInt()
        val bodyGap = (sizePx * 0.22f).toInt()
        // 孤儿保护：小标题下至少能放下约一行正文
        val oneBodyLine = (sizePx * spacing * 1.15f).toInt().coerceAtLeast(sizePx.toInt())
        data class Block(val layout: StaticLayout, val head: Boolean, val padTop: Int, val padBottom: Int) {
            val height get() = padTop + layout.height + padBottom
        }
        fun asPageStart(b: Block) = if (b.padTop == 0) b else Block(b.layout, b.head, 0, b.padBottom)
        val pageList = mutableListOf<MutableList<Block>>()
        var cur = mutableListOf<Block>(); var used = 0
        fun flush() {
            if (cur.isEmpty()) return
            pageList.add(cur); cur = mutableListOf(); used = 0
        }
        fun placeHead(text: String) {
            val lay = layout(text, headP, contentW, spacing) // 折行同行距
            fun headBlock(withBefore: Boolean) = Block(lay, true, if (withBefore) beforeHead else 0, afterHead)
            // 小标题整块保留；若本页放不下「标题 + 约一行正文」，整组挪到下一页
            if (cur.isNotEmpty()) {
                val trial = headBlock(true)
                if (used + trial.height + oneBodyLine > avail) flush()
                else if (used + trial.height > avail) flush()
            }
            val blk = headBlock(cur.isNotEmpty())
            cur.add(blk); used += blk.height
        }
        fun placeBody(text: String) {
            var left: CharSequence = kinsoku(text)
            while (left.isNotEmpty()) {
                if (used >= avail && cur.isNotEmpty()) flush()
                val full = layoutRaw(left, bodyP, contentW, spacing)
                val room = avail - used
                // 整段（剩余）能放下：按行填满后加段间距
                if (full.height <= room) {
                    val blk = Block(full, false, 0, bodyGap)
                    cur.add(blk); used += blk.height
                    return
                }
                // 按 StaticLayout 行切：能塞几行就塞几行
                var fit = 0
                for (line in 0 until full.lineCount) {
                    val h = full.getLineBottom(line) - full.getLineTop(0)
                    if (h <= room) fit = line + 1 else break
                }
                if (fit == 0) {
                    // 本页塞不下哪怕一行
                    if (cur.isNotEmpty() && cur.last().head) {
                        // 避免小标题孤零零停在页底：连同小标题一起带走
                        val moved = cur.removeAt(cur.lastIndex)
                        used = cur.sumOf { it.height }
                        flush()
                        val orphan = asPageStart(moved)
                        cur.add(orphan); used = orphan.height
                        continue
                    }
                    if (cur.isNotEmpty()) {
                        flush()
                        continue
                    }
                    // 空白页也至少放一行，避免死循环
                    fit = 1
                }
                if (fit >= full.lineCount) {
                    val blk = Block(full, false, 0, bodyGap)
                    cur.add(blk); used += blk.height
                    return
                }
                val end = full.getLineEnd(fit - 1)
                val next = full.getLineStart(fit)
                val part = full.text.subSequence(0, end)
                val partLay = layoutRaw(part, bodyP, contentW, spacing)
                val blk = Block(partLay, false, 0, 0) // 跨页切开：本页不加段间距
                cur.add(blk); used += blk.height
                left = full.text.subSequence(next, full.text.length)
                flush()
            }
        }
        for (p in paras) {
            val ml = Md.line(p, paras.size)
            when (ml.kind) { MdKind.H2, MdKind.H3 -> placeHead(Md.strip(ml.text)); MdKind.HR -> {}; else -> placeBody(Md.plainLine(ml)) }
        }
        if (cur.isNotEmpty()) pageList.add(cur)
        // 收尾：任意页末仍是小标题且后面还有页，把小标题挪到下一页开头（去掉上方段距）
        var pi = 0
        while (pi < pageList.size - 1) {
            val page = pageList[pi]
            if (page.isNotEmpty() && page.last().head) {
                val orphan = asPageStart(page.removeAt(page.lastIndex))
                if (page.isEmpty()) pageList.removeAt(pi) else pi++
                pageList[pi].add(0, orphan)
            } else pi++
        }
        if (pageList.isEmpty()) pageList.add(mutableListOf())
        // 超长保护：先算页数再画图，避免一次性申请几十张大图导致闪退
        if (pageList.size > MAX_PAGES) throw TooLongError("按现在的字号和行距，这篇文章需要 ${pageList.size} 张配图，超过了上限 $MAX_PAGES 张（小红书单篇最多 18 张）。建议调小字号或行距，或者把文章拆成两篇。")
        val n = pageList.size.coerceAtLeast(1)
        val heads = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; color = st.accent; typeface = boldTf }
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; color = st.text; alpha = 140 }
        return pageList.mapIndexed { idx, blks ->
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            c.drawColor(st.bg1); frame(c, width, height, st)
            c.drawRect(0f, 0f, width.toFloat(), 14f, Paint().apply { color = st.accent })
            val ht = TextUtilsEllipsize(title, heads, width - 2f * m - 120)
            c.drawText(ht, m.toFloat(), 140f, heads)
            c.drawRect(m.toFloat(), 172f, width - m.toFloat(), 174f, Paint().apply { color = st.text; alpha = 40 })
            var y = top.toFloat()
            for (blk in blks) {
                c.save(); c.translate(m.toFloat(), y + blk.padTop); blk.layout.draw(c); c.restore()
                y += blk.height
            }
            val pn = "${idx + 1} / $n"
            c.drawText(pn, width - m - foot.measureText(pn), height - 90f, foot)
            c.drawText("左滑继续阅读 →".takeIf { idx < n - 1 } ?: "— 完 —", m.toFloat(), height - 90f, foot)
            b
        }
    }

    const val MAX_PAGES = 40
    /** 单张长图最大高度；超过就按行拆成多张 */
    const val LONG_MAX_H = 16000
    /** 长图按 1.333 倍（1440 宽）出图更清晰；单张高度相应收紧，内存与原先相当 */
    const val HD = 1.3333334f
    const val LONG_MAX_H_HD = 9000

    /**
     * 杂志风奶油纸长图：标题 + 标签 + 正文（小标题同字体更大加粗），无页脚品牌。
     * 1.10：始终一张完整长图（不再拆张），交给 [sink] 后由调用方回收。
     * 返回总张数。
     */
    fun longImage(title: String, body: String, tags: List<String>, tf: Typeface = Typeface.DEFAULT, boldTf: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
                  pal: LongPalette = LongPalette(), pics: Map<Int, List<Pair<Bitmap, String>>> = emptyMap(), sink: (Bitmap, Int, Int) -> Unit): Int {
        val w = W
        val m = 88
        val cream = pal.bg
        val ink = pal.ink
        val sub = pal.sub
        val accent = pal.accent
        val tagBg = pal.tagBg
        val sizePx = 42f
        val lineMult = 1.5f // 阅读排版 A：行距约 0.5em
        val beforeHead = sizePx * 0.9f
        val afterHead = sizePx * 0.35f
        val bodyGap = sizePx * 0.85f // 段距明显大于行距
        val listGap = sizePx * 0.35f
        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 64f; typeface = boldTf; color = ink; letterSpacing = 0.02f }
        val bodyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx; typeface = tf; color = ink; letterSpacing = 0.03f }
        val headP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx * 1.3f; typeface = boldTf; color = ink; letterSpacing = 0.02f }
        val h3P = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx * 1.12f; typeface = boldTf; color = ink; letterSpacing = 0.02f }
        val quoteP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx * 0.95f; typeface = tf; color = 0xFF5B6576.toInt(); letterSpacing = 0.03f }
        fun rich(text: String): CharSequence {
            val sb = android.text.SpannableStringBuilder()
            Md.spans(text).forEach { (x, b) -> val st = sb.length; sb.append(kinsoku(x)); if (b) sb.setSpan(android.text.style.TypefaceSpan(boldTf), st, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            return sb
        }
        val metaP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = sub }
        val tagP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = pal.tagText }
        val eyebrow = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = accent; typeface = boldTf; letterSpacing = 0.12f }
        val titleL = layout(title, titleP, w - 2 * m, 1.25f)
        val paras = body.replace("\r", "").split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val capP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = sub }
        val iw = w - 2 * m
        val blocks = mutableListOf<LBlock>()
        paras.forEachIndexed { i, p ->
            val ml = Md.line(p, paras.size)
            when (ml.kind) {
                MdKind.H2, MdKind.H3 -> {
                    val no = Md.sectionNo(ml.text)
                    val lay = if (no != null) layoutRaw(rich("%02d".format(no)), TextPaint(h3P).apply { color = accent; textSize = h3P.textSize * 0.8f; letterSpacing = 0.12f }, iw, 1.3f) else layoutRaw(rich(ml.text), if (ml.kind == MdKind.H2) headP else h3P, iw, 1.3f)
                    val padTop = if (i > 0) beforeHead else 0f
                    blocks += LBlock(lay, padTop, padTop + lay.height + afterHead)
                }
                MdKind.QUOTE -> {
                    val padX = 36f; val padY = 26f
                    val lay = layoutRaw(rich(ml.text), quoteP, iw - padX.toInt() - 24, lineMult)
                    blocks += LBlock(lay, padY + 8f, 8f + padY + lay.height + padY + 8f + bodyGap * 0.6f, indent = padX, deco = 1, decoH = padY * 2 + lay.height)
                }
                MdKind.HR -> blocks += LBlock(null, 0f, sizePx * 1.6f, deco = 2)
                MdKind.BULLET, MdKind.NUM -> {
                    val mark = if (ml.kind == MdKind.BULLET) "•" else ml.num + "."
                    val ind = if (ml.kind == MdKind.BULLET) sizePx * 1.1f else sizePx * 1.5f
                    val lay = layoutRaw(rich(ml.text), bodyP, iw - ind.toInt(), lineMult)
                    val next = paras.getOrNull(i + 1)?.let { Md.line(it, paras.size).kind }
                    val gap = if (next == MdKind.BULLET || next == MdKind.NUM) listGap else bodyGap
                    blocks += LBlock(lay, 0f, lay.height + gap, indent = ind, mark = mark)
                }
                MdKind.P -> {
                    val lay = layoutRaw(rich(ml.text), bodyP, iw, lineMult)
                    blocks += LBlock(lay, 0f, lay.height + bodyGap)
                }
            }
            pics[i]?.forEach { (bmp, cap) ->
                val ih = bmp.height * iw.toFloat() / bmp.width
                val capL = if (cap.isNotBlank()) layout(cap, capP, iw, 1.2f) else null
                blocks += LBlock(null, 10f, 10f + ih + (capL?.let { 14f + it.height } ?: 0f) + bodyGap + 16f, bmp, ih, capL)
            }
        }
        var tagH = 0f; var tx = 0f; val tagGap = 16f; val tagRowH = 56f
        val tagWs = tags.take(8).map { tagP.measureText("#$it") + 36f }
        for (tw in tagWs) {
            if (tx > 0 && tx + tw > w - 2 * m) { tagH += tagRowH + 12; tx = 0f }
            tx += tw + tagGap
        }
        if (tagWs.isNotEmpty()) tagH += tagRowH
        val bodyH = blocks.sumOf { it.height.toDouble() }.toFloat()
        val bodyTop = 100f + 48f + titleL.height + 28f + 40f + 36f + tagH + 40f + 14f + 48f
        val h = (bodyTop + bodyH + 80f).toInt().coerceAtLeast(1400)
        // 可切分的位置：每块开头、每一行的底边（绝不把一行字切成两半）
        val cuts = mutableListOf(bodyTop.toInt())
        run {
            var by = bodyTop
            for (blk in blocks) {
                cuts += by.toInt()
                blk.layout?.let { l -> for (li in 0 until l.lineCount) cuts += (by + blk.padTop + l.getLineBottom(li)).toInt() }
                by += blk.height
            }
        }
        // 1.10：永远只出一张完整长图（以前超过 9000 高就按行拆成多张，这就是「被分成 2 段」的原因）。
        // 清晰度：优先 1440 宽（HD），太高时退到 1080 宽；内存吃紧时用 RGB_565（长图不透明，不需要 alpha）。
        var sc = longScale(h)
        fun alloc(scale: Float): Bitmap {
            val bw = (w * scale).toInt(); val bh = (h * scale).toInt()
            val cfg = if (bw.toLong() * bh * 4 <= LONG_ARGB_BUDGET) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            return Bitmap.createBitmap(bw, bh, cfg)
        }
        // 内存不够时退到 1080 宽 RGB_565 再试一次（仍是一张完整图）
        val b = try { alloc(sc) } catch (e: OutOfMemoryError) { if (sc <= 1f) throw e; sc = minOf(1f, sc); System.gc(); alloc(sc) }
        val c = Canvas(b)
        c.drawColor(cream)
        c.scale(sc, sc)
        drawLong(c, m, titleL, paras, tags, tagP, tagRowH, tagGap, tagBg, accent, eyebrow, metaP, blocks, ink)
        sink(b, 0, 1)
        return 1
    }

    /** JPEG 单边上限 65535；留余量 */
    const val LONG_MAX_PX = 60000
    /** ARGB_8888 的内存预算（原生内存），超出改用 RGB_565 */
    const val LONG_ARGB_BUDGET = 192L * 1024 * 1024
    /** 长图缩放：1440 宽放得下就用 1440，否则 1080（1.0），极端长文再按 JPEG 上限收 */
    fun longScale(h: Int): Float = when {
        h * HD <= LONG_MAX_PX -> HD
        h <= LONG_MAX_PX -> 1f
        else -> LONG_MAX_PX.toFloat() / h
    }

    private fun drawLong(c: Canvas, m: Int, titleL: StaticLayout, paras: List<String>, tags: List<String>, tagP: TextPaint, tagRowH: Float, tagGap: Float, tagBg: Int, accent: Int,
                         eyebrow: TextPaint, metaP: TextPaint, blocks: List<LBlock>, ink: Int = 0xFF111827.toInt()) {
        val w = W
        var y = 100f
        c.drawText("落笔", m.toFloat(), y, eyebrow); y += 48f
        c.save(); c.translate(m.toFloat(), y); titleL.draw(c); c.restore(); y += titleL.height + 28f
        val words = paras.joinToString("").length
        c.drawText("约 ${(words / 300).coerceAtLeast(1)} 分钟读完", m.toFloat(), y + 28f, metaP); y += 40 + 36f
        var cx = m.toFloat(); var cy = y
        tags.take(8).forEach { tag ->
            val label = "#$tag"
            val tw = tagP.measureText(label) + 36f
            if (cx > m && cx + tw > w - m) { cx = m.toFloat(); cy += tagRowH + 12 }
            c.drawRoundRect(cx, cy, cx + tw, cy + tagRowH, 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tagBg })
            c.drawText(label, cx + 18f, cy + 38f, tagP)
            cx += tw + tagGap
        }
        y = cy + (if (tags.isNotEmpty()) tagRowH else 0f) + 40f
        c.drawRoundRect(m.toFloat(), y, m + 56f, y + 6f, 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        y += 14f + 48f
        val iw = w - 2 * m
        for (b in blocks) {
            if (b.deco == 1) {
                val top = y + 8f
                c.drawRoundRect(m.toFloat(), top, (w - m).toFloat(), top + b.decoH, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F4F8.toInt() })
                c.drawRect(m.toFloat(), top, m + 6f, top + b.decoH, Paint().apply { color = 0xFFCBD5E1.toInt() })
            }
            if (b.deco == 2) c.drawRect(m.toFloat(), y + b.height / 2 - 1f, (w - m).toFloat(), y + b.height / 2 + 1f, Paint().apply { color = 0xFFE3E8EF.toInt() })
            if (b.mark != null && b.layout != null) {
                val mp = TextPaint(b.layout.paint).apply { color = ink }
                c.drawText(b.mark, m.toFloat() + (if (b.mark == "•") 6f else 0f), y + b.padTop + b.layout.getLineBaseline(0), mp)
            }
            if (b.layout != null) { c.save(); c.translate(m.toFloat() + b.indent, y + b.padTop); b.layout.draw(c); c.restore() }
            if (b.bmp != null) {
                val top = y + b.padTop
                c.save(); val rr = android.graphics.RectF(m.toFloat(), top, (w - m).toFloat(), top + b.bmpH)
                c.clipPath(android.graphics.Path().apply { addRoundRect(rr, 16f, 16f, android.graphics.Path.Direction.CW) })
                c.drawBitmap(b.bmp, null, rr, Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
                b.cap?.let { c.save(); c.translate(m.toFloat(), top + b.bmpH + 14f); it.draw(c); c.restore() }
            }
            y += b.height
        }
    }

    private fun TextUtilsEllipsize(s: String, p: TextPaint, w: Float) = android.text.TextUtils.ellipsize(s, p, w, android.text.TextUtils.TruncateAt.END).toString()
}

/** 长图里的一块：一段文字，或一张插图（含图注） */
class LBlock(val layout: StaticLayout?, val padTop: Float, val height: Float, val bmp: Bitmap? = null, val bmpH: Float = 0f, val cap: StaticLayout? = null,
             val indent: Float = 0f, val deco: Int = 0, val decoH: Float = 0f, val mark: String? = null)
