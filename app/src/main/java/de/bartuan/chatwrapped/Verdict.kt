package de.bartuan.chatwrapped

import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val DE: Locale = Locale.GERMANY
val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d. MMMM yyyy", DE)
fun weekdayName(i: Int) = java.time.DayOfWeek.of(i + 1).getDisplayName(TextStyle.FULL, DE)

object Anonymizer {
    private val emailRx = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val urlRx = Regex("""(?i)\b(https?://\S+|www\.\S+)""")
    private val phoneRx = Regex("""(?<![\w])\+?\d[\d \-/()]{6,}\d""")

    fun aliases(names: List<String>): Map<String, String> =
        names.withIndex().associate { (i, n) -> n to "Person ${alias(i)}" }

    private fun alias(i: Int): String = if (i < 26) ('A' + i).toString() else "A${i - 25}"

    fun mask(text: String, aliases: Map<String, String>): String {
        var t = text
        t = t.replace(emailRx, "[E-Mail]").replace(urlRx, "[Link]").replace(phoneRx, "[Nummer]")
        // replace full names and their first names (longest first), case-insensitive, as whole words
        val parts = ArrayList<Pair<String, String>>()
        for ((n, a) in aliases) {
            parts.add(n to a)
            n.split(' ').filter { it.length >= 3 && it.first().isLetter() }.forEach { parts.add(it to a) }
        }
        for ((n, a) in parts.sortedByDescending { it.first.length }) {
            t = t.replace(Regex("(?i)(?<![\\p{L}])" + Regex.escape(n) + "(?![\\p{L}])"), a)
        }
        return t
    }
}

object PromptBuilder {
    val headings = listOf("Beziehungsdynamik", "Wer investiert mehr", "Stil je Person", "Stimmungsverlauf",
        "Red Flags", "Green Flags", "Kompatibilitäts-Score", "3 Tipps", "Gesamtfazit")

