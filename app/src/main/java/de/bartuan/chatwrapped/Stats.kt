package de.bartuan.chatwrapped

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

class PersonStats(val name: String) {
    var messages = 0; var textMessages = 0; var words = 0; var chars = 0L
    val media = IntArray(MediaType.values().size)
    var deleted = 0; var links = 0; var starts = 0; var lastWords = 0
    var questions = 0; var laughs = 0; var love = 0; var insults = 0; var night = 0
    var turnsReceived = 0 // times this person answered the other side
    var ghosted = 0 // ...of which only after >12h
    var turns = 0; var doubleTexts = 0
    var replySeconds = ArrayList<Long>()
    var longestText = ""
    var emojiCount = 0
    var topWords: List<Pair<String, Int>> = emptyList()
    var topEmojis: List<Pair<String, Int>> = emptyList()
    var vocabDiversity = 0.0 // normalized type/token (mean over 500-token windows), 0..1
    internal val wordCounts = HashMap<String, Int>()
    internal val tokenStream = ArrayList<String>()

    fun mediaCount(t: MediaType) = media[t.ordinal]
    val mediaTotal get() = media.sum() - media[MediaType.NONE.ordinal]
    val avgWords get() = if (textMessages > 0) words.toDouble() / textMessages else 0.0
    val medianReplySec: Long get() = median(replySeconds)
    val ghostingIndex get() = if (turnsReceived > 0) ghosted * 100.0 / turnsReceived else 0.0
    val doubleRate get() = if (turns > 0) doubleTexts * 100.0 / turns else 0.0
    val questionRate get() = if (messages > 0) questions * 100.0 / messages else 0.0
    val laughIndex get() = if (messages > 0) laughs * 100.0 / messages else 0.0
    val nightOwlPct get() = if (messages > 0) night * 100.0 / messages else 0.0
}

fun median(l: List<Long>): Long {
    if (l.isEmpty()) return -1
    val s = l.sorted(); val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
}

data class Gag(val phrase: String, val count: Int, val days: Int)

class ChatStats(val msgs: List<Msg>, val mediaFilesInExport: Map<MediaType, Int> = emptyMap()) {
    companion object {
        const val CONV_GAP = 6 * 3600L      // new conversation after 6h quiet
        const val GHOST_GAP = 12 * 3600L    // >12h unanswered = ghosting
        const val DOUBLE_GAP = 10 * 60L     // same sender again after >=10 min without reply
        val histLabels = listOf("<1 Min", "1–5 Min", "5–15 Min", "15–60 Min", "1–3 Std", "3–12 Std", ">12 Std")
        fun histBucket(sec: Long) = when { sec < 60 -> 0; sec < 300 -> 1; sec < 900 -> 2; sec < 3600 -> 3; sec < 10800 -> 4; sec < GHOST_GAP -> 5; else -> 6 }
    }

    init { require(msgs.isNotEmpty()) { "Keine Nachrichten" } }

    val people = LinkedHashMap<String, PersonStats>()
    val total = msgs.size
    val first: LocalDateTime = msgs.first().time
    val last: LocalDateTime = msgs.last().time
    val byDay = java.util.TreeMap<LocalDate, Int>()
    val byHour = IntArray(24)
    val byWeekday = IntArray(7)
    val byMonth = java.util.TreeMap<YearMonth, Int>()
    val initiativeByMonth = java.util.TreeMap<YearMonth, MutableMap<String, Int>>()
    val replyHist = IntArray(7)
    var conversations = 0; private set
    var longestStreak = 0; private set
    var streakStart: LocalDate? = null; private set
    var silenceSeconds = 0L; private set
    var silenceFrom: LocalDateTime? = null; private set
    var silenceTo: LocalDateTime? = null; private set
    var topWords: List<Pair<String, Int>> = emptyList(); private set
    var topEmojis: List<Pair<String, Int>> = emptyList(); private set
    var sharedWords: List<String> = emptyList(); private set
    var gags: List<Gag> = emptyList(); private set
    var longestMsg: Msg? = null; private set

