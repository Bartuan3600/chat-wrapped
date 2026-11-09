package de.bartuan.chatwrapped.analysis

import de.bartuan.chatwrapped.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM exactly like Ausgaben (see docs/PROTOCOL.md). Never log key material. */
object CwCrypto {
    const val ALG = "aes-256-gcm"
    private val rnd = SecureRandom()
    fun parseKey(b64: String?): ByteArray? = b64?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Base64.getDecoder().decode(it.trim()) }.getOrNull() }?.takeIf { it.size == 32 }
    fun newIv() = ByteArray(12).also { rnd.nextBytes(it) }
    private fun cipher(mode: Int, key: ByteArray, iv: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
    fun encryptBlob(key: ByteArray, plain: ByteArray, iv: ByteArray = newIv()) = iv + cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain)
    fun decryptBlob(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size >= 28) { "Blob zu kurz" }
        return cipher(Cipher.DECRYPT_MODE, key, blob.copyOfRange(0, 12)).doFinal(blob, 12, blob.size - 12)
    }
    fun envelope(key: ByteArray, plain: String, id: String? = null): JSONObject {
        val iv = newIv()
        val ct = cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().apply { if (id != null) put("id", id); put("enc", ALG); put("iv", b64(iv)); put("data", b64(ct)) }
    }
    fun openEnvelope(key: ByteArray, env: JSONObject): String {
        require(env.optString("enc") == ALG) { "Unbekannte Verschlüsselung" }
        val iv = Base64.getDecoder().decode(env.getString("iv"))
        return String(cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(Base64.getDecoder().decode(env.getString("data"))), Charsets.UTF_8)
    }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}

data class CwConfig(val server: String, val topic: String, val key: ByteArray) {
    companion object {
        fun default(): CwConfig? {
            val key = CwCrypto.parseKey(BuildConfig.CW_KEY_B64) ?: return null
            if (BuildConfig.CW_REQ_TOPIC.isBlank()) return null
            return CwConfig(BuildConfig.CW_SERVER.ifBlank { "https://ntfy.sh" }.trimEnd('/'), BuildConfig.CW_REQ_TOPIC, key)
        }
    }
}

/** Minimal HTTP; [onProgress] gets bytes written. Interface so tests can swap it. */
interface CwHttp {
    fun send(method: String, url: String, body: ByteArray, headers: Map<String, String>, onProgress: (Long) -> Unit = {}): Pair<Int, String>
    fun get(url: String): Pair<Int, String>
}

object RealHttp : CwHttp {
    override fun send(method: String, url: String, body: ByteArray, headers: Map<String, String>, onProgress: (Long) -> Unit): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.doOutput = true; c.connectTimeout = 20_000; c.readTimeout = 120_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { o ->
                var off = 0
                while (off < body.size) { val n = minOf(64 * 1024, body.size - off); o.write(body, off, n); off += n; onProgress(off.toLong()) }
            }
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to txt
        } finally { c.disconnect() }
    }
    override fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000; c.readTimeout = 60_000; c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            val txt = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return code to txt
        } finally { c.disconnect() }
    }
}

enum class CwState { NONE, SENDING, WAITING, DONE, ERROR }

/** Persisted per chat in chats/<id>/analysis.json */
data class CwJob(
    val reqId: String, val resultUrl: String, val state: CwState, val createdAt: Long,
    val sentAt: Long = 0, val parts: List<JSONObject> = emptyList(), val partsAt: Long = 0, val error: String? = null,
) {
    fun toJson() = JSONObject().put("reqId", reqId).put("resultUrl", resultUrl).put("state", state.name).put("createdAt", createdAt)
        .put("sentAt", sentAt).put("parts", JSONArray(parts)).put("partsAt", partsAt).put("error", error ?: JSONObject.NULL)
    companion object {
        fun from(o: JSONObject) = CwJob(o.getString("reqId"), o.getString("resultUrl"), CwState.valueOf(o.getString("state")), o.optLong("createdAt"),
            o.optLong("sentAt"), o.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList(),
            o.optLong("partsAt"), o.optString("error").takeIf { o.has("error") && !o.isNull("error") })
    }
}

data class Progress(val step: String, val done: Long, val total: Long)

