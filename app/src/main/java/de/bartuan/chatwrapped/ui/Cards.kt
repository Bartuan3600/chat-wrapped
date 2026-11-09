@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package de.bartuan.chatwrapped.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bartuan.chatwrapped.*
import java.time.format.DateTimeFormatter

class StoryCard(
    val key: String,
    val emoji: String,
    val title: String,
    val headline: String,
    val content: @Composable ColumnScope.() -> Unit = {},
)

@Composable
fun CardFrame(card: StoryCard, index: Int, modifier: Modifier = Modifier) {
    val (a, b) = Pastel.gradients[index % Pastel.gradients.size]
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(a, b))).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(card.emoji, fontSize = 44.sp)
        Text(card.title.uppercase(), fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = Pastel.inkSoft)
        Text(card.headline, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, color = Pastel.ink)
        card.content(this)
    }
}

@Composable
private fun Body(t: String) = Text(t, fontSize = 17.sp, lineHeight = 24.sp, color = Pastel.ink)

@Composable
private fun Big(t: String, sub: String) = Column {
    Text(t, fontSize = 40.sp, fontWeight = FontWeight.Black, color = Pastel.accent)
    Text(sub, fontSize = 15.sp, color = Pastel.inkSoft)
}

@Composable
private fun StatRow(items: List<Pair<String, String>>) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    items.forEach { (v, l) ->
        Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .55f)).padding(12.dp)) {
            Text(v, fontSize = 22.sp, fontWeight = FontWeight.Black, color = Pastel.ink, maxLines = 1)
            Text(l, fontSize = 12.sp, color = Pastel.inkSoft, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun Chips(items: List<String>) = androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    items.forEach { Text(it, Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .65f)).padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 16.sp, color = Pastel.ink, fontWeight = FontWeight.SemiBold) }
}

private fun short(n: String) = n.split(' ').first().take(14)

