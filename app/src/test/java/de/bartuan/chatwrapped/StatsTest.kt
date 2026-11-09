package de.bartuan.chatwrapped

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StatsTest {
    private val t0 = LocalDateTime.of(2026, 1, 1, 10, 0)
    private fun m(min: Long, who: String, text: String = "hi") = Msg(t0.plusMinutes(min), who, text)

    @Test fun streakAndSilence() {
        val msgs = listOf(m(0, "A"), m(60 * 24, "B"), m(60 * 48, "A"), /* gap of 5 days */ m(60 * 24 * 7, "B"), m(60 * 24 * 8, "A"))
        val s = ChatStats(msgs)
        assertEquals(3, s.longestStreak); assertEquals(LocalDate.of(2026, 1, 1), s.streakStart)
        assertEquals(5L * 86400, s.silenceSeconds)
    }

    @Test fun startersAndLastWord() {
        val msgs = listOf(m(0, "A"), m(1, "B"), m(2, "A"), m(60 * 7, "B"), m(60 * 7 + 3, "A"), m(60 * 20, "B"))
        val s = ChatStats(msgs)
        assertEquals(3, s.conversations)
        assertEquals(1, s.people["A"]!!.starts); assertEquals(2, s.people["B"]!!.starts)
        assertEquals(2, s.people["A"]!!.lastWords); assertEquals(1, s.people["B"]!!.lastWords)
    }

    @Test fun replyTimesAndGhosting() {
        val msgs = listOf(m(0, "A"), m(2, "B"), m(3, "A"), m(13, "B"), m(14, "A"), m(14 + 13 * 60, "B"))
        val s = ChatStats(msgs)
        val b = s.people["B"]!!
        assertEquals(listOf(120L, 600L), b.replySeconds)
        assertEquals(360L, b.medianReplySec)
        assertEquals(1, b.ghosted); assertEquals(3, b.turnsReceived)
        assertEquals(100.0 / 3, b.ghostingIndex, 0.01)
        assertEquals(60L, s.people["A"]!!.medianReplySec)
        assertEquals(1, s.replyHist[6])
    }

    @Test fun doubleTextsQuestionsLaughsLove() {
        val msgs = listOf(m(0, "A", "Hast du Zeit?"), m(15, "A", "hallo??"), m(16, "B", "hahaha ja 😂"), m(17, "B", "hab dich lieb ❤️"), m(18, "A", "du Idiot xD"))
        val s = ChatStats(msgs)
        val a = s.people["A"]!!; val b = s.people["B"]!!
        assertEquals(1, a.doubleTexts); assertEquals(0, b.doubleTexts) // B's 2nd message came after 1 min
        assertEquals(2, a.questions); assertEquals(1, b.laughs); assertEquals(1, a.laughs)
        assertEquals(2, b.love); assertEquals(1, a.insults)
        assertEquals(0, Words.loveCount("das ist beliebig und das Bild"))
    }

    @Test fun runningGagsAndShared() {
        val msgs = (0 until 10).flatMap { d -> listOf(m(d * 1440L, "A", "Der Hund vom Nachbarn bellt"), m(d * 1440L + 1, "B", "kaffee trinken später?")) }
        val s = ChatStats(msgs)
        val gags = s.gags.map { it.phrase }
        assertTrue(gags.toString(), gags.any { it.contains("hund vom nachbarn") || it.contains("nachbarn bellt") })
        assertTrue(gags.toString(), gags.any { it.contains("kaffee trinken") })
        assertTrue(s.gags.all { it.days >= 3 })
    }

    @Test fun vocabularyNormalized() {
        val rich = (0 until 600).map { m(it.toLong(), "A", "wort$it ${('a' + it % 26)}x${it}y") }
        val poor = (0 until 600).map { m(it.toLong(), "B", "ja ja ja okay ja") }
        val s = ChatStats((rich + poor).sortedBy { it.time })
        assertTrue(s.people["A"]!!.vocabDiversity > s.people["B"]!!.vocabDiversity)
        assertTrue(s.people["A"]!!.vocabDiversity <= 1.0)
    }

    @Test fun sampleStats() {
        val s = ChatStats(Parser.parse(javaClass.classLoader!!.getResource("sample_chat.txt")!!.readText()))
        assertFalse(s.isGroup); assertTrue(s.longestStreak > 5); assertNotNull(s.recordDay)
        assertTrue(QuickVerdict.build(s).isNotEmpty()); assertTrue(s.gags.isNotEmpty())
    }
}