object CwProtocol {
    const val TAG = "chat-request"
    const val PART_SIZE = 8 * 1024 * 1024        // < ntfy.sh 15 MB per attachment
    const val MAX_TOTAL = 90L * 1024 * 1024      // ntfy.sh: 100 MB per visitor
    const val NTFY_BODY_MAX = 4096
    const val PART_TTL_MS = 150 * 60 * 1000L     // ntfy.sh attachments expire after ~3 h
    val SECTIONS = listOf("overview", "who_writes_more", "response_times", "starters_enders", "activity_times", "streaks_pauses",
        "mood_timeline", "topics", "running_gags", "emojis", "writing_style", "questions_answers", "conflicts", "investment",
        "red_flags", "green_flags", "highlights", "phases", "compatibility", "tips", "verdict")
    fun now(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

/** Sends the WHOLE chat (gzip → AES-GCM → ntfy attachments) and polls the textdb result. */
class CwClient(private val cfg: CwConfig, private val http: CwHttp = RealHttp, private val resultBase: String = "https://textdb.dev/api/data/",
               private val partSize: Int = CwProtocol.PART_SIZE) {

    fun newJob(): CwJob = CwJob(UUID.randomUUID().toString(), resultBase + UUID.randomUUID(), CwState.SENDING, System.currentTimeMillis())

    /** Uploads missing parts and publishes the request. Returns the updated job (WAITING) or throws. */
    fun send(job: CwJob, chatText: String, title: String, people: List<String>, messages: Int, first: String, last: String,
             selfName: String? = null, persist: (CwJob) -> Unit = {}, progress: (Progress) -> Unit = {}): CwJob {
        val plain = chatText.toByteArray(Charsets.UTF_8)
        progress(Progress("Komprimiere …", 0, 1))
        val gz = CwCrypto.gzip(plain)
        if (gz.size > CwProtocol.MAX_TOTAL) throw IllegalStateException("Der Chat ist auch gepackt zu groß (${gz.size / 1_048_576} MB).")
        val chunks = (gz.indices step partSize).map { gz.copyOfRange(it, minOf(it + partSize, gz.size)) }
        var j = job
        // reuse already uploaded parts only if still fresh on ntfy and made from the same bytes
        val fresh = j.parts.isNotEmpty() && j.parts.size <= chunks.size && System.currentTimeMillis() - j.partsAt < CwProtocol.PART_TTL_MS &&
            j.parts.withIndex().all { (i, p) -> p.optString("src") == CwCrypto.sha256Hex(chunks[i]) }
        val parts = if (fresh) j.parts.toMutableList() else mutableListOf()
        if (!fresh) j = j.copy(parts = emptyList(), partsAt = 0)
        val totalBytes = chunks.sumOf { it.size + 28L }
        var doneBytes = parts.sumOf { it.optLong("size") }
        for ((i, ch) in chunks.withIndex()) {
            if (i < parts.size) continue
            val blob = CwCrypto.encryptBlob(cfg.key, ch)
            val base = doneBytes
            val (code, body) = http.send("PUT", "${cfg.server}/${cfg.topic}", blob, mapOf("Filename" to "${j.reqId}-${i + 1}.bin")) {
                progress(Progress("Lade hoch: Teil ${i + 1} von ${chunks.size}", base + it, totalBytes))
            }
            if (code !in 200..299) throw IllegalStateException("Upload fehlgeschlagen (HTTP $code)")
            val url = JSONObject(body).optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Server lieferte keine Datei-Adresse")
            parts += JSONObject().put("n", i + 1).put("url", url).put("sha256", CwCrypto.sha256Hex(blob)).put("size", blob.size)
                .put("src", CwCrypto.sha256Hex(ch))
            doneBytes += blob.size
            j = j.copy(parts = parts.toList(), partsAt = if (j.partsAt == 0L) System.currentTimeMillis() else j.partsAt); persist(j)
        }
        progress(Progress("Sende Auftrag …", totalBytes, totalBytes))
        val req = JSONObject().put("v", 1).put("app", "chatwrapped").put("id", j.reqId).put("t", CwProtocol.now())
            .put("title", title).put("people", JSONArray(people)).put("messages", messages).put("first", first).put("last", last)
            .put("chat", JSONObject().put("format", "whatsapp-txt").put("compression", "gzip").put("bytes", plain.size)
                .put("sha256", CwCrypto.sha256Hex(plain)).put("gzip_bytes", gz.size).put("parts", JSONArray(parts.map { p -> JSONObject().put("n", p.getInt("n")).put("url", p.getString("url")).put("sha256", p.getString("sha256")).put("size", p.getLong("size")) })))
            .put("sections", JSONArray(CwProtocol.SECTIONS)).put("lang", "de")
            // messages with an EMPTY sender in chat.txt belong to this person (already mapped in "people")
            .put("self_name", selfName ?: JSONObject.NULL)
            .put("result_url", j.resultUrl).put("enc", CwCrypto.ALG)
        val env = CwCrypto.envelope(cfg.key, req.toString(), j.reqId).toString()
        val hdr = mapOf("Tags" to CwProtocol.TAG, "Title" to CwProtocol.TAG)
        val (code, _) = if (env.toByteArray().size <= CwProtocol.NTFY_BODY_MAX)
            http.send("POST", "${cfg.server}/${cfg.topic}", env.toByteArray(), hdr)
        else http.send("PUT", "${cfg.server}/${cfg.topic}", env.toByteArray(), hdr + mapOf("Filename" to "${j.reqId}-request.json",
            "Message" to JSONObject().put("id", j.reqId).put("enc", CwCrypto.ALG).put("envelope", "attachment").toString()))
        if (code !in 200..299) throw IllegalStateException("Auftrag konnte nicht gesendet werden (HTTP $code)")
        return j.copy(state = CwState.WAITING, sentAt = System.currentTimeMillis(), error = null).also(persist)
    }

    /** null = not there yet. Throws only on undecryptable data. */
    fun poll(job: CwJob): AnalysisResult? {
        val (code, body) = runCatching { http.get(job.resultUrl + "?t=" + System.currentTimeMillis()) }.getOrNull() ?: return null
        if (code != 200 || body.isBlank()) return null
        val o = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return null
        val plain = if (o.has("enc")) runCatching { CwCrypto.openEnvelope(cfg.key, o) }.getOrNull() ?: return null else body
        val r = runCatching { AnalysisResult.parse(plain) }.getOrNull() ?: return null
        return r.takeIf { it.id == job.reqId }
    }
}

data class PersonNote(val name: String, val value: String?, val text: String?)
data class Quote(val sender: String?, val date: String?, val text: String, val note: String?)
data class Phase(val period: String, val title: String, val text: String?)
data class ASection(val key: String, val title: String, val emoji: String, val headline: String?, val body: String?, val score: Int?,
                    val people: List<PersonNote>, val bullets: List<String>, val quotes: List<Quote>, val timeline: List<Phase>)
data class AnalysisResult(val id: String, val status: String, val error: String?, val t: String?, val score: Int?, val summary: String?, val sections: List<ASection>, val raw: String) {
    companion object {
        val defaults = mapOf(
            "overview" to ("📋" to "Überblick"), "who_writes_more" to ("🍩" to "Wer schreibt mehr"), "response_times" to ("⚡" to "Antwortzeiten"),
            "starters_enders" to ("👋" to "Wer startet & beendet"), "activity_times" to ("🕰️" to "Aktivitätszeiten"), "streaks_pauses" to ("🔥" to "Serien & Pausen"),
            "mood_timeline" to ("📈" to "Stimmung im Verlauf"), "topics" to ("🗂️" to "Typische Themen"), "running_gags" to ("🔁" to "Insider & Sprüche"),
            "emojis" to ("😀" to "Emojis pro Person"), "writing_style" to ("✍️" to "Schreibstil"), "questions_answers" to ("❓" to "Fragen & Antworten"),
            "conflicts" to ("⛈️" to "Konflikte & Versöhnung"), "investment" to ("⚖️" to "Wer investiert mehr"), "red_flags" to ("🚩" to "Red Flags"),
            "green_flags" to ("💚" to "Green Flags"), "highlights" to ("⭐" to "Highlights & Zitate"), "phases" to ("🧭" to "Phasen der Beziehung"),
            "compatibility" to ("💯" to "Kompatibilität"), "tips" to ("💡" to "Tipps"), "verdict" to ("🏁" to "Gesamtfazit"))

        private fun JSONObject.str(k: String) = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null
        private fun JSONObject.int(k: String) = if (has(k) && !isNull(k)) (opt(k) as? Number)?.toInt() ?: optString(k).filter { it.isDigit() }.toIntOrNull() else null
        private fun <T> JSONArray?.list(f: (Any) -> T?): List<T> = if (this == null) emptyList() else (0 until length()).mapNotNull { runCatching { f(get(it)) }.getOrNull() }

        fun parse(text: String): AnalysisResult {
            val o = JSONObject(text)
            val secs = o.optJSONArray("sections").list { s ->
                s as JSONObject
                val key = s.str("key") ?: "extra"
                val d = defaults[key]
                ASection(key, s.str("title") ?: d?.second ?: key, s.str("emoji") ?: d?.first ?: "✨", s.str("headline"), s.str("body"),
                    s.int("score")?.coerceIn(0, 100),
                    s.optJSONArray("people").list { p -> p as JSONObject; PersonNote(p.getString("name"), p.str("value"), p.str("text")) },
                    s.optJSONArray("bullets").list { it.toString() },
                    s.optJSONArray("quotes").list { q -> q as JSONObject; Quote(q.str("sender"), q.str("date"), q.getString("text"), q.str("note")) },
                    s.optJSONArray("timeline").list { q -> q as JSONObject; Phase(q.optString("period"), q.optString("title"), q.str("text")) })
            }
            return AnalysisResult(o.getString("id"), o.optString("status", "done"), o.str("error"), o.str("t"),
                o.int("score")?.coerceIn(0, 100) ?: secs.firstOrNull { it.key == "compatibility" }?.score, o.str("summary"), secs, text)
        }
    }
}

/** Files next to the chat: analysis.json (job), analysis_result.json (decrypted result). */
class CwFiles(private val chatDir: File) {
    private val jobF get() = File(chatDir, "analysis.json")
    private val resF get() = File(chatDir, "analysis_result.json")
    fun job(): CwJob? = runCatching { CwJob.from(JSONObject(jobF.readText())) }.getOrNull()
    fun saveJob(j: CwJob) { chatDir.mkdirs(); jobF.writeText(j.toJson().toString()) }
    fun result(): AnalysisResult? = runCatching { AnalysisResult.parse(resF.readText()) }.getOrNull()
    fun saveResult(r: AnalysisResult) { chatDir.mkdirs(); resF.writeText(r.raw) }
    fun clear() { jobF.delete(); resF.delete() }
}
