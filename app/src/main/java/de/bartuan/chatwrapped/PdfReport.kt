package de.bartuan.chatwrapped

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import de.bartuan.chatwrapped.analysis.AnalysisResult
import de.bartuan.chatwrapped.analysis.ASection
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/** Where pages go: a real PdfDocument, or bitmaps (tests / previews). */
interface PageSink { fun begin(n: Int): Canvas; fun end() }

class PdfSink : PageSink {
    val doc = PdfDocument(); private var page: PdfDocument.Page? = null
    override fun begin(n: Int): Canvas { page = doc.startPage(PdfDocument.PageInfo.Builder(PdfReport.W, PdfReport.H, n).create()); return page!!.canvas }
    override fun end() { doc.finishPage(page!!) }
    fun bytes(): ByteArray = ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
}

class BitmapSink(private val scale: Float = 2f) : PageSink {
    val pages = ArrayList<Bitmap>()
    override fun begin(n: Int): Canvas {
        val b = Bitmap.createBitmap((PdfReport.W * scale).toInt(), (PdfReport.H * scale).toInt(), Bitmap.Config.ARGB_8888)
        pages += b; return Canvas(b).apply { scale(scale, scale) }
    }
    override fun end() {}
}

/** A4 report of an analysis result: cover + score, key stats, charts with NAMES, all sections, quotes with name tags. */
object PdfReport {
    const val W = 595; const val H = 842
    private const val M = 40f
    private val INK = Color.rgb(0x2B, 0x21, 0x40); private val SOFT = Color.rgb(0x5F, 0x55, 0x77)
    private val ACCENT = Color.rgb(0x8E, 0x7B, 0xFF); private val PINK = Color.rgb(0xFF, 0x8F, 0xAB)
    private val SERIES = intArrayOf(0xFF8E7BFF.toInt(), 0xFFFF8FAB.toInt(), 0xFF5CC8B0.toInt(), 0xFFFFB45E.toInt(), 0xFF5FA8FF.toInt(), 0xFFE48BE6.toInt(), 0xFFA3C95A.toInt(), 0xFFFF7A7A.toInt())
    private val TINTS = intArrayOf(0xFFFFF1F5.toInt(), 0xFFF3F0FF.toInt(), 0xFFEDFAF6.toInt(), 0xFFFFF6EC.toInt(), 0xFFEFF6FF.toInt())

    fun pdf(r: AnalysisResult, s: ChatStats, title: String): ByteArray = PdfSink().also { render(it, r, s, title) }.bytes()

    fun render(sink: PageSink, r: AnalysisResult, s: ChatStats, title: String) = Writer(sink, s.ranked.map { it.name }, title).run {
        cover(r, s); keyStats(s); charts(s)
        r.sections.forEachIndexed { i, sec -> section(sec, i) }
        finish()
    }

