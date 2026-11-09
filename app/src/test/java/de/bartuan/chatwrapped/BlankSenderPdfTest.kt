package de.bartuan.chatwrapped

import android.graphics.Bitmap
import de.bartuan.chatwrapped.analysis.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BlankSenderPdfTest {
    private val blank = "30/05/2024, 12:36 -  : Hallo, bist du schon da?\n30/05/2024, 12:37 - Lena: Gleich 😊\n30/05/2024, 12:38 -  : Okay!\n31/05/2024, 09:00 - Lena: Guten Morgen\n"

    @Test fun parserKeepsBlankSenderAndMapsName() {
        val m = Parser.parse(blank)
        assertEquals(4, m.size)
        assertTrue(Parser.hasBlankSender(m))
        assertEquals("", m[0].sender)
        val n = Parser.withSelfName(m, "Alex")
        assertEquals(listOf("Alex", "Lena", "Alex", "Lena"), n.map { it.sender })
        assertFalse(Parser.hasBlankSender(n))
        assertEquals(setOf("Alex", "Lena"), ChatStats(n).people.keys)
    }

    @Test fun storeKeepsSelfNameAcrossReimport() {
        val dir = Files.createTempDirectory("cw").toFile(); val st = ChatStore(dir)
        val src = ChatSource("Lena", blank)
        val meta = st.save(src, ChatStats(Parser.withSelfName(Parser.parse(blank), "Du")))
        assertNull(st.selfName(meta.id))
        st.setSelfName(meta.id, "Alex", listOf("Alex", "Lena"))
        st.save(src, ChatStats(Parser.withSelfName(Parser.parse(blank), "Alex")))
        assertEquals("Alex", st.selfName(meta.id))
        assertEquals(listOf("Alex", "Lena").toSet(), st.list().first().people.toSet())
    }

    @Test fun requestCarriesSelfNameAndPeople() {
        val key = ByteArray(32) { 7 }; val h = FakeHttp(); val c = CwClient(CwConfig("https://ntfy.sh", "t", key), h)
        c.send(c.newJob(), blank, "Lena", listOf("Alex", "Lena"), 4, "a", "b", selfName = "Alex")
        val env = JSONObject(String(h.msgs.last().second))
        val req = JSONObject(CwCrypto.openEnvelope(key, env))
        assertEquals("Alex", req.getString("self_name"))
        assertEquals("Alex", req.getJSONArray("people").getString(0))
    }

    @Test fun pdfReport() {
        val chat = File(System.getProperty("sampleChat")!!).readText()
        val stats = ChatStats(Parser.parse(chat))
        val r = AnalysisResult.parse(javaClass.classLoader!!.getResource("sample_result.json")!!.readText())
        val out = File(System.getProperty("screenshotDir")!!).apply { mkdirs() }
        val bs = BitmapSink(2f); PdfReport.render(bs, r, stats, "Lena & Jonas")
        assertTrue("expected several pages", bs.pages.size >= 3)
        bs.pages.forEachIndexed { i, b -> File(out, "pdf_page_${i + 1}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val pdf = runCatching { PdfReport.pdf(r, stats, "Lena & Jonas") }
        pdf.onSuccess { assertEquals("%PDF", String(it.copyOfRange(0, 4))); File(out, "../build/Analyse_Lena_Jonas_robolectric.pdf").writeBytes(it) }
        println("PdfDocument under Robolectric: " + (pdf.exceptionOrNull()?.toString() ?: "ok, ${pdf.getOrNull()!!.size} bytes"))
    }
}