    fun build(s: ChatStats, includeSamples: Boolean, sampleCount: Int = 150): String {
        val names = s.ranked.map { it.name }
        val al = Anonymizer.aliases(names)
        val sb = StringBuilder()
        sb.appendLine("Du bist ein einfühlsamer, ehrlicher Beziehungs- und Kommunikationsanalyst. Analysiere den folgenden WhatsApp-Chat anhand der Statistiken" +
            (if (includeSamples) " und der anonymisierten Beispielnachrichten" else "") + ". Antworte auf Deutsch, locker aber respektvoll, ohne Diagnosen.")
        sb.appendLine("Die Namen sind anonymisiert (Person A, B, …). Nutze GENAU diese Markdown-Überschriften in dieser Reihenfolge:")
        headings.forEach { sb.appendLine("## $it") }
        sb.appendLine("Unter „## Kompatibilitäts-Score“ gib eine Zahl als „X/100“ an und begründe sie kurz. Unter „## 3 Tipps“ genau drei nummerierte Tipps.")
        sb.appendLine()
        sb.appendLine("# Statistiken")
        sb.appendLine("- Art: ${if (s.isGroup) "Gruppenchat mit ${s.people.size} Personen" else "Einzelchat"}")
        sb.appendLine("- Zeitraum: ${s.first.toLocalDate().format(dateFmt)} bis ${s.last.toLocalDate().format(dateFmt)} (${s.spanDays} Tage, an ${s.activeDays} Tagen geschrieben)")
        sb.appendLine("- Nachrichten gesamt: ${s.total}, Wörter: ${s.words}, Ø ${"%.1f".format(DE, s.perDayAvg)} Nachrichten pro Tag")
        sb.appendLine("- Gespräche (neu nach 6 h Pause): ${s.conversations}")
        sb.appendLine("- Rekordtag: ${s.recordDay?.let { "${it.key.format(dateFmt)} mit ${it.value} Nachrichten" }}")
        sb.appendLine("- Längste Serie: ${s.longestStreak} Tage am Stück; längste Funkstille: ${fmtDuration(s.silenceSeconds)}")
        sb.appendLine("- Aktivste Uhrzeit: ${s.peakHour} Uhr; aktivster Wochentag: ${weekdayName(s.peakWeekday)}")
        sb.appendLine("- Antwortzeit-Verteilung: " + ChatStats.histLabels.zip(s.replyHist.toList()).joinToString(", ") { "${it.first}: ${it.second}" })
        sb.appendLine("- Nachrichten pro Monat: " + s.byMonth.entries.joinToString(", ") { "${it.key}: ${it.value}" })
        sb.appendLine("- Häufigste Wörter: " + s.topWords.take(12).joinToString(", ") { "${Anonymizer.mask(it.first, al)} (${it.second})" })
        sb.appendLine("- Häufigste Emojis: " + s.topEmojis.take(8).joinToString(" ") { "${it.first}×${it.second}" })
        if (s.gags.isNotEmpty()) sb.appendLine("- Running Gags: " + s.gags.joinToString(", ") { "„${Anonymizer.mask(it.phrase, al)}“ an ${it.days} Tagen" })
        sb.appendLine()
        sb.appendLine("# Pro Person")
        for (p in s.ranked) {
            val a = al[p.name]!!
            val trend = s.initiativeShare(p.name).joinToString(", ") { "${it.first}: ${(it.second * 100).toInt()} %" }
            sb.appendLine("## $a")
            sb.appendLine("- Nachrichten: ${p.messages} (${fmtPct(p.messages * 100.0 / s.total)}), Ø ${"%.1f".format(DE, p.avgWords)} Wörter/Nachricht")
            sb.appendLine("- Beginnt Gespräche: ${p.starts}×, hat das letzte Wort: ${p.lastWords}×")
            sb.appendLine("- Median-Antwortzeit: ${fmtDuration(p.medianReplySec)}, Ghosting-Index (Antwort erst nach >12 h): ${fmtPct(p.ghostingIndex, 1)}")
            sb.appendLine("- Doppelnachrichten-Rate: ${fmtPct(p.doubleRate, 1)}, Fragen-Quote: ${fmtPct(p.questionRate, 1)}, Lach-Index: ${fmtPct(p.laughIndex, 1)}")
            sb.appendLine("- Liebe-Wörter/Emojis: ${p.love}, Beleidigungen: ${p.insults}, Nachtschwärmer (0–5 Uhr): ${fmtPct(p.nightOwlPct, 1)}")
            sb.appendLine("- Medien: Fotos ${p.mediaCount(MediaType.PHOTO)}, Videos ${p.mediaCount(MediaType.VIDEO)}, Sprachnachrichten ${p.mediaCount(MediaType.VOICE)}, Sticker ${p.mediaCount(MediaType.STICKER)}, Links ${p.links}, gelöscht ${p.deleted}")
            sb.appendLine("- Wortschatz-Vielfalt (MSTTR): ${"%.2f".format(DE, p.vocabDiversity)}; Lieblingswörter: ${p.topWords.take(6).joinToString(", ") { Anonymizer.mask(it.first, al) }}; Emojis: ${p.topEmojis.joinToString(" ") { it.first }}")
            if (trend.isNotEmpty()) sb.appendLine("- Initiative pro Monat (Anteil Gesprächsstarts): $trend")
        }
        if (includeSamples) {
            sb.appendLine()
            sb.appendLine("# Beispielnachrichten (anonymisiert, Telefonnummern/E-Mails/Links maskiert)")
            sample(s.msgs, sampleCount).forEach { m ->
                val txt = Anonymizer.mask(m.text.replace('\n', ' '), al).take(200)
                sb.appendLine("[${m.time.toLocalDate()} ${"%02d:%02d".format(m.time.hour, m.time.minute)}] ${al[m.sender]}: $txt")
            }
        }
        return sb.toString().trimEnd()
    }

    /** Evenly spread, text-only messages, in short consecutive bursts so replies keep context. */
    fun sample(msgs: List<Msg>, n: Int): List<Msg> {
        val text = msgs.filter { !it.deleted && it.media == MediaType.NONE && it.text.isNotBlank() }
        if (text.size <= n) return text
        val chunk = 5
        val groups = n / chunk
        val step = (text.size - chunk).toDouble() / (groups - 1).coerceAtLeast(1)
        val out = ArrayList<Msg>()
        for (g in 0 until groups) { val st = (g * step).toInt(); out.addAll(text.subList(st, minOf(st + chunk, text.size))) }
        return out.distinct().take(n)
    }
}

