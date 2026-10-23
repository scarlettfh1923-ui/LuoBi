package com.scarlett.luobi

import android.graphics.*
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan

/** 1.19 小红书封面风格（mockups/v1.19/covers.png） */
enum class CoverKind(val label: String, val sub: String) {
    BIG("大字留白", "米白底 + 超大黑体，一处蓝色强调"),
    MAG("杂志", "刊头 + 衬线大字 + 双栏导语"),
    PAPER("书页纸感", "暖纸色 + 细框 + 居中宋体"),
    QUOTE("引语卡", "大引号金句 + 玻璃标题条"),
    BLOCK("极简色块", "顶部蓝块放关键数字");
    companion object { fun of(i: Int) = entries.getOrElse(i) { BIG } }
}

/** 1.19 正文页版式（mockups/v1.19/body-pages.png）：都不再画标题条 */
enum class BodyKind(val label: String, val sub: String, val bg: Int, val bg2: Int, val ink: Int, val head: Int, val sub2: Int) {
    PLAIN("只留页码", "底部居中 2 / 6", 0xFFF6F5F1.toInt(), 0xFFF6F5F1.toInt(), 0xFF2B2F38.toInt(), 0xFF14171F.toInt(), 0xFF9AA0AA.toInt()),
    PROGRESS("底部进度条", "细进度条 + 页码", 0xFFF7F8FA.toInt(), 0xFFF7F8FA.toInt(), 0xFF2B2F38.toInt(), 0xFF14171F.toInt(), 0xFF9AA0AA.toInt()),
    BOOK("书页", "宋体纸感，右下 — 2 —", 0xFFF1EADB.toInt(), 0xFFE9DFC9.toInt(), 0xFF3B3429.toInt(), 0xFF26211A.toInt(), 0xFFA39883.toInt());
    companion object { fun of(i: Int) = entries.getOrElse(i) { PLAIN } }
}

