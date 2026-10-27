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
data class LongPalette(val bg: Int = 0xFFFFFFFF.toInt(), val ink: Int = 0xFF1E1B17.toInt(), val sub: Int = 0xFF776C5F.toInt(), val accent: Int = 0xFF2E5E6B.toInt(), val tagBg: Int = 0xFFE1EAEA.toInt(), val tagText: Int = 0xFF1E4651.toInt())

object Render {
    const val W = 1080; const val H = 1440
    /** 2.2 宣纸·黛青：默认宣纸底 + 黛青；另可选 素白 / 雾灰 / 青瓷 / 石墨 / 纸张（只换配色，版式不变） */
    val styles = listOf(
        CardStyle("宣纸", 0xFFF7F3EC.toInt(), 0xFFF7F3EC.toInt(), 0xFF1E1B17.toInt(), 0xFF2E2923.toInt(), 0xFF2E5E6B.toInt(), 0xFFFFFCF6.toInt(), 0xFFE1EAEA.toInt()),
        CardStyle("素白", 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF1E1B17.toInt(), 0xFF2E2923.toInt(), 0xFF2E5E6B.toInt(), 0xFFFAF7F1.toInt(), 0xFFE1EAEA.toInt()),
        CardStyle("雾灰", 0xFFEDEBE6.toInt(), 0xFFEDEBE6.toInt(), 0xFF22201C.toInt(), 0xFF3A362F.toInt(), 0xFF2E5E6B.toInt(), 0xFFF6F4F0.toInt(), 0xFFDCE5E4.toInt()),
        CardStyle("青瓷", 0xFFE6EEEC.toInt(), 0xFFE6EEEC.toInt(), 0xFF14282C.toInt(), 0xFF22363A.toInt(), 0xFF2E5E6B.toInt(), 0xFFF2F6F5.toInt(), 0xFFCFDFDD.toInt()),
        CardStyle("石墨", 0xFF1D1B18.toInt(), 0xFF1D1B18.toInt(), 0xFFF2EEE6.toInt(), 0xFFDAD3C7.toInt(), 0xFF8DBDC7.toInt(), 0xFF27241F.toInt(), 0x558DBDC7),
        CardStyle("纸张", 0xFFF7F3EA.toInt(), 0xFFF7F3EA.toInt(), 0xFF2A2620.toInt(), 0xFF3A352D.toInt(), 0xFF2B5864.toInt(), 0xFFFBF8F2.toInt(), 0xFFDDE4DF.toInt()),
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
    const val HEAD_MULT = 1.25f
    /** 1.16.1 配图行距：滑杆 1.4–2.0，默认 1.7（中文正文舒适区间） */
    const val LINE_MIN = 1.4f; const val LINE_MAX = 2.0f; const val LINE_DEFAULT = 1.7f
    /**
     * 1.16.1 配图节奏（全部按「字迹框」之间的可见空白计算，不依赖 includePad / 行距尾巴）：
     * 行内空白 = L − 字高；段距 = 行内空白 + 0.7L；小标题上方 = 2 × 段距；小标题下方 = 0.45L（明显小于段距）。
     * L = 字号 × 行距，随滑杆一起缩放。
     */
    data class Rhythm(val line: Float, val inner: Float, val para: Float, val aboveHead: Float, val belowHead: Float, val mult: Float)
    /**
     * L = 字号 × 行距（真实行距，按实测行高换算 StaticLayout 倍数，CJK 回退字体的行高比字号大很多）。
     * 可见空白（像素）：行内 V = L − 字号；段距 = V + 0.7L；小标题下 = 0.45L；小标题上 = 2 × 段距。
     * 排版用字体框（ascent/descent）定位，框比字迹上下多出约 0.17 字号，换算时扣掉。
     */
    fun rhythm(sizePx: Float, spacing: Float, p: TextPaint): Rhythm {
        val probe = StaticLayout.Builder.obtain("中中", 0, 2, p, (p.measureText("中") * 1.3f).toInt().coerceAtLeast(1)).setLineSpacing(0f, 1f)
            .setIncludePad(false).setUseLineSpacingFromFallbacks(true).build()
        val pitch1 = if (probe.lineCount > 1) (probe.getLineBaseline(1) - probe.getLineBaseline(0)).toFloat() else (p.descent() - p.ascent())
        val L = sizePx * spacing
        val mult = L / pitch1
        val boxExtra = (p.descent() - p.ascent()) - sizePx
        val v = L - sizePx
        if (tightRhythm) {
            // 1.19：段距 ≈ 0.6 行、小标题上 ≈ 1 行、下 ≈ 0.4 行（可见空白）
            val para = maxOf(0.6f * L, v + 4f)
            return Rhythm(L, v, para - boxExtra, maxOf(1.0f * L, para + 8f) - boxExtra, maxOf(0.4f * L, v) - boxExtra, mult)
        }
        val paraVis = v + 0.7f * L
        return Rhythm(L, v, paraVis - boxExtra, 2f * paraVis - boxExtra, 0.45f * L - boxExtra, mult)
    }
    fun headGaps(sizePx: Float, mult: Float = LINE_DEFAULT): Pair<Int, Int> {
        val r = rhythm(sizePx, mult, TextPaint().apply { textSize = sizePx }); return r.aboveHead.toInt() to r.belowHead.toInt()
    }
    /** 测试用：可见空白（上 = 前段字底到标题字顶，下 = 标题字底到下段字顶） */
    fun visualHeadGaps(sizePx: Float, spacing: Float, tf: Typeface = Typeface.DEFAULT): Pair<Float, Float> = headGaps(sizePx, spacing).let { it.first.toFloat() to it.second.toFloat() }
    /** 旧设定（1.0–1.3 倍，1.0 是最紧）迁移到新区间：1.0 → 默认 1.7；其余线性映射 */
    fun migrateSpacing(old: Float): Float = if (old <= 1.0f) LINE_DEFAULT else (LINE_MIN + (old - 1f) * 2f).coerceIn(LINE_MIN, LINE_MAX)
    private fun layoutRaw(t: CharSequence, p: TextPaint, w: Int, mult: Float): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, w).setLineSpacing(0f, mult)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false).setUseLineSpacingFromFallbacks(true).build()
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

    /**
     * 1.16.1 方案 A：文中配图按原位置排进正文页（内容宽、圆角、可带图注）；放不下就整张移到下一页页首（不打乱文章顺序）。
     * 封面 + 正文页最多 18 张：超出时从后往前丢掉文中配图，丢了几张记在 [lastDroppedPics]。
     */
    fun pages(title: String, body: String, st: CardStyle, tf: Typeface = Typeface.DEFAULT, sizePx: Float = 46f, spacing: Float = 1.7f, boldTf: Typeface = Typeface.create(Typeface.DEFAULT, 700, false), width: Int = W, height: Int = H,
              pics: Map<Int, List<Pair<Bitmap, String>>> = emptyMap(), maxTotal: Int = 18, kind: BodyKind = BodyKind.PLAIN): List<Bitmap> {
        val all = pics.toSortedMap().flatMap { (k, l) -> l.map { k to it } }
        var keep = all.size
        while (true) {
            val use = all.take(keep).groupBy({ it.first }, { it.second })
            val n = runCatching { pagesInner(title, body, st, tf, sizePx, spacing, boldTf, width, height, use, countOnly = true, kind = kind).size }.getOrElse { if (keep == 0) throw it else Int.MAX_VALUE }
            if (n + 1 <= maxTotal || keep == 0) { lastDroppedPics = all.size - keep; return pagesInner(title, body, st, tf, sizePx, spacing, boldTf, width, height, use, countOnly = false, kind = kind) }
            keep--
        }
    }
    @Volatile var lastDroppedPics = 0
    private val DUMMY: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8) }
    /** 圆角、居中裁切、高质量缩放（原图分辨率 → 内容宽） */
    private fun drawPic(c: Canvas, b: Bitmap, x: Float, y: Float, w: Int, h: Int, cap: StaticLayout?) {
        val scale = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
        val sw = w / scale; val sh = h / scale
        val src = android.graphics.Rect(((b.width - sw) / 2).toInt(), ((b.height - sh) / 2).toInt(), ((b.width + sw) / 2).toInt(), ((b.height + sh) / 2).toInt())
        val dst = android.graphics.RectF(x, y, x + w, y + h)
        c.save()
        c.clipPath(android.graphics.Path().apply { addRoundRect(dst, 28f, 28f, android.graphics.Path.Direction.CW) })
        c.drawBitmap(b, src, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { isDither = true })
        c.restore()
        cap?.let { c.save(); c.translate(x, y + h + 14f); it.draw(c); c.restore() }
    }

    private fun pagesInner(title: String, body: String, st: CardStyle, tf: Typeface, sizePx: Float, spacing: Float, boldTf: Typeface, width: Int, height: Int,
                           pics: Map<Int, List<Pair<Bitmap, String>>>, countOnly: Boolean, kind: BodyKind = BodyKind.PLAIN): List<Bitmap> {
        // 1.19：不再画标题条；上下字迹留白对称（3:4 ≈ 140，9:16 ≈ 160），页脚居中放在下留白里
        val m = marginH; val vm = vMargin(height); val top = vm; val bottom = height - vm; val avail = bottom - top
        val tf = if (kind == BodyKind.BOOK) Typeface.create(Typeface.SERIF, 400, false) else tf
        val boldTf = if (kind == BodyKind.BOOK) Typeface.create(Typeface.SERIF, 700, false) else boldTf
        val contentW = width - 2 * m
        val paras = body.replace("\r", "").split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val headPx = sizePx * 1.25f
        val bodyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx; typeface = tf; color = kind.ink; letterSpacing = 0.03f }
        val headP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = headPx; typeface = boldTf; color = kind.head; letterSpacing = 0.04f }
        val rh = rhythm(sizePx, spacing.coerceIn(1.0f, LINE_MAX), bodyP)
        val bodyMult = rh.mult
        /** 一块 = 一段 / 一个小标题；gap = 与上一块字迹之间的可见空白；页首 gap = 0 */
        class Block(val layout: StaticLayout, val head: Boolean, val gap: Int, val img: Bitmap? = null, val imgH: Int = 0, val cap: StaticLayout? = null) {
            val inkTop get() = if (img != null) 0 else (layout.getLineBaseline(0) + layout.paint.ascent()).toInt()
            val inkBottom get() = if (img != null) imgH + (cap?.let { 14 + it.height } ?: 0) else (layout.getLineBaseline(layout.lineCount - 1) + layout.paint.descent()).toInt()
            val height get() = gap + inkBottom - inkTop
            fun atTop() = Block(layout, head, 0, img, imgH, cap)
            fun withGap(g: Int) = Block(layout, head, g, img, imgH, cap)
        }
        val capP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx * 0.56f; typeface = tf; color = kind.ink; alpha = 130 }
        val emptyLay = layoutRaw(" ", bodyP, contentW, 1f)
        val pageList = mutableListOf<MutableList<Block>>()
        var cur = mutableListOf<Block>(); var used = 0
        fun flush() { if (cur.isEmpty()) return; pageList.add(cur); cur = mutableListOf(); used = 0 }
        fun gapFor(head: Boolean): Int = when {
            cur.isEmpty() -> 0
            head -> rh.aboveHead.toInt()
            cur.last().head -> rh.belowHead.toInt()
            else -> rh.para.toInt()
        }
        fun placeImage(b: Bitmap, caption: String) {
            // 内容宽；过高的图裁到不超过可用高度的 62%（居中裁切）
            val h = (contentW.toFloat() * b.height / b.width).toInt().coerceAtMost((avail * 0.62f).toInt()).coerceAtLeast(1)
            val cap = caption.takeIf { it.isNotBlank() }?.let { layoutRaw(it, capP, contentW, 1.3f) }
            var blk = Block(emptyLay, false, gapFor(false), b, h, cap)
            if (cur.isNotEmpty() && used + blk.height > avail) { flush(); blk = blk.atTop() }  // 放不下：整张移到下一页页首
            cur.add(blk); used += blk.height
        }
        val oneBodyLine = rh.line.toInt()
        fun placeHead(text: String) {
            val lay = layout(text, headP, contentW, HEAD_MULT)
            if (cur.isNotEmpty() && used + Block(lay, true, gapFor(true)).height + rh.belowHead.toInt() + 2 * oneBodyLine > avail) flush()  // 1.19：小标题后至少能放 2 行正文才留在本页
            val blk = Block(lay, true, gapFor(true)); cur.add(blk); used += blk.height
        }
        fun placeBody(text: String) {
            var left: CharSequence = kinsoku(text)
            while (left.isNotEmpty()) {
                val full = layoutRaw(left, bodyP, contentW, bodyMult)
                val g = gapFor(false)
                val room = avail - used
                val whole = Block(full, false, g)
                if (whole.height <= room) { cur.add(whole); used += whole.height; return }
                var fit = 0
                for (line in 0 until full.lineCount) {
                    val h = g + (full.getLineBaseline(line) + bodyP.descent() - whole.inkTop).toInt()
                    if (h <= room) fit = line + 1 else break
                }
                if (fit == 1 && full.lineCount >= 2 && cur.size > 1 && cur.last().head) fit = 0
                if (fit == 0) {
                    if (cur.size > 1 && cur.last().head) {  // 小标题别孤零零留在页底
                        val moved = cur.removeAt(cur.lastIndex); flush(); val o = moved.atTop(); cur.add(o); used = o.height; continue
                    }
                    if (cur.isNotEmpty()) { flush(); continue }
                    fit = 1
                }
                if (fit >= full.lineCount) { cur.add(whole); used += whole.height; return }
                val part = layoutRaw(full.text.subSequence(0, full.getLineEnd(fit - 1)), bodyP, contentW, bodyMult)
                val blk = Block(part, false, g); cur.add(blk); used += blk.height
                left = full.text.subSequence(full.getLineStart(fit), full.text.length)
                flush()
            }
        }
        for ((pi, p) in paras.withIndex()) {
            val ml = Md.line(p, paras.size)
            when (ml.kind) { MdKind.H2, MdKind.H3 -> placeHead(Md.strip(ml.text)); MdKind.HR -> {}; else -> placeBody(Md.plainLine(ml)) }
            pics[pi]?.forEach { (b, c) -> placeImage(b, c) }
        }
        if (cur.isNotEmpty()) pageList.add(cur)
        var pi = 0
        while (pi < pageList.size - 1) {
            val page = pageList[pi]
            if (page.isNotEmpty() && page.last().head) {
                val orphan = page.removeAt(page.lastIndex).atTop()
                if (page.isEmpty()) pageList.removeAt(pi) else pi++
                val nx = pageList[pi]; if (nx.isNotEmpty()) nx[0] = nx[0].withGap(rh.belowHead.toInt())
                nx.add(0, orphan)
            } else pi++
        }
        if (pageList.isEmpty()) pageList.add(mutableListOf())
        lastGeometry = pageList.map { pg -> pg.map { Triple(it.head, it.gap, it.inkBottom - it.inkTop) } }
        // 超长保护：先算页数再画图，避免一次性申请几十张大图导致闪退
        if (pageList.size > MAX_PAGES) throw TooLongError("按现在的字号和行距，这篇文章需要 ${pageList.size} 张配图，超过了上限 $MAX_PAGES 张（小红书单篇最多 18 张）。建议调小字号或行距，或者把文章拆成两篇。")
        val n = pageList.size.coerceAtLeast(1)
        val foot = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = kind.sub2; typeface = if (kind == BodyKind.BOOK) tf else Typeface.DEFAULT; letterSpacing = .06f }
        val footY = height - vm / 2f + 10f
        val bottoms = mutableListOf<Int>()
        val out = pageList.mapIndexed { idx, blks ->
            // 非末页：把剩余空白分摊到段距（每处最多半行），让末行落在统一的下边距
            val usedH = blks.sumOf { it.height }
            val slots = blks.drop(1).count { it.gap > 0 }
            val extra = if (idx < pageList.size - 1 && slots > 0) ((avail - usedH).toFloat() / slots).coerceIn(0f, rh.line * justifyCap) else 0f
            var yy = top.toFloat(); var k = 0
            val ys = blks.map { blk -> val add = if (k++ > 0 && blk.gap > 0) extra else 0f; val y0 = yy + add; yy = y0 + blk.height; y0 }
            bottoms += yy.toInt()
            if (countOnly) return@mapIndexed DUMMY
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            if (kind.bg == kind.bg2) c.drawColor(kind.bg) else c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, width * .3f, height.toFloat(), kind.bg, kind.bg2, Shader.TileMode.CLAMP) })
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Covers.ACCENT }
            for ((bi, blk) in blks.withIndex()) {
                val y = ys[bi]
                if (blk.img != null) drawPic(c, blk.img, m.toFloat(), y + blk.gap, contentW, blk.imgH, blk.cap)
                else {
                    if (blk.head) { val t0 = y + blk.gap; val l = blk.layout; c.drawRoundRect(m - 30f, t0 + 4f, m - 22f, t0 + (l.getLineBaseline(0) + l.paint.descent() - blk.inkTop) - 4f, 4f, 4f, bar) }
                    c.save(); c.translate(m.toFloat(), y + blk.gap - blk.inkTop); blk.layout.draw(c); c.restore()
                }
            }
            when (kind) {
                BodyKind.PLAIN -> { foot.textAlign = Paint.Align.CENTER; c.drawText("${idx + 1} / $n", width / 2f, footY, foot) }
                BodyKind.PROGRESS -> {
                    val num = String.format(java.util.Locale.US, "%02d", idx + 1); foot.textAlign = Paint.Align.RIGHT
                    c.drawText(num, width - m.toFloat(), footY, foot)
                    val r = width - m - 90f; val cy = footY - 10f
                    c.drawRoundRect(m.toFloat(), cy - 2f, r, cy + 2f, 2f, 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1F14171F })
                    c.drawRoundRect(m.toFloat(), cy - 2f, m + (r - m) * (idx + 1) / n, cy + 2f, 2f, 2f, bar)
                }
                BodyKind.BOOK -> { foot.textAlign = Paint.Align.RIGHT; c.drawText("— ${idx + 1} —", width - m.toFloat(), footY, foot) }
            }
            b
        }
        lastBottoms = bottoms
        lastShape = pageList.map { pg -> pg.map { b -> b.head to (if (b.img != null) 0 else b.layout.lineCount) } }
        lastChars = pageList.map { pg -> pg.filter { it.img == null }.sumOf { b -> b.layout.text.count { !it.isWhitespace() && it != '\u2060' } } }
        return out
    }
    fun vMargin(height: Int) = marginV ?: 96
    /** 边距参数（1.19 方案比较用） */
    @Volatile var marginH = 72
    @Volatile var marginV: Int? = null
    @Volatile var justifyCap = 0f
    @Volatile var tightRhythm = true
    @Volatile var lastChars: List<Int> = emptyList()
    /** 测试用：每页每块（是否小标题, 行数） */
    @Volatile var lastShape: List<List<Pair<Boolean, Int>>> = emptyList()
    /** 测试用：每页末行字迹底部 y */
    @Volatile var lastBottoms: List<Int> = emptyList()

    /** 测试用：最近一次分页的 (是否小标题, 上方可见空白, 字迹高度) */
    @Volatile var lastGeometry: List<List<Triple<Boolean, Int, Int>>> = emptyList()
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
        val quoteP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePx * 0.95f; typeface = tf; color = 0xFF5C5348.toInt(); letterSpacing = 0.03f }
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
                         eyebrow: TextPaint, metaP: TextPaint, blocks: List<LBlock>, ink: Int = 0xFF1E1B17.toInt()) {
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
                c.drawRoundRect(m.toFloat(), top, (w - m).toFloat(), top + b.decoH, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2ECE1.toInt() })
                c.drawRect(m.toFloat(), top, m + 6f, top + b.decoH, Paint().apply { color = 0xFFD3C8B6.toInt() })
            }
            if (b.deco == 2) c.drawRect(m.toFloat(), y + b.height / 2 - 1f, (w - m).toFloat(), y + b.height / 2 + 1f, Paint().apply { color = 0xFFE5DDCF.toInt() })
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