data class VerdictSection(val title: String, val body: String)

object VerdictParser {
    private fun norm(s: String) = s.lowercase(DE).replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
        .replace(Regex("[^a-z0-9]"), "")
    private val keys = PromptBuilder.headings.map { it to norm(it) }
    private val aliases = mapOf("kompatibilitat" to "Kompatibilitäts-Score", "kompatibilitatsscore" to "Kompatibilitäts-Score",
        "compatibility" to "Kompatibilitäts-Score", "tipps" to "3 Tipps", "dreitipps" to "3 Tipps", "fazit" to "Gesamtfazit",
        "redflag" to "Red Flags", "greenflag" to "Green Flags", "werinvestiertmehr" to "Wer investiert mehr")

    /** Detects a heading line; returns canonical title and the rest of the line (for '**Fazit:** text'). */
    private fun heading(line: String): Pair<String, String>? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val hashes = t.takeWhile { it == '#' }.length
        var core = t.drop(hashes).trim()
        val bold = core.startsWith("**") || core.startsWith("__")
        if (hashes == 0 && !bold) {
            // a bare heading line (no markdown) only if it equals a heading exactly (optionally with ':')
            val n = norm(core.removeSuffix(":"))
            return keys.firstOrNull { it.second == n }?.let { it.first to "" }
        }
        var rest = ""
        if (bold) {
            val close = core.indexOf(core.substring(0, 2), 2)
            if (close > 0) { rest = core.substring(close + 2); core = core.substring(2, close) } else core = core.drop(2)
        }
        core = core.replace(Regex("^\\s*\\d+[.)]\\s*(?=\\D)"), "").replace(Regex("[*_:]+$"), "").trim()
        val n = norm(core)
        val match = keys.firstOrNull { it.second == n }?.first
            ?: keys.firstOrNull { n.startsWith(it.second) && it.second.length >= 6 }?.first
            ?: aliases.entries.firstOrNull { n.startsWith(it.key) }?.value
            ?: return null
        return match to rest.trimStart(':', ' ', '*').trim()
    }

    fun parse(text: String): List<VerdictSection> {
        val found = LinkedHashMap<String, StringBuilder>()
        var cur: StringBuilder? = null
        for (line in text.lines()) {
            val h = heading(line)
            if (h != null) {
                cur = found.getOrPut(h.first) { StringBuilder() }
                if (h.second.isNotEmpty()) cur.appendLine(h.second)
            } else cur?.appendLine(line)
        }
        return PromptBuilder.headings.mapNotNull { k -> found[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { VerdictSection(k, it) } }
    }

    fun score(sections: List<VerdictSection>): Int? {
        val b = sections.firstOrNull { it.title == "Kompatibilitäts-Score" }?.body ?: return null
        val m = Regex("""(\d{1,3})\s*(?:/\s*100|%|von 100| von 100)""").find(b) ?: Regex("""\b(\d{1,3})\b""").find(b) ?: return null
        return m.groupValues[1].toInt().takeIf { it in 0..100 }
    }
}