    val isGroup get() = people.size > 2
    val ranked get() = people.values.sortedByDescending { it.messages }
    val activeDays get() = byDay.size
    val spanDays get() = ChronoUnit.DAYS.between(first.toLocalDate(), last.toLocalDate()) + 1
    val recordDay get() = byDay.entries.maxByOrNull { it.value }
    val peakHour get() = byHour.indices.maxBy { byHour[it] }
    val peakWeekday get() = byWeekday.indices.maxBy { byWeekday[it] }
    val lastMsg get() = msgs.last()
    val perDayAvg get() = total.toDouble() / spanDays
    fun mediaTotal(t: MediaType) = people.values.sumOf { it.mediaCount(t) }
    val links get() = people.values.sumOf { it.links }
    val words get() = people.values.sumOf { it.words }
    val emojiTotal get() = people.values.sumOf { it.emojiCount }

    init { compute() }

    private fun compute() {
        val emojiAll = HashMap<String, Int>()
        val emojiPer = HashMap<String, HashMap<String, Int>>()
        val ngramDays = HashMap<String, HashSet<LocalDate>>()
        val ngramCount = HashMap<String, Int>()
        var prev: Msg? = null
        var awaitingFrom: Msg? = null // last message of the other side's run
        for (m in msgs) {
            val p = people.getOrPut(m.sender) { PersonStats(m.sender) }
            p.messages++
            val d = m.time.toLocalDate()
            byDay.merge(d, 1, Int::plus)
            byHour[m.time.hour]++
            byWeekday[m.time.dayOfWeek.value - 1]++
            val ym = YearMonth.from(m.time)
            byMonth.merge(ym, 1, Int::plus)
            if (m.time.hour < 5) p.night++
            if (m.deleted) p.deleted++
            if (m.media != MediaType.NONE) p.media[m.media.ordinal]++

            val gap = prev?.let { Duration.between(it.time, m.time).seconds.coerceAtLeast(0) }
            if (gap != null && gap > silenceSeconds) { silenceSeconds = gap; silenceFrom = prev!!.time; silenceTo = m.time }
            if (prev == null || gap!! > CONV_GAP) {
                conversations++; p.starts++
                initiativeByMonth.getOrPut(ym) { HashMap() }.merge(m.sender, 1, Int::plus)
                prev?.let { people[it.sender]!!.lastWords++ }
            }
            if (prev != null) {
                if (prev.sender != m.sender) {
                    p.turns++
                    p.turnsReceived++
                    if (gap!! > GHOST_GAP) p.ghosted++
                    else p.replySeconds.add(gap)
                    replyHist[histBucket(gap)]++
                } else if (gap!! >= DOUBLE_GAP) p.doubleTexts++
            } else p.turns++

            if (!m.deleted && m.media == MediaType.NONE) {
                val text = m.text
                p.textMessages++
                p.chars += text.length
                if (text.length > (longestMsg?.text?.length ?: 0)) longestMsg = m
                if (text.length > p.longestText.length) p.longestText = text
                p.links += Words.linkRx.findAll(text).count()
                val noLinks = text.replace(Words.linkRx, " ")
                if (noLinks.contains('?')) p.questions++
                if (Words.laughRx.containsMatchIn(noLinks)) p.laughs++
                p.love += Words.loveCount(noLinks)
                val toks = Words.tokens(noLinks)
                p.insults += Words.insultCount(toks)
                p.words += toks.size
                p.tokenStream.addAll(toks)
                for (t in toks) if (t !in Words.stop && t.length > 2 && !t.all { it.isDigit() }) p.wordCounts.merge(t, 1, Int::plus)
                val em = Words.emojis(text)
                p.emojiCount += em.size
                for (e in em) { emojiAll.merge(e, 1, Int::plus); emojiPer.getOrPut(m.sender) { HashMap() }.merge(e, 1, Int::plus) }
                // n-grams for running gags
                val seenHere = HashSet<String>()
                for (n in 2..3) for (i in 0..toks.size - n) {
                    val g = toks.subList(i, i + n)
                    if (g.first() in Words.stop || g.last() in Words.stop) continue
                    if (g.all { it in Words.stop || it.length < 3 }) continue
                    val key = g.joinToString(" ")
                    if (!seenHere.add(key)) continue
                    ngramCount.merge(key, 1, Int::plus)
                    ngramDays.getOrPut(key) { HashSet() }.add(d)
                }
            }
            prev = m
        }
        prev?.let { people[it.sender]!!.lastWords++ }

        // streaks
        var run = 0; var runStart: LocalDate? = null; var last: LocalDate? = null
        for (day in byDay.keys) {
            if (last != null && ChronoUnit.DAYS.between(last, day) == 1L) run++ else { run = 1; runStart = day }
            if (run > longestStreak) { longestStreak = run; streakStart = runStart }
            last = day
        }

        // words & emojis
        val allWords = HashMap<String, Int>()
        for (p in people.values) {
            p.wordCounts.forEach { (k, v) -> allWords.merge(k, v, Int::plus) }
            p.topWords = p.wordCounts.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }
            p.topEmojis = (emojiPer[p.name] ?: emptyMap<String, Int>()).entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
            p.vocabDiversity = msttr(p.tokenStream)
            p.tokenStream.clear(); p.tokenStream.trimToSize()
        }
        topWords = allWords.entries.sortedByDescending { it.value }.take(15).map { it.key to it.value }
        topEmojis = emojiAll.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }

        // shared favourites: words in every main person's top 60, ranked by the smaller count
        val main = ranked.take(if (isGroup) 3 else 2).filter { it.wordCounts.isNotEmpty() }
        if (main.size >= 2) {
            val tops = main.map { pp -> pp.wordCounts.entries.sortedByDescending { it.value }.take(60).map { it.key }.toSet() }
            val inter = tops.reduce { a, b -> a intersect b }
            sharedWords = inter.sortedByDescending { w -> main.minOf { it.wordCounts[w] ?: 0 } }.take(8)
        }

        // running gags: recurring phrases on many different days
        val minDays = maxOf(3, (activeDays * 0.02).roundToInt())
        val cands = ngramDays.entries.filter { it.value.size >= minDays }
            .map { Gag(it.key, ngramCount[it.key] ?: 0, it.value.size) }
            .sortedWith(compareByDescending<Gag> { it.days }.thenByDescending { it.phrase.count { c -> c == ' ' } })
        val picked = ArrayList<Gag>()
        for (g in cands) {
            if (picked.any { it.phrase.contains(g.phrase) || g.phrase.contains(it.phrase) }) continue
            picked.add(g); if (picked.size == 6) break
        }
        gags = picked
    }

    /** Mean segmental type/token ratio over 500-token windows: comparable across chats of different length. */
    private fun msttr(tokens: List<String>, window: Int = 500): Double {
        if (tokens.isEmpty()) return 0.0
        if (tokens.size < window) return tokens.toSet().size.toDouble() / tokens.size // short: plain TTR
        var sum = 0.0; var n = 0
        var i = 0
        while (i + window <= tokens.size) { sum += tokens.subList(i, i + window).toSet().size.toDouble() / window; n++; i += window }
        return sum / n
    }

    /** Share of conversation starts per person per month (0..1), for the initiative trend. */
    fun initiativeShare(name: String): List<Pair<YearMonth, Double>> = initiativeByMonth.map { (ym, m) ->
        ym to ((m[name] ?: 0).toDouble() / m.values.sum().coerceAtLeast(1))
    }
}

fun fmtDuration(sec: Long): String = when {
    sec < 0 -> "–"
    sec < 60 -> "$sec Sek."
    sec < 3600 -> "${sec / 60} Min."
    sec < 86400 -> { val h = sec / 3600; val m = (sec % 3600) / 60; if (m == 0L) "$h Std." else "$h Std. $m Min." }
    else -> { val d = sec / 86400; val h = (sec % 86400) / 3600; if (h == 0L) "$d Tage" else "$d Tage $h Std." }
}

fun fmtInt(n: Number): String = String.format(java.util.Locale.GERMANY, "%,d", n.toLong())
fun fmtPct(d: Double, digits: Int = 0): String = String.format(java.util.Locale.GERMANY, "%.${digits}f %%", d)