    private fun tp(size: Float, color: Int = INK, bold: Boolean = false, italic: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL })
    }
    private fun layout(t: String, p: TextPaint, w: Float): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, w.toInt().coerceAtLeast(10)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()
    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

    fun colorOf(name: String, people: List<String>): Int {
        val i = people.indexOfFirst { it == name || it.split(' ').first() == name.split(' ').first() }
        return if (i < 0) SOFT else SERIES[i % SERIES.size]
    }

    private class Writer(val sink: PageSink, val people: List<String>, val title: String) {
        var page = 0; lateinit var c: Canvas; var y = 0f
        val bottom = H - 50f; val cw = W - 2 * M
        init { newPage() }

        fun newPage() {
            if (page > 0) { footer(); sink.end() }
            page++; c = sink.begin(page); c.drawColor(Color.WHITE); y = M
            if (page > 1) {
                c.drawText("Chat Wrapped · $title", M, 26f, tp(9f, SOFT)); c.drawRect(M, 32f, W - M, 33f, fill(0x22000000)); y = 48f
            }
        }
        fun footer() {
            val p = tp(9f, SOFT); c.drawText("Erstellt mit Chat Wrapped", M, H - 24f, p)
            val t = "Seite $page"; c.drawText(t, W - M - p.measureText(t), H - 24f, p)
        }
        fun finish() { footer(); sink.end() }
        fun need(h: Float) { if (y + h > bottom) newPage() }
        fun text(t: String, p: TextPaint, x: Float = M, w: Float = cw): Float {
            val l = layout(t, p, w)
            // split long paragraphs across pages line by line
            var line = 0
            while (line < l.lineCount) {
                val lh = (l.getLineBottom(line) - l.getLineTop(line)).toFloat()
                need(lh)
                var last = line; var hgt = 0f
                while (last < l.lineCount && y + hgt + (l.getLineBottom(last) - l.getLineTop(last)) <= bottom) { hgt += l.getLineBottom(last) - l.getLineTop(last); last++ }
                c.save(); c.translate(x, y - l.getLineTop(line)); c.clipRect(0f, l.getLineTop(line).toFloat(), w, l.getLineTop(line) + hgt); l.draw(c); c.restore()
                y += hgt; line = last
            }
            return l.height.toFloat()
        }
        fun chip(name: String, x: Float, yy: Float): Float {
            val p = tp(10f, INK, bold = true); val tw = p.measureText(name)
            c.drawRoundRect(RectF(x, yy, x + tw + 26f, yy + 18f), 9f, 9f, fill(0xFFF1EEF8.toInt()))
            c.drawCircle(x + 9f, yy + 9f, 4.5f, fill(colorOf(name, people)))
            c.drawText(name, x + 18f, yy + 13f, p); return tw + 26f
        }

        fun cover(r: AnalysisResult, s: ChatStats) {
            val h = 210f
            val bg = fill(0).apply { shader = LinearGradient(M, M, W - M, M + h, Color.rgb(0xFF, 0xD6, 0xE0), Color.rgb(0xD9, 0xD2, 0xFF), Shader.TileMode.CLAMP) }
            c.drawRoundRect(RectF(M, y, W - M, y + h), 22f, 22f, bg)
            val df = DateTimeFormatter.ofPattern("d. MMM yyyy", DE)
            c.drawText("CHAT WRAPPED · ANALYSE", M + 22f, y + 34f, tp(10f, SOFT, bold = true).apply { letterSpacing = .15f })
            val tl = layout(title, tp(26f, INK, bold = true), cw - 190f); c.save(); c.translate(M + 22f, y + 46f); tl.draw(c); c.restore()
            var ty = y + 52f + tl.height
            c.drawText("${s.first.toLocalDate().format(df)} – ${s.last.toLocalDate().format(df)}", M + 22f, ty + 12f, tp(11f, SOFT)); ty += 22f
            var cx = M + 22f; people.take(6).forEach { cx += chip(it, cx, ty) + 6f }
            r.summary?.let { val sl = layout(it, tp(11f, INK, italic = true), cw - 190f); c.save(); c.translate(M + 22f, ty + 28f); sl.draw(c); c.restore() }
            r.score?.let { sc ->
                val ccx = W - M - 85f; val ccy = y + h / 2; val rad = 58f
                c.drawCircle(ccx, ccy, rad, fill(0xB3FFFFFF.toInt()))
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = 0x22000000; strokeCap = Paint.Cap.ROUND }
                val ov = RectF(ccx - rad + 8, ccy - rad + 8, ccx + rad - 8, ccy + rad - 8)
                c.drawArc(ov, 0f, 360f, false, ring); ring.color = PINK; c.drawArc(ov, -90f, 360f * sc / 100, false, ring)
                val p = tp(30f, INK, bold = true); val t = "$sc"; c.drawText(t, ccx - p.measureText(t) / 2, ccy + 8f, p)
                val q = tp(9f, SOFT, bold = true); val u = "von 100"; c.drawText(u, ccx - q.measureText(u) / 2, ccy + 24f, q)
                val k = "Kompatibilität"; c.drawText(k, ccx - q.measureText(k) / 2, ccy + rad + 14f, q)
            }
            y += h + 18f
        }

        fun heading(t: String, emoji: String? = null) {
            need(60f)
            c.drawRoundRect(RectF(M, y + 2f, M + 4f, y + 22f), 2f, 2f, fill(ACCENT))
            c.drawText((emoji?.let { "$it  " } ?: "") + t, M + 12f, y + 18f, tp(15f, INK, bold = true)); y += 30f
        }

        fun keyStats(s: ChatStats) {
            heading("Die wichtigsten Zahlen", "📊")
            val tiles = listOf(fmtInt(s.total) to "Nachrichten", fmtInt(s.words) to "Wörter", "${s.activeDays} / ${s.spanDays}" to "Tage aktiv",
                "%.1f".format(DE, s.perDayAvg) to "Ø pro Tag", "${s.longestStreak}" to "Tage-Serie", fmtDuration(s.silenceSeconds) to "längste Pause")
            val tw = (cw - 5 * 8f) / 6
            tiles.forEachIndexed { i, (v, l) ->
                val x = M + i * (tw + 8f)
                c.drawRoundRect(RectF(x, y, x + tw, y + 52f), 12f, 12f, fill(TINTS[i % TINTS.size]))
                val vp = tp(if (v.length > 7) 11f else 15f, INK, bold = true); c.drawText(v, x + 8f, y + 24f, vp)
                c.drawText(l, x + 8f, y + 40f, tp(8.5f, SOFT))
            }
            y += 66f
        }

        /** Horizontal bars, every row labelled with the person's NAME. */
        fun nameBars(label: String, rows: List<Pair<String, Double>>, fmt: (Double) -> String) {
            need(24f + rows.size * 20f)
            c.drawText(label, M, y + 10f, tp(10.5f, INK, bold = true)); y += 18f
            val mx = rows.maxOfOrNull { it.second }?.coerceAtLeast(1e-9) ?: 1.0
            val nameW = 110f; val valW = 70f; val bw = cw - nameW - valW - 10f
            rows.forEach { (n, v) ->
                c.drawCircle(M + 5f, y + 7f, 4.5f, fill(colorOf(n, people)))
                c.drawText(n.take(18), M + 14f, y + 11f, tp(10f, INK, bold = true))
                c.drawRoundRect(RectF(M + nameW, y + 1f, M + nameW + bw, y + 13f), 6f, 6f, fill(0xFFF1EEF8.toInt()))
                c.drawRoundRect(RectF(M + nameW, y + 1f, M + nameW + (bw * (v / mx)).toFloat().coerceAtLeast(6f), y + 13f), 6f, 6f, fill(colorOf(n, people)))
                val t = fmt(v); val p = tp(10f, INK, bold = true); c.drawText(t, W - M - p.measureText(t), y + 11f, p)
                y += 20f
            }
            y += 8f
        }

        fun charts(s: ChatStats) {
            val top = s.ranked.take(if (s.isGroup) 8 else 2)
            heading("Auf einen Blick", "📈")
            nameBars("Wer schreibt mehr? (Anteil der Nachrichten)", top.map { it.name to it.messages * 100.0 / s.total }) { fmtPct(it, 1) }
            nameBars("Antwortzeit (Median)", top.filter { it.medianReplySec >= 0 }.map { it.name to it.medianReplySec.toDouble() }) { fmtDuration(it.toLong()) }
            nameBars("Wer startet Gespräche?", top.map { it.name to it.starts.toDouble() }) { fmtInt(it) }
            nameBars("Lach-Index", top.map { it.name to it.laughIndex }) { fmtPct(it) }
            // activity by hour (whole chat)
            need(110f)
            c.drawText("Aktivität nach Uhrzeit (alle)", M, y + 10f, tp(10.5f, INK, bold = true)); y += 18f
            val mx = (s.byHour.maxOrNull() ?: 1).coerceAtLeast(1); val bw = cw / 24f; val hh = 64f
            for (h in 0 until 24) {
                val bh = hh * s.byHour[h] / mx
                c.drawRoundRect(RectF(M + h * bw + 2f, y + hh - bh, M + (h + 1) * bw - 2f, y + hh), 3f, 3f, fill(if (h == s.peakHour) PINK else ACCENT))
                if (h % 3 == 0) c.drawText("$h", M + h * bw + 3f, y + hh + 12f, tp(8f, SOFT))
            }
            y += hh + 26f
        }

        fun section(sec: ASection, i: Int) {
            heading(sec.title, sec.emoji)
            sec.headline?.let { text(it, tp(13f, INK, bold = true)); y += 4f }
            sec.score?.let { sc ->
                need(20f); c.drawRoundRect(RectF(M, y, W - M, y + 10f), 5f, 5f, fill(0xFFF1EEF8.toInt()))
                c.drawRoundRect(RectF(M, y, M + cw * sc / 100f, y + 10f), 5f, 5f, fill(PINK)); c.drawText("$sc / 100", M, y + 24f, tp(10f, INK, bold = true)); y += 32f
            }
            sec.body?.let { text(it.replace("**", ""), tp(10.5f, INK)); y += 6f }
            sec.people.forEach { p ->
                val tl = p.text?.let { layout(it, tp(10f, INK), cw - 24f) }
                val h = 30f + (tl?.height ?: 0)
                need(h + 6f)
                c.drawRoundRect(RectF(M, y, W - M, y + h), 12f, 12f, fill(TINTS[i % TINTS.size]))
                chip(p.name, M + 10f, y + 6f)
                p.value?.let { val vp = tp(10.5f, INK, bold = true); c.drawText(it, W - M - 10f - vp.measureText(it), y + 19f, vp) }
                tl?.let { c.save(); c.translate(M + 12f, y + 28f); it.draw(c); c.restore() }
                y += h + 6f
            }
            sec.timeline.forEach { ph ->
                val tl = layout(ph.title + (ph.text?.let { "\n$it" } ?: ""), tp(10f, INK), cw - 100f)
                need(tl.height + 8f)
                c.drawCircle(M + 5f, y + 7f, 4f, fill(ACCENT))
                c.drawText(ph.period, M + 14f, y + 11f, tp(9.5f, ACCENT, bold = true))
                c.save(); c.translate(M + 100f, y); tl.draw(c); c.restore(); y += tl.height + 8f
            }
            sec.bullets.forEach { b -> text("•  $b", tp(10.5f, INK), M + 6f, cw - 6f); y += 3f }
            sec.quotes.forEach { q ->
                val ql = layout("„${q.text}“", tp(11f, INK, italic = true), cw - 30f)
                val nl = q.note?.let { layout(it, tp(9f, SOFT), cw - 30f) }
                val h = 32f + ql.height + (nl?.height ?: 0)
                need(h + 6f)
                c.drawRoundRect(RectF(M, y, W - M, y + h), 12f, 12f, fill(0xFFFAF8FF.toInt()))
                c.drawRoundRect(RectF(M, y, M + 4f, y + h), 2f, 2f, fill(q.sender?.let { colorOf(it, people) } ?: SOFT))
                q.sender?.let { chip(it, M + 12f, y + 6f) }
                q.date?.let { val dp = tp(9f, SOFT); c.drawText(it, W - M - 10f - dp.measureText(it), y + 18f, dp) }
                c.save(); c.translate(M + 14f, y + 28f); ql.draw(c); nl?.let { c.translate(0f, ql.height.toFloat()); it.draw(c) }; c.restore()
                y += h + 6f
            }
            y += 10f
        }
    }
}