/** Offline rule-based mini verdict. */
object QuickVerdict {
    fun build(s: ChatStats): List<String> {
        val out = ArrayList<String>()
        val r = s.ranked
        if (s.isGroup) {
            out.add("${r[0].name} ist mit ${fmtPct(r[0].messages * 100.0 / s.total)} der Nachrichten die Stimme der Gruppe.")
            val quiet = r.last()
            out.add("${quiet.name} ist am stillsten (${fmtInt(quiet.messages)} Nachrichten) – vielleicht mal direkt einbeziehen?")
            val starter = s.people.values.maxBy { it.starts }
            out.add("${starter.name} bringt den Chat am häufigsten ins Rollen (${starter.starts} Gespräche gestartet).")
        } else if (r.size == 2) {
            val (a, b) = r
            val share = a.messages * 100.0 / s.total
            out.add(if (share < 56) "Ausgeglichen: Ihr schreibt fast gleich viel (${fmtPct(share)} zu ${fmtPct(100 - share)})."
                else "${a.name} schreibt deutlich mehr (${fmtPct(share)}). ${b.name} hält sich eher kurz.")
            val sa = a.starts; val sb = b.starts
            val init = if (sa + sb > 0) sa * 100.0 / (sa + sb) else 50.0
            out.add(when {
                init in 40.0..60.0 -> "Beide melden sich von sich aus – gesunde Initiative auf beiden Seiten."
                init > 60 -> "${a.name} startet ${fmtPct(init)} der Gespräche. ${b.name} könnte öfter den ersten Schritt machen."
                else -> "${b.name} startet ${fmtPct(100 - init)} der Gespräche. ${a.name} könnte öfter den ersten Schritt machen."
            })
            val fa = a.medianReplySec; val fb = b.medianReplySec
            if (fa >= 0 && fb >= 0) {
                val fast = if (fa <= fb) a else b; val slow = if (fa <= fb) b else a
                out.add(if (maxOf(fa, fb) < 600) "Ihr antwortet beide flott (Median unter 10 Min.) – hier ist Interesse da."
                    else "${fast.name} antwortet schneller (${fmtDuration(fast.medianReplySec)}) als ${slow.name} (${fmtDuration(slow.medianReplySec)}).")
            }
            val ghost = r.maxBy { it.ghostingIndex }
            if (ghost.ghostingIndex >= 15) out.add("Ghosting-Alarm: ${ghost.name} lässt ${fmtPct(ghost.ghostingIndex)} der Nachrichten über 12 Std. liegen.")
        }
        val love = s.people.values.sumOf { it.love }; val ins = s.people.values.sumOf { it.insults }
        if (love > 0 || ins > 0) out.add(if (love >= ins * 3) "Viel Liebe im Chat: $love liebevolle Wörter/Emojis gegen $ins Beleidigungen. 💕"
            else "Rauer Ton: $ins Beleidigungen gegen $love liebe Worte – hoffentlich alles Spaß.")
        val laughs = s.people.values.sumOf { it.laughs } * 100.0 / s.total
        if (laughs >= 8) out.add("Ihr lacht viel: ${fmtPct(laughs)} der Nachrichten sind Lacher. 😂")
        if (s.longestStreak >= 30) out.add("${s.longestStreak} Tage am Stück geschrieben – das ist echte Konstanz.")
        if (s.silenceSeconds > 30L * 86400) out.add("Es gab aber auch eine Funkstille von ${fmtDuration(s.silenceSeconds)}.")
        val night = s.people.values.maxBy { it.nightOwlPct }
        if (night.nightOwlPct >= 5) out.add("${night.name} ist der Nachtschwärmer (${fmtPct(night.nightOwlPct)} der Nachrichten zwischen 0 und 5 Uhr). 🌙")
        return out
    }

    fun funFacts(s: ChatStats): List<String> {
        val f = ArrayList<String>()
        val books = s.words / 90_000.0
        f.add("Ihr habt ${fmtInt(s.words)} Wörter geschrieben – " + if (books >= 0.1) "das sind etwa ${"%.1f".format(DE, books)} Romane." else "ein ordentlicher Kurzroman.")
        val minutes = s.words / 40
        f.add("Alles vorzulesen würde rund ${fmtDuration(minutes * 60L)} dauern.")
        s.longestMsg?.let { f.add("Die längste Nachricht (${it.text.length} Zeichen) kam von ${it.sender}.") }
        f.add("Im Schnitt ${"%.1f".format(DE, s.perDayAvg)} Nachrichten pro Tag, an ${s.activeDays} von ${s.spanDays} Tagen wurde geschrieben.")
        if (s.emojiTotal > 0) f.add("${fmtInt(s.emojiTotal)} Emojis insgesamt, Lieblings-Emoji: ${s.topEmojis.firstOrNull()?.first ?: "–"}")
        val del = s.people.values.sumOf { it.deleted }
        if (del > 0) f.add("$del Nachrichten wurden wieder gelöscht. 👀")
        val q = s.people.values.maxBy { it.questionRate }
        f.add("${q.name} stellt die meisten Fragen (${fmtPct(q.questionRate)} der Nachrichten).")
        return f
    }
}
