package de.bartuan.chatwrapped

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ParserTest {
    @Test fun android() {
        val m = Parser.parse("""
            17.10.26, 22:42 - Nachrichten und Anrufe sind Ende-zu-Ende-verschlüsselt. Tippe, um mehr zu erfahren.
            17.10.26, 22:42 - Anna: Hi
            17.10.26, 22:43 - Ben: Hallo: wie geht's?
            18.10.26, 08:01 - Anna: <Medien ausgeschlossen>
            18.10.26, 08:02 - Ben: Diese Nachricht wurde gelöscht
        """.trimIndent())
        assertEquals(4, m.size)
        assertEquals(LocalDateTime.of(2026, 10, 17, 22, 42), m[0].time)
        assertEquals("Hallo: wie geht's?", m[1].text)
        assertEquals(MediaType.OTHER, m[2].media)
        assertTrue(m[3].deleted)
    }

    @Test fun iosWithInvisibleMarks() {
        val m = Parser.parse("\u200E[17.10.26, 22:42:13] Anna: \u200ENachrichten und Anrufe sind Ende-zu-Ende-verschlüsselt.\n" +
            "[17.10.26, 22:42:20] Anna: Na?\n" +
            "\u200E[17.10.26, 22:43:01] Ben: \u200EBild weggelassen\n" +
            "[17.10.26, 22:43:05] Ben: \u200E<angehängt: 00000012-PHOTO-2026-10-17-22-43-05.jpg>\n" +
            "[17.10.26, 22:44:00] Ben: \u200ESprachnachricht weggelassen\n" +
            "[17.10.26, 22:44:30] Anna: \u200ESticker weggelassen\n" +
            "[17.10.26, 22:45:00] Anna: \u200EDiese Nachricht wurde gelöscht.\n" +
            "[17.10.26, 22:46:00] Anna: lol\u200F")
        assertEquals(listOf("Anna", "Ben", "Ben", "Ben", "Anna", "Anna", "Anna"), m.map { it.sender })
        assertEquals(listOf(MediaType.NONE, MediaType.PHOTO, MediaType.PHOTO, MediaType.VOICE, MediaType.STICKER, MediaType.NONE, MediaType.NONE), m.map { it.media })
        assertTrue(m[5].deleted); assertEquals("lol", m[6].text); assertEquals(13, m[0].time.second - 7)
    }

    @Test fun usAmPmWithNarrowNbsp() {
        val m = Parser.parse("10/17/26, 10:42\u202FPM - Ben: yo\n1/2/26, 12:05\u202FAM - Anna: late\n12/25/2026, 9:00 AM - Ben: xmas")
        assertEquals(LocalDateTime.of(2026, 10, 17, 22, 42), m[0].time)
        assertEquals(LocalDateTime.of(2026, 1, 2, 0, 5), m[1].time)
        assertEquals(LocalDateTime.of(2026, 12, 25, 9, 0), m[2].time)
    }

    @Test fun autoDetectsMonthFirstWithoutAmPm() {
        // 24h US-style export: the 13 in the second field proves month/day order for ambiguous lines too
        val m = Parser.parse("03/04/26, 10:00 - A: x\n03/13/26, 10:00 - B: y")
        assertEquals(LocalDateTime.of(2026, 3, 4, 10, 0), m[0].time)
        val d = Parser.parse("03.04.2026, 10:00 - A: x\n13.04.2026, 10:00 - B: y")
        assertEquals(LocalDateTime.of(2026, 4, 3, 10, 0), d[0].time)
    }

    @Test fun multiline() {
        val m = Parser.parse("17.10.26, 10:00 - Anna: erste Zeile\nzweite Zeile\n\ndritte Zeile\n17.10.26, 10:01 - Ben: ok")
        assertEquals(2, m.size); assertEquals("erste Zeile\nzweite Zeile\n\ndritte Zeile", m[0].text)
    }

    @Test fun groupSystemLinesFiltered() {
        val m = Parser.parse("""
            01.01.26, 10:00 - Anna hat die Gruppe „Urlaub: 2026“ erstellt
            01.01.26, 10:00 - Anna hat Ben hinzugefügt
            01.01.26, 10:01 - Anna hat den Betreff von „A“ zu „Urlaub: 2026“ geändert
            01.01.26, 10:02 - Chris ist über den Einladungslink der Gruppe beigetreten
            01.01.26, 10:03 - Anna: Willkommen!
            01.01.26, 10:04 - Ben: Danke
            01.01.26, 10:05 - Chris: Hi
            01.01.26, 10:06 - Dora hat die Gruppe verlassen
            01/02/26, 10:00 - You changed the subject from "a" to "b: c"
        """.trimIndent())
        assertEquals(listOf("Anna", "Ben", "Chris"), m.map { it.sender })
        val ios = Parser.parse("[01.01.26, 10:00:00] Urlaub: \u200EAnna hat Ben hinzugefügt.\n[01.01.26, 10:01:00] Ben: Hallo")
        assertEquals(listOf("Ben"), ios.map { it.sender })
    }

    @Test fun androidMediaAttached() {
        val m = Parser.parse("17.10.26, 10:00 - Anna: \u200EIMG-20261017-WA0001.jpg (Datei angehängt)\n17.10.26, 10:01 - Ben: \u200EPTT-20261017-WA0002.opus (Datei angehängt)\n17.10.26, 10:02 - Ben: STK-20261017-WA0003.webp (Datei angehängt)")
        assertEquals(listOf(MediaType.PHOTO, MediaType.VOICE, MediaType.STICKER), m.map { it.media })
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bo.toByteArray()
    }

    @Test fun zipImportIos() {
        val chat = "[17.10.26, 22:42:20] Anna: Na?\n[17.10.26, 22:43:01] Ben: \u200E<angehängt: 00000012-PHOTO-2026-10-17.jpg>\n"
        val z = zip("00000012-PHOTO-2026-10-17.jpg" to ByteArray(10), "00000013-AUDIO-2026-10-17.opus" to ByteArray(5), "notes.txt" to "hello".toByteArray(), "_chat.txt" to ("\uFEFF" + chat).toByteArray())
        val src = Importer.load(listOf(ImportedFile("WhatsApp Chat - Ben.zip", z)))
        assertEquals(2, Parser.parse(src.text).size)
        assertEquals(1, src.mediaFiles[MediaType.PHOTO]); assertEquals(1, src.mediaFiles[MediaType.VOICE])
    }

    @Test fun zipImportAndroidTitle() {
        val chat = "17.10.26, 10:00 - Anna: Hi\n17.10.26, 10:01 - Ben: Hey\n"
        val z = zip("WhatsApp Chat mit Ben.txt" to chat.toByteArray())
        val src = Importer.load(listOf(ImportedFile("WhatsApp Chat mit Ben.zip", z)))
        assertEquals("Ben", src.title); assertEquals(2, Parser.parse(src.text).size)
    }

    @Test fun multipleSharedFiles() {
        val src = Importer.load(listOf(ImportedFile("IMG-1.jpg", ByteArray(0)), ImportedFile("WhatsApp Chat with Ben.txt", "17.10.26, 10:00 - Anna: Hi\n".toByteArray()), ImportedFile("VID-2.mp4", ByteArray(0))))
        assertEquals("Ben", src.title); assertEquals(1, src.mediaFiles[MediaType.PHOTO]); assertEquals(1, src.mediaFiles[MediaType.VIDEO])
    }

    @Test(expected = ImportException::class) fun rejectsNonChat() { Importer.load(listOf(ImportedFile("x.txt", "just some text".toByteArray()))) }

    @Test fun groupChat() {
        val m = Parser.parse("01.01.26, 10:00 - Anna: a\n01.01.26, 10:01 - Ben: b\n01.01.26, 10:02 - Chris: c\n01.01.26, 10:03 - +49 151 2345678: d")
        val s = ChatStats(m)
        assertTrue(s.isGroup); assertEquals(4, s.people.size)
    }

    @Test fun emoji() { assertEquals(listOf("👍", "❤", "👨‍👩‍👧", "🇩🇪"), Words.emojis("ok 👍🏽 ❤️ a 👨‍👩‍👧 🇩🇪 © 1")) }

    @Test fun sampleChatParses() {
        val text = javaClass.classLoader!!.getResource("sample_chat.txt")!!.readText()
        val m = Parser.parse(text)
        assertTrue(m.size > 5000); assertEquals(setOf("Lena", "Jonas"), m.map { it.sender }.toSet())
        assertTrue(m.any { it.text.contains("\nDas war so lustig\n") })
    }
}