fun buildCards(s: ChatStats, title: String): List<StoryCard> {
    val r = s.ranked
    val top = r.take(if (s.isGroup) 6 else 2)
    val cards = ArrayList<StoryCard>()
    val df = DateTimeFormatter.ofPattern("d. MMM yyyy", DE)

    cards += StoryCard("intro", "🎁", "Dein Chat Wrapped", title.ifBlank { r.joinToString(" & ") { short(it.name) } }) {
        Body("${s.first.toLocalDate().format(df)} – ${s.last.toLocalDate().format(df)}")
        Body(if (s.isGroup) "Gruppenchat mit ${s.people.size} Personen" else "Ein Chat zwischen ${r.joinToString(" und ") { short(it.name) }}")
        Spacer(Modifier.height(8.dp)); Body("Wisch nach links für deine Highlights ➜")
    }
    cards += StoryCard("count", "💬", "Nachrichten", "${fmtInt(s.total)} Nachrichten") {
        StatRow(listOf(fmtInt(s.words) to "Wörter", fmtInt(s.activeDays) to "aktive Tage", "%.1f".format(DE, s.perDayAvg) to "pro Tag"))
        Body("In ${fmtInt(s.spanDays)} Tagen. ${s.conversations} einzelne Gespräche (neu nach 6 Std. Pause).")
    }
    cards += StoryCard("share", "🍩", "Wer schreibt mehr?", "${short(r[0].name)} liegt vorn") {
        val vals = top.map { short(it.name) to it.messages } + (if (r.size > top.size) listOf("Andere" to r.drop(top.size).sumOf { it.messages }) else emptyList())
        Donut(vals, Modifier.fillMaxWidth().height(230.dp).padding(horizontal = 48.dp, vertical = 16.dp), fmtPct(r[0].messages * 100.0 / s.total))
        Legend(vals.map { it.first to "${fmtInt(it.second)} · ${fmtPct(it.second * 100.0 / s.total)}" })
    }
    val starter = s.people.values.maxBy { it.starts }
    cards += StoryCard("starter", "👋", "Wer schreibt zuerst?", "${short(starter.name)} startet ${fmtPct(starter.starts * 100.0 / s.conversations.coerceAtLeast(1))} der Gespräche") {
        HBars(top.map { short(it.name) to it.starts.toDouble() }, { fmtInt(it) })
        val trend = top.take(2).map { it.name to s.initiativeShare(it.name).map { p -> p.second } }
        if (!s.isGroup && s.initiativeByMonth.size >= 3) {
            Text("Initiative-Trend pro Monat", fontWeight = FontWeight.Bold, color = Pastel.ink)
            TrendLines(trend.take(1).map { short(it.first) to it.second }, listOf(s.initiativeByMonth.firstKey().toString(), s.initiativeByMonth.lastKey().toString()), Modifier.fillMaxWidth().height(110.dp))
            Body("Linie = Anteil der Gesprächsstarts von ${short(r[0].name)}; Mitte = 50 %.")
        }
    }
    val fast = r.filter { it.medianReplySec >= 0 }.minByOrNull { it.medianReplySec }
    cards += StoryCard("reply", "⚡", "Antwortzeit", fast?.let { "${short(it.name)} antwortet am schnellsten" } ?: "Antwortzeiten") {
        HBars(top.filter { it.medianReplySec >= 0 }.map { short(it.name) to it.medianReplySec.toDouble() }, { fmtDuration(it.toLong()) })
        Text("Verteilung aller Antworten", fontWeight = FontWeight.Bold, color = Pastel.ink)
        Bars(s.replyHist.toList(), listOf("<1m", "5m", "15m", "1h", "3h", "12h", ">12h"), Modifier.fillMaxWidth().height(130.dp), highlight = s.replyHist.indices.maxBy { s.replyHist[it] })
    }
    s.recordDay?.let { rd ->
        cards += StoryCard("record", "🏆", "Rekordtag", rd.key.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", DE))) {
            Big(fmtInt(rd.value), "Nachrichten an einem einzigen Tag")
            Body("Das ist ${"%.0f".format(DE, rd.value / s.perDayAvg.coerceAtLeast(0.1))}× so viel wie an einem normalen Tag.")
        }
    }
    cards += StoryCard("streak", "🔥", "Serie & Funkstille", "${s.longestStreak} Tage am Stück") {
        Body("Längste Serie ab ${s.streakStart?.format(df) ?: "–"}: jeden Tag mindestens eine Nachricht.")
        Big(fmtDuration(s.silenceSeconds), "längste Funkstille")
        Body("Von ${s.silenceFrom?.toLocalDate()?.format(df)} bis ${s.silenceTo?.toLocalDate()?.format(df)}.")
    }
    cards += StoryCard("hours", "🕰️", "Nach Uhrzeit", "Am aktivsten um ${s.peakHour} Uhr") {
        Bars(s.byHour.toList(), (0 until 24).map { if (it % 6 == 0) "$it" else "" }, Modifier.fillMaxWidth().height(170.dp), highlight = s.peakHour)
        val owl = s.people.values.maxBy { it.nightOwlPct }
        Body("Nachtschwärmer-Score (0–5 Uhr): ${short(owl.name)} mit ${fmtPct(owl.nightOwlPct, 1)} der Nachrichten. 🌙")
    }
    cards += StoryCard("week", "📅", "Wochentage & Monate", "${weekdayName(s.peakWeekday)} ist euer Tag") {
        Bars(s.byWeekday.toList(), listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"), Modifier.fillMaxWidth().height(120.dp), highlight = s.peakWeekday)
        val months = s.byMonth.entries.toList().takeLast(12)
        val mx = months.indices.maxByOrNull { months[it].value } ?: -1
        Bars(months.map { it.value }, months.map { it.key.month.getDisplayName(java.time.format.TextStyle.NARROW, DE) }, Modifier.fillMaxWidth().height(120.dp), highlight = mx, color = Pastel.series[2])
        if (mx >= 0) Body("Stärkster Monat: ${months[mx].key.month.getDisplayName(java.time.format.TextStyle.FULL, DE)} ${months[mx].key.year}")
    }
    val hmMonths = (java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(s.first), java.time.YearMonth.from(s.last)) + 1).toInt().coerceIn(1, 12)
    cards += StoryCard("heatmap", "🗓️", "Kalender-Heatmap", "${s.activeDays} von ${s.spanDays} Tagen") {
        MonthHeatmap(s.byDay, s.last.toLocalDate(), hmMonths, Modifier.fillMaxWidth())
        Body("Jedes Kästchen ist ein Tag (Mo–So), letzte $hmMonths Monate. Je dunkler, desto mehr Nachrichten.")
    }
    cards += StoryCard("words", "🔤", "Top-Wörter", "„${s.topWords.firstOrNull()?.first ?: "–"}“") {
        Chips(s.topWords.take(12).map { "${it.first} ${it.second}" })
        if (s.sharedWords.isNotEmpty()) { Text("Gemeinsame Lieblingswörter", fontWeight = FontWeight.Bold, color = Pastel.ink); Chips(s.sharedWords) }
    }
    if (s.topEmojis.isNotEmpty()) cards += StoryCard("emoji", s.topEmojis.first().first, "Top-Emojis", "${fmtInt(s.emojiTotal)} Emojis") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { s.topEmojis.take(5).forEach { (e, n) -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(e, fontSize = 38.sp); Text(fmtInt(n), color = Pastel.inkSoft, fontSize = 13.sp) } } }
        top.forEach { p -> Body("${short(p.name)}: ${p.topEmojis.joinToString(" ") { it.first }.ifEmpty { "–" }}") }
    }
    val mediaAll = MediaType.values().filter { it != MediaType.NONE }.sumOf { s.mediaTotal(it) }
    cards += StoryCard("media", "📸", "Medien, Links & Längen", "${fmtInt(mediaAll)} Medien") {
        StatRow(listOf(fmtInt(s.mediaTotal(MediaType.PHOTO)) to "Fotos", fmtInt(s.mediaTotal(MediaType.VIDEO)) to "Videos", fmtInt(s.mediaTotal(MediaType.VOICE)) to "Sprach-\nnachrichten"))
        StatRow(listOf(fmtInt(s.mediaTotal(MediaType.STICKER)) to "Sticker", fmtInt(s.links) to "Links", fmtInt(s.mediaTotal(MediaType.OTHER)) to "Medien\n(ohne Typ)"))
        HBars(top.map { short(it.name) to it.avgWords }, { "%.1f W.".format(DE, it) })
        Body("Ø Wörter pro Nachricht. Längste Nachricht: ${s.longestMsg?.text?.length ?: 0} Zeichen von ${s.longestMsg?.sender?.let(::short)}.")
    }
    val laughTop = s.people.values.maxBy { it.laughIndex }
    cards += StoryCard("vibes", "😂", "Lachen, Fragen, Doppelt", "${short(laughTop.name)} lacht am meisten") {
        HBars(top.map { short(it.name) to it.laughIndex }, { fmtPct(it) }); Body("Lach-Index: Anteil Nachrichten mit haha, lol, xD, 😂 …")
        StatRow(top.take(3).map { fmtPct(it.questionRate) to "Fragen\n${short(it.name)}" })
        StatRow(top.take(3).map { fmtPct(it.doubleRate) to "Doppelnachr.\n${short(it.name)}" })
    }
    val love = s.people.values.sumOf { it.love }; val ins = s.people.values.sumOf { it.insults }
    cards += StoryCard("love", "💞", "Liebe vs. Beleidigung", "$love × Liebe, $ins × Beleidigung") {
        HBars(top.map { short(it.name) + " ❤" to it.love.toDouble() } + top.map { short(it.name) + " 😤" to it.insults.toDouble() }, { fmtInt(it) })
    }
    val ghost = s.people.values.maxBy { it.ghostingIndex }
    cards += StoryCard("ghost", "👻", "Ghosting-Index", if (ghost.ghostingIndex < 3) "Kaum Ghosting!" else "${short(ghost.name)} lässt am längsten warten") {
        HBars(top.map { short(it.name) to it.ghostingIndex }, { fmtPct(it, 1) })
        Body("Anteil der Antworten, die erst nach über 12 Stunden kamen.")
    }
    val vocab = s.people.values.filter { it.words > 0 }.maxByOrNull { it.vocabDiversity }
    if (vocab != null) cards += StoryCard("vocab", "📚", "Wortschatz-Vielfalt", "${short(vocab.name)} hat den größten Wortschatz") {
        HBars(top.map { short(it.name) to it.vocabDiversity * 100 }, { "%.0f".format(DE, it) })
        Body("Verschiedene Wörter pro 500 Wörter (normalisiert, damit Vielschreiber nicht im Vorteil sind).")
    }
    if (s.gags.isNotEmpty()) cards += StoryCard("gags", "🔁", "Running Gags", "„${s.gags.first().phrase}“") {
        s.gags.forEach { Body("„${it.phrase}“ – an ${it.days} Tagen, ${it.count}×") }
    }
    val lm = s.lastMsg
    cards += StoryCard("last", "🎤", "Das letzte Wort", "${short(lm.sender)} hat das letzte Wort") {
        Text("„${if (lm.media != MediaType.NONE) "📎 Medien" else if (lm.deleted) "🗑 gelöscht" else lm.text.take(140)}“", fontSize = 20.sp, color = Pastel.ink, fontWeight = FontWeight.SemiBold)
        HBars(top.map { short(it.name) to it.lastWords.toDouble() }, { fmtInt(it) })
        Body("Wie oft jemand ein Gespräch beendet hat.")
    }
    if (s.isGroup) cards += StoryCard("leader", "🥇", "Gruppen-Rangliste", "${short(r[0].name)} führt die Gruppe an") {
        r.take(8).forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .55f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", Modifier.width(30.dp), fontWeight = FontWeight.Black, color = Pastel.accent)
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.Bold, color = Pastel.ink, maxLines = 1)
                    Text("${p.starts} Starts · ${fmtPct(p.laughIndex)} Lacher · ${p.mediaTotal} Medien", fontSize = 12.sp, color = Pastel.inkSoft)
                }
                Text(fmtInt(p.messages), fontWeight = FontWeight.Black, color = Pastel.ink)
            }
        }
    }
    cards += StoryCard("facts", "✨", "Fun Facts", "Wusstest du …?") { QuickVerdict.funFacts(s).forEach { Body("• $it") } }
    cards += StoryCard("quick", "🧠", "Schnell-Fazit", "Das sagen die Zahlen") { QuickVerdict.build(s).forEach { Body("• $it") } }
    return cards
}

@Composable
fun CenterText(t: String) = Text(t, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
