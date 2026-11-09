package de.bartuan.chatwrapped.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

@Composable
fun Donut(values: List<Pair<String, Int>>, modifier: Modifier = Modifier, center: String = "") {
    val total = values.sumOf { it.second }.coerceAtLeast(1)
    val tm = rememberTextMeasurer()
    val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Pastel.ink)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.18f
            val d = size.minDimension - stroke
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            var start = -90f
            values.forEachIndexed { i, (_, v) ->
                val sweep = 360f * v / total
                drawArc(Pastel.series[i % Pastel.series.size], start, (sweep - 1.5f).coerceAtLeast(0.5f), false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
            // name label OUTSIDE each segment (colour is never the only hint)
            start = -90f
            values.forEach { (name, v) ->
                val sweep = 360f * v / total
                if (sweep >= 8f) {
                    val mid = Math.toRadians((start + sweep / 2).toDouble())
                    val r = d / 2 + stroke / 2 + 6f
                    val lay = tm.measure(name, style, maxLines = 1)
                    val cx = size.width / 2 + (r * cos(mid)).toFloat(); val cy = size.height / 2 + (r * sin(mid)).toFloat()
                    val x = if (cos(mid) >= 0) cx + 4f else cx - lay.size.width - 4f
                    val y = cy - lay.size.height / 2f
                    drawRoundRect(Color.White.copy(alpha = .85f), Offset(x - 6f, y - 2f), Size(lay.size.width + 12f, lay.size.height + 4f), CornerRadius(12f, 12f))
                    drawText(lay, topLeft = Offset(x, y))
                }
                start += sweep
            }
        }
        if (center.isNotEmpty()) Text(center, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Pastel.ink)
    }
}

