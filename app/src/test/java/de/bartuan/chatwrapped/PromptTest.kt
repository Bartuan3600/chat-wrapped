package de.bartuan.chatwrapped

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class PromptTest {
    private val t0 = LocalDateTime.of(2026, 1, 1, 10, 0)
    private val msgs = listOf(
        Msg(t0, "Lena Berg", "Hi Jonas, ruf mich an: +49 151 2345678 oder lena.berg@mail.de"),
        Msg(t0.plusMinutes(1), "Jonas", "Klar Lena! Schau mal https://example.org/x"),
        Msg(t0.plusMinutes(2), "Lena Berg", "LENA ist müde, jonas auch"),
    )

    @Test fun anonymizedWithSamples() {
        val p = PromptBuilder.build(ChatStats(msgs), includeSamples = true)
        for (bad in listOf("Lena", "LENA", "Jonas", "jonas", "Berg", "2345678", "@mail.de", "example.org")) assertFalse("leaked $bad", p.contains(bad))
        assertTrue(p.contains("Person A")); assertTrue(p.contains("Person B"))
        assertTrue(p.contains("[Nummer]")); assertTrue(p.contains("[E-Mail]")); assertTrue(p.contains("[Link]"))
        PromptBuilder.headings.forEach { assertTrue(it, p.contains("## $it")) }
    }

    @Test fun noSamplesByDefaultFlag() {
        val p = PromptBuilder.build(ChatStats(msgs), includeSamples = false)
        assertFalse(p.contains("Beispielnachrichten (anonymisiert"))
    }

    @Test fun sampleSize() {
        val many = (0 until 2000).map { Msg(t0.plusMinutes(it.toLong()), if (it % 2 == 0) "A" else "B", "msg $it") }
        assertEquals(150, PromptBuilder.sample(many, 150).size)
    }

    @Test fun verdictParsingTolerant() {
        val txt = """
            Hier ist meine Analyse:
            # Beziehungsdynamik
            Sehr ausgeglichen.
            ### **Wer investiert mehr?**
            Person A etwas mehr.
            **Stil je Person:** A schreibt lang, B kurz.
            ## 2. Stimmungsverlauf
            Steigend.
            ## Red Flags
            - keine großen
            __Green Flags__
            - viel Humor
            ## Kompatibilitäts-Score
            **82/100** – passt gut.
            ## 3 Tipps
            1. Mehr fragen
            Gesamtfazit
            Ein schönes Duo.
        """.trimIndent()
        val s = VerdictParser.parse(txt)
        assertEquals(PromptBuilder.headings, s.map { it.title })
        assertEquals("A schreibt lang, B kurz.", s[2].body)
        assertEquals(82, VerdictParser.score(s))
        assertEquals("Ein schönes Duo.", s.last().body)
    }

    @Test fun verdictMissingSections() {
        val s = VerdictParser.parse("## Gesamtfazit\nPasst.\n## Unbekannt\nx")
        assertEquals(listOf("Gesamtfazit"), s.map { it.title })
        assertNull(VerdictParser.score(s))
        assertTrue(VerdictParser.parse("nur Text ohne Überschriften").isEmpty())
    }

}
