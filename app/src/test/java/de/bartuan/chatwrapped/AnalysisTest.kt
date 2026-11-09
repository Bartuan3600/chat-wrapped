package de.bartuan.chatwrapped

import de.bartuan.chatwrapped.analysis.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Fake ntfy + textdb in memory. */
class FakeHttp : CwHttp {
    val files = HashMap<String, ByteArray>()
    val msgs = ArrayList<Pair<Map<String, String>, ByteArray>>()
    val db = HashMap<String, String>()
    var failPutAfter = Int.MAX_VALUE
    var puts = 0
    override fun send(method: String, url: String, body: ByteArray, headers: Map<String, String>, onProgress: (Long) -> Unit): Pair<Int, String> {
        onProgress(body.size.toLong())
        if (method == "PUT") {
            if (++puts > failPutAfter) throw java.io.IOException("offline")
            val u = "https://ntfy.sh/file/${files.size}.bin"; files[u] = body; msgs += headers to body
            return 200 to JSONObject().put("attachment", JSONObject().put("url", u)).toString()
        }
        msgs += headers to body; return 200 to "{}"
    }
    override fun get(url: String): Pair<Int, String> = db[url.substringBefore('?')]?.let { 200 to it } ?: (404 to "")
}

@RunWith(RobolectricTestRunner::class)
class AnalysisTest {
    private val key = ByteArray(32) { it.toByte() }
    private val cfg = CwConfig("https://ntfy.sh", "test-topic", key)
    private val chat = File(System.getProperty("sampleChat")!!).readText()

    private fun decodeRequest(h: FakeHttp): JSONObject {
        val (hdr, body) = h.msgs.last { it.first["Tags"] == CwProtocol.TAG }
        val env = if (hdr.containsKey("Filename")) JSONObject(String(body)) else JSONObject(String(body))
        return JSONObject(CwCrypto.openEnvelope(key, env)).also { assertEquals(env.getString("id"), it.getString("id")) }
    }

    @Test fun wholeChatIsSentAndRoundTrips() {
        val h = FakeHttp(); val c = CwClient(cfg, h, partSize = 20_000)   // force several parts
        val j = c.send(c.newJob(), chat, "Lena & Jonas", listOf("Lena", "Jonas"), 6472, "a", "b")
        assertEquals(CwState.WAITING, j.state)
        val req = decodeRequest(h)
        val parts = req.getJSONObject("chat").getJSONArray("parts")
        assertTrue("expected chunking", parts.length() > 1)
        var gz = ByteArray(0)
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i); val blob = h.files[p.getString("url")]!!
            assertEquals(p.getString("sha256"), CwCrypto.sha256Hex(blob))
            gz += CwCrypto.decryptBlob(key, blob)
        }
        assertEquals(chat, String(CwCrypto.gunzip(gz), Charsets.UTF_8))   // ALL messages, byte-identical
        assertEquals(CwProtocol.SECTIONS.size, req.getJSONArray("sections").length())
    }

    @Test fun resumesAfterFailedUpload() {
        val h = FakeHttp().apply { failPutAfter = 2 }; val c = CwClient(cfg, h, partSize = 5_000)
        var saved: CwJob? = null
        assertThrows(Exception::class.java) { c.send(c.newJob(), chat, "t", listOf("Lena"), 1, "a", "b", persist = { saved = it }) }
        assertEquals(2, saved!!.parts.size)
        h.failPutAfter = Int.MAX_VALUE; val before = h.puts
        val j = c.send(saved!!, chat, "t", listOf("Lena"), 1, "a", "b")
        assertEquals(CwState.WAITING, j.state)
        assertEquals(j.parts.size - 2, h.puts - before)   // finished parts not re-uploaded
    }

    @Test fun bigEnvelopeGoesAsAttachment() {
        val h = FakeHttp(); val c = CwClient(cfg, h, partSize = 1000)   // many parts -> envelope > 4 KB
        c.send(c.newJob(), chat, "t", listOf("Lena", "Jonas"), 1, "a", "b")
        val (hdr, _) = h.msgs.last()
        assertEquals(CwProtocol.TAG, hdr["Tags"]); assertTrue(hdr["Filename"]!!.endsWith("-request.json"))
        decodeRequest(h)
    }

    @Test fun pollReadsEncryptedResultAndChecksId() {
        val h = FakeHttp(); val c = CwClient(cfg, h)
        val j = c.newJob().copy(state = CwState.WAITING)
        assertNull(c.poll(j))
        val sample = javaClass.classLoader!!.getResource("sample_result.json")!!.readText()
        h.db[j.resultUrl] = CwCrypto.envelope(key, sample.replace("REPLACE", "other-id")).toString()
        assertNull("wrong id ignored", c.poll(j))
        h.db[j.resultUrl] = CwCrypto.envelope(key, sample.replace("REPLACE", j.reqId)).toString()
        val r = c.poll(j)!!
        assertEquals(86, r.score); assertEquals(21, r.sections.size)
        assertEquals("Lena", r.sections.first { it.key == "who_writes_more" }.people[0].name)
    }

    /** Opt-in live round trip against a FRESH test topic: -PcwE2eDir=… -PcwE2ePhase=send|receive */
    @Test fun e2e() {
        val dir = System.getProperty("cw.e2e.dir"); val phase = System.getProperty("cw.e2e.phase")
        assumeTrue(dir != null && phase != null)
        val d = File(dir!!)
        val k = CwCrypto.parseKey(File(d, "key.b64").readText())!!
        val topic = File(d, "topic.txt").readText().trim()
        assertTrue(topic.startsWith("cw-e2e-"))
        val ps = File(d, "part_size.txt").takeIf { it.exists() }?.readText()?.trim()?.toInt() ?: CwProtocol.PART_SIZE
        val c = CwClient(CwConfig("https://ntfy.sh", topic, k), partSize = ps)
        if (phase == "send") {
            val j = c.send(c.newJob(), chat, "Lena & Jonas", listOf("Lena", "Jonas"), 6472, "2025-11-02", "2026-10-15")
            File(d, "job.json").writeText(j.toJson().toString())
        } else {
            val j = CwJob.from(JSONObject(File(d, "job.json").readText()))
            var r: AnalysisResult? = null
            repeat(20) { if (r == null) { r = c.poll(j); if (r == null) Thread.sleep(3000) } }
            assertNotNull("no result", r)
            File(d, "kotlin_received.txt").writeText("id=${r!!.id} score=${r!!.score} sections=${r!!.sections.size}")
        }
    }
}