@Composable
fun Legend(values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEachIndexed { i, (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).padding(1.dp)) { Canvas(Modifier.fillMaxSize()) { drawCircle(Pastel.series[i % Pastel.series.size]) } }
                Spacer(Modifier.width(8.dp))
                Text(k, Modifier.weight(1f), color = Pastel.ink, fontSize = 16.sp, maxLines = 1)
                Text(v, color = Pastel.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

/** Vertical bars with labels under selected bars. */
@Composable
fun Bars(values: List<Int>, labels: List<String>, modifier: Modifier = Modifier, highlight: Int = -1, color: Color = Pastel.accent) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = values.size; val gap = size.width / n * 0.22f; val w = size.width / n - gap
            values.forEachIndexed { i, v ->
                val h = size.height * v / max
                drawRoundRect(if (i == highlight) Pastel.pink else color, Offset(i * (w + gap) + gap / 2, size.height - h), Size(w, h.coerceAtLeast(2f)), CornerRadius(w / 3, w / 3))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, color = Pastel.inkSoft, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

/** Horizontal bars: label – bar – value. */
@Composable
fun HBars(rows: List<Pair<String, Double>>, valueText: (Double) -> String, modifier: Modifier = Modifier) {
    val max = (rows.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(1e-9)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Pastel.series[i % Pastel.series.size]))
                Spacer(Modifier.width(6.dp))
                Text(k, Modifier.width(88.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Pastel.ink, maxLines = 1)
                Box(Modifier.weight(1f).height(18.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(Color.White.copy(alpha = .55f), cornerRadius = CornerRadius(9f, 9f))
                        drawRoundRect(Pastel.series[i % Pastel.series.size], size = Size((size.width * (v / max)).toFloat().coerceAtLeast(6f), size.height), cornerRadius = CornerRadius(size.height / 2, size.height / 2))
                    }
                }
                Text(valueText(v), Modifier.width(72.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Pastel.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

/** Lines per person, values 0..1. */
@Composable
fun TrendLines(series: List<Pair<String, List<Double>>>, labels: List<String>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            drawLine(Pastel.inkSoft.copy(alpha = .3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f)
            series.forEachIndexed { si, (_, vs) ->
                if (vs.size < 2) return@forEachIndexed
                val p = Path()
                vs.forEachIndexed { i, v ->
                    val x = size.width * i / (vs.size - 1); val y = size.height * (1 - v.toFloat())
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, Pastel.series[si % Pastel.series.size], style = Stroke(6f, cap = StrokeCap.Round))
                val lay = tm.measure(series[si].first, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Pastel.ink), maxLines = 1)
                val ly = (size.height * (1 - vs.last().toFloat()) - lay.size.height - 6f).coerceIn(0f, size.height - lay.size.height)
                val lx = size.width - lay.size.width - 4f
                drawRoundRect(Color.White.copy(alpha = .85f), Offset(lx - 6f, ly - 2f), Size(lay.size.width + 12f, lay.size.height + 4f), CornerRadius(12f, 12f))
                drawText(lay, topLeft = Offset(lx, ly))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { Text(it, fontSize = 11.sp, color = Pastel.inkSoft) }
        }
    }
}

/** GitHub-style calendar heatmap of the last [weeks] weeks up to [end]. */
@Composable
fun Heatmap(byDay: Map<LocalDate, Int>, end: LocalDate, weeks: Int, modifier: Modifier = Modifier) {
    val lastMonday = end.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val startMonday = lastMonday.minusWeeks((weeks - 1).toLong())
    val inRange = byDay.filterKeys { !it.isBefore(startMonday) && !it.isAfter(end) }
    val sorted = inRange.values.sorted()
    val q = if (sorted.isEmpty()) listOf(1, 1, 1) else listOf(0.25, 0.5, 0.75).map { sorted[((sorted.size - 1) * it).toInt()].coerceAtLeast(1) }
    val levels = listOf(Color.White.copy(alpha = .6f), Color(0xFFE3DCFF), Color(0xFFC2B5FF), Color(0xFF9F8CFF), Color(0xFF7259F0))
    Canvas(modifier) {
        val cell = minOf(size.width / weeks, size.height / 7)
        val pad = cell * 0.14f
        for (w in 0 until weeks) for (d in 0 until 7) {
            val day = startMonday.plusDays(w * 7L + d)
            if (day.isAfter(end)) continue
            val v = inRange[day] ?: 0
            val lvl = when { v == 0 -> 0; v <= q[0] -> 1; v <= q[1] -> 2; v <= q[2] -> 3; else -> 4 }
            drawRoundRect(levels[lvl], Offset(w * cell + pad / 2, d * cell + pad / 2), Size(cell - pad, cell - pad), CornerRadius(cell / 4, cell / 4))
        }
    }
}

fun heatmapWeeks(first: LocalDate, last: LocalDate): Int = (ChronoUnit.WEEKS.between(first, last) + 1).toInt().coerceIn(8, 53)

/** Calendar heatmap as mini month calendars (Mo–So columns), last [months] months; readable in portrait. */
@Composable
fun MonthHeatmap(byDay: Map<LocalDate, Int>, end: LocalDate, months: Int, modifier: Modifier = Modifier) {
    val last = java.time.YearMonth.from(end)
    val list = (months - 1 downTo 0).map { last.minusMonths(it.toLong()) }
    val sorted = byDay.filterKeys { java.time.YearMonth.from(it) in list }.values.sorted()
    val q = if (sorted.isEmpty()) listOf(1, 1, 1) else listOf(0.25, 0.5, 0.75).map { sorted[((sorted.size - 1) * it).toInt()].coerceAtLeast(1) }
    val levels = listOf(Color.White.copy(alpha = .6f), Color(0xFFE3DCFF), Color(0xFFC2B5FF), Color(0xFF9F8CFF), Color(0xFF7259F0))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { ym ->
                    Column(Modifier.weight(1f)) {
                        Text(ym.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.GERMANY) + " " + (ym.year % 100).toString().padStart(2, '0'),
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Pastel.inkSoft)
                        Canvas(Modifier.fillMaxWidth().aspectRatio(7f / 6f)) {
                            val cell = size.width / 7; val pad = cell * 0.14f
                            val offset = ym.atDay(1).dayOfWeek.value - 1
                            for (d in 1..ym.lengthOfMonth()) {
                                val idx = offset + d - 1
                                val v = byDay[ym.atDay(d)] ?: 0
                                val lvl = when { v == 0 -> 0; v <= q[0] -> 1; v <= q[1] -> 2; v <= q[2] -> 3; else -> 4 }
                                drawRoundRect(levels[lvl], Offset((idx % 7) * cell + pad / 2, (idx / 7) * cell + pad / 2), Size(cell - pad, cell - pad), CornerRadius(cell / 4, cell / 4))
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