object Covers {
    const val ACCENT = 0xFF2F6BFF.toInt()
    private val serifBold: Typeface get() = Typeface.create(Typeface.SERIF, 700, false)
    private val serif: Typeface get() = Typeface.create(Typeface.SERIF, 400, false)
    private fun tp(size: Float, color: Int, tf: Typeface = Typeface.DEFAULT, ls: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = tf; letterSpacing = ls }
    private fun lay(t: CharSequence, p: TextPaint, w: Int, mult: Float = 1.18f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, maxLines: Int = Int.MAX_VALUE): StaticLayout {
        val b = StaticLayout.Builder.obtain(t, 0, t.length, p, w.coerceAtLeast(1)).setLineSpacing(0f, mult).setIncludePad(false).setAlignment(align)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (maxLines != Int.MAX_VALUE) b.setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END)
        if (android.os.Build.VERSION.SDK_INT >= 33) b.setLineBreakConfig(android.graphics.text.LineBreakConfig.Builder().setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE).build())
        return b.build()
    }
    /** 字号从 start 往下缩，直到行数 ≤ maxLines 且高度 ≤ maxH */
    private fun fit(t: CharSequence, p: TextPaint, w: Int, start: Float, min: Float, maxLines: Int, maxH: Int, mult: Float = 1.18f, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout {
        var s = start; var l: StaticLayout
        while (true) { p.textSize = s; l = lay(t, p, w, mult, align); if ((l.lineCount <= maxLines && l.height <= maxH) || s <= min) return l; s -= 4f }
    }
    private fun draw(c: Canvas, l: StaticLayout, x: Float, y: Float) { c.save(); c.translate(x, y); l.draw(c); c.restore() }
    private fun fill(c: Canvas, w: Int, h: Int, a: Int, b: Int = a) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w * .3f, h.toFloat(), a, b, Shader.TileMode.CLAMP) })
    }
    private fun clean(s: String) = Md.strip(s).replace(Regex("^#+\\s*"), "").trim()
    private fun bodyText(body: String) = body.replace("\r", "").split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("![") && !it.startsWith(">") }
        .joinToString("") { clean(it) }

    /** 强调片段：最后一个逗号后的短句（2–6 字），否则末尾 4 字 */
    fun highlight(title: String): String {
        val t = title.trim().trimEnd('？', '?', '！', '!', '。', '.', '…')
        val seg = t.split(Regex("[，,、：:；;\\s]")).lastOrNull { it.isNotBlank() }.orEmpty()
        val tail = Regex("[^\\p{IsHan}A-Za-z0-9]").replace(seg, "")
        return when { tail.length in 2..6 && tail != t -> tail.takeLast(4).let { if (tail.length <= 4) tail else it }; t.length > 6 -> t.takeLast(4).filter { it.isLetterOrDigit() || it.toString().matches(Regex("\\p{IsHan}")) }; else -> "" }
    }
    /** 引语：正文里第一句引号内容（4–26 字），否则第一句 */
    fun quote(title: String, body: String): String {
        val t = bodyText(body)
        Regex("[“「『\"]([^”」』\"]{4,26})[”」』\"]").find(t)?.let { return it.groupValues[1].trim() }
        val first = t.split(Regex("(?<=[。！？!?])")).map { it.trim() }.firstOrNull { it.length in 6..30 }
        return first ?: title
    }
    /** 关键数字：标题优先，其次正文；返回 (数字+单位, 所在短句) */
    fun keyNumber(title: String, body: String): Pair<String, String>? {
        val re = Regex("(\\d+(?:\\.\\d+)?)\\s*(%|％|万|亿|小时|分钟|秒|天|年|岁|元|块|集|倍|h|H|个|次|部|本)?")
        fun pick(src: String): Pair<String, String>? {
            val m = re.findAll(src).firstOrNull { val v = it.groupValues[1]; !(v.length == 4 && v.startsWith("19") || v.length == 4 && v.startsWith("20")) || it.groupValues[2].isNotEmpty() && it.groupValues[2] != "年" } ?: return null
            val unit = when (val u = m.groupValues[2]) { "小时" -> "h"; "分钟" -> "min"; "％" -> "%"; else -> u }
            val sent = src.split(Regex("[。！？!?\\n]")).firstOrNull { it.contains(m.value) }.orEmpty().trim()
            return (m.groupValues[1] + unit) to sent
        }
        return pick(title) ?: pick(bodyText(body).take(1500))
    }

    fun render(kind: CoverKind, title: String, tags: List<String>, body: String, width: Int, height: Int, heavy: Typeface = Typeface.create(Typeface.DEFAULT, 900, false)): Bitmap {
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val t = clean(title).ifBlank { "无标题" }
        when (kind) {
            CoverKind.BIG -> big(c, width, height, t, tags, heavy)
            CoverKind.MAG -> mag(c, width, height, t, body)
            CoverKind.PAPER -> paper(c, width, height, t, tags)
            CoverKind.QUOTE -> quoteCard(c, width, height, t, body, heavy)
            CoverKind.BLOCK -> block(c, width, height, t, tags, body, heavy)
        }
        return b
    }

    /** 选择器缩略图：按 3:4 画好再缩到 216×288 */
    fun thumbs(title: String, tags: List<String>, body: String): List<Bitmap> = CoverKind.entries.map { k ->
        val full = render(k, title, tags, body, 1080, 1440)
        Bitmap.createScaledBitmap(full, 216, 288, true).also { full.recycle() }
    }

    private fun tagLine(tags: List<String>) = tags.take(3).joinToString("  ") { "#$it" }

    private fun big(c: Canvas, w: Int, h: Int, t: String, tags: List<String>, heavy: Typeface) {
        fill(c, w, h, 0xFFF7F6F2.toInt(), 0xFFF1EFE9.toInt())
        val m = 104f
        c.drawText("NOTE  ·  " + java.text.SimpleDateFormat("yyyy", java.util.Locale.US).format(java.util.Date()), m, 168f, tp(30f, 0xFFA3A8B2.toInt(), Typeface.DEFAULT, .18f))
        val hl = highlight(t)
        val sp = SpannableString(t)
        if (hl.isNotEmpty()) t.lastIndexOf(hl).takeIf { it >= 0 }?.let { sp.setSpan(ForegroundColorSpan(ACCENT), it, it + hl.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val p = tp(150f, 0xFF111318.toInt(), heavy, .01f)
        val l = fit(sp, p, (w - 2 * m).toInt(), 150f, 76f, 4, (h * .46f).toInt(), 1.16f)
        draw(c, l, m, h * .43f - l.height / 2f)
        c.drawRoundRect(m, h - 196f, m + 64f, h - 189f, 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        val tg = tagLine(tags); val tpg = tp(28f, 0xFFA3A8B2.toInt())
        c.drawText(tg, w - m - tpg.measureText(tg), h - 182f, tpg)
    }

    private fun mag(c: Canvas, w: Int, h: Int, t: String, body: String) {
        fill(c, w, h, 0xFFF0EFEA.toInt(), 0xFFE9E7E0.toInt())
        val m = 92f; val ink = 0xFF16181D.toInt()
        val mast = tp(30f, ink, Typeface.create(Typeface.DEFAULT, 800, false), .14f)
        c.drawText("LUOBI WEEKLY", m, 150f, mast)
        val no = "No." + java.text.SimpleDateFormat("MMdd", java.util.Locale.US).format(java.util.Date())
        val np = tp(28f, ink, Typeface.create(Typeface.DEFAULT, 600, false)); c.drawText(no, w - m - np.measureText(no), 150f, np)
        val line = Paint().apply { color = ink }
        c.drawRect(m, 176f, w - m, 179f, line); c.drawRect(m, 186f, w - m, 187.5f, line)
        val cut = Regex("[，,、：:；;？?！!\\s]").find(t)?.range?.first ?: -1
        val (big, rest) = when { cut in 1..4 -> t.substring(0, cut) to t.substring(cut + 1).trim(); t.length <= 4 -> t to ""; else -> t.take(2) to t.drop(2) }
        val bp = tp(230f, ACCENT, serifBold)
        val bl = fit(big, bp, (w - 2 * m).toInt(), 230f, 120f, 1, 320)
        val top = h * .2f
        draw(c, bl, m, top)
        var y = top + bl.height + 36f
        if (rest.isNotBlank()) { val rl = fit(rest, tp(76f, ink, serifBold), (w - 2 * m).toInt(), 76f, 50f, 3, (h * .25f).toInt(), 1.25f); draw(c, rl, m, y); y += rl.height }
        // 双栏导语
        val ex = bodyText(body).take(160)
        if (ex.isNotBlank()) {
            val colW = ((w - 2 * m - 48f) / 2).toInt()
            val ep = tp(30f, 0xFF4A4E57.toInt(), serif)
            val full = lay(ex, ep, colW, 1.5f)
            val n = minOf(3, full.lineCount)
            val left = ex.substring(0, full.getLineEnd(n - 1))
            val right = ex.substring(full.getLineEnd(n - 1))
            val ly = h - 150f - lay("中\n中\n中", ep, colW, 1.5f).height
            c.drawRect(m, ly - 34f, w - m, ly - 32f, Paint().apply { color = 0x5516181D })
            draw(c, lay(left, ep, colW, 1.5f, maxLines = 3), m, ly)
            if (right.isNotBlank()) draw(c, lay(right, ep, colW, 1.5f, maxLines = 3), m + colW + 48f, ly)
        }
    }

    private fun paper(c: Canvas, w: Int, h: Int, t: String, tags: List<String>) {
        fill(c, w, h, 0xFFF2EBDC.toInt(), 0xFFE8DDC6.toInt())
        val ink = 0xFF2B251C.toInt(); val faint = 0xFFA89B80.toInt()
        c.drawRect(54f, 54f, w - 54f, h - 54f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFFCDBE9E.toInt() })
        val cp = tp(28f, faint, serif, .3f).apply { textAlign = Paint.Align.CENTER }
        c.drawText("落 笔 · 随 笔", w / 2f, 190f, cp)
        val l = fit(t, tp(96f, ink, serifBold, .06f), (w - 260), 96f, 58f, 4, (h * .42f).toInt(), 1.42f, Layout.Alignment.ALIGN_CENTER)
        val top = h * .45f - l.height / 2f
        draw(c, l, 130f, top)
        c.drawCircle(w / 2f, top + l.height + 70f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = faint })
        val foot = if (tags.isEmpty()) "— 落笔 —" else "— 关于 " + tags.take(2).joinToString("、") + " —"
        c.drawText(foot, w / 2f, h - 150f, cp.apply { letterSpacing = .08f })
    }

    private fun quoteCard(c: Canvas, w: Int, h: Int, t: String, body: String, heavy: Typeface) {
        fill(c, w, h, 0xFFF1F4F9.toInt(), 0xFFE2E8F2.toInt())
        val m = 110f; val ink = 0xFF161A22.toInt()
        val qp = tp(220f, ACCENT, serifBold)
        c.drawText("“", m - 10f, h * .25f, qp)
        val q = quote(t, body)
        val ql = fit(q, tp(84f, ink, serifBold, .02f), (w - 2 * m).toInt(), 84f, 52f, 4, (h * .32f).toInt(), 1.38f)
        val qTop = h * .29f
        draw(c, ql, m, qTop)
        val at = "—— 摘自《" + android.text.TextUtils.ellipsize(t, tp(30f, 0), 520f, android.text.TextUtils.TruncateAt.END) + "》"
        c.drawText(at, m, qTop + ql.height + 74f, tp(30f, 0xFF8A93A3.toInt()))
        // 玻璃标题条
        val bw = w - 2 * 72f
        val tl = fit(t, tp(44f, ink, Typeface.create(heavy, 800, false)), (bw - 88).toInt(), 44f, 34f, 2, 140, 1.3f)
        val bh = tl.height + 72f
        val bt = h - 140f - bh
        val r = RectF(72f, bt, 72f + bw, bt + bh)
        c.drawRoundRect(RectF(r.left, r.top + 10f, r.right, r.bottom + 10f), 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000; maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL) })
        c.drawRoundRect(r, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() })
        c.drawRoundRect(r, 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x22FFFFFF or 0x10000000 })
        draw(c, tl, r.left + 44f, r.top + 36f)
    }

    private fun block(c: Canvas, w: Int, h: Int, t: String, tags: List<String>, body: String, heavy: Typeface) {
        c.drawColor(0xFFF8F9FB.toInt())
        val m = 96f
        val bh = h * .38f
        c.drawRect(0f, 0f, w.toFloat(), bh, Paint().apply { color = ACCENT })
        val kn = keyNumber(t, body)
        val big = kn?.first ?: t.take(2)
        val np = tp(250f, Color.WHITE, heavy, -.01f)
        val nl = fit(big, np, (w - 2 * m).toInt(), 250f, 120f, 1, 300, 1f)
        val capTxt = kn?.second?.takeIf { it.isNotBlank() && it != t }?.let { android.text.TextUtils.ellipsize(it, tp(32f, 0), w - 2 * m, android.text.TextUtils.TruncateAt.END).toString() }
        val capH = if (capTxt != null) 56f else 0f
        val ny = (bh - nl.height - capH) / 2f + 10f
        draw(c, nl, m, ny)
        capTxt?.let { c.drawText(it, m, ny + nl.height + 44f, tp(32f, 0xDDFFFFFF.toInt())) }
        val tl = fit(t, tp(92f, 0xFF111318.toInt(), heavy), (w - 2 * m).toInt(), 92f, 56f, 4, (h * .36f).toInt(), 1.24f)
        draw(c, tl, m, bh + (h - bh - 180f - tl.height) / 2f)
        c.drawText(tagLine(tags), m, h - 140f, tp(28f, 0xFFA3A8B2.toInt()))
    }
}
