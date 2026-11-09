package de.bartuan.chatwrapped

import java.time.LocalDateTime
import java.util.zip.ZipInputStream

enum class MediaType { NONE, PHOTO, VIDEO, VOICE, STICKER, GIF, DOC, OTHER }

data class Msg(
    val time: LocalDateTime,
    val sender: String,
    val text: String,
    val media: MediaType = MediaType.NONE,
    val deleted: Boolean = false,
)

data class ImportedFile(val name: String, val bytes: ByteArray)

/** Result of reading one or more shared/picked files. */
data class ChatSource(val title: String, val text: String, val mediaFiles: Map<MediaType, Int> = emptyMap())

class ImportException(msg: String) : Exception(msg)

object Importer {
    private val chatNameRx = Regex("""(?i)^WhatsApp[ -]Chat (?:mit|with) (.+?)(?:\.txt|\.zip)?$""")
    private const val MAX_TXT = 60L * 1024 * 1024

    fun mediaTypeOfFile(name: String): MediaType? {
        val n = name.substringAfterLast('/').lowercase()
        val ext = n.substringAfterLast('.', "")
        return when {
            n.contains("sticker") || ext == "webp" -> MediaType.STICKER
            n.contains("ptt-") || n.contains("audio") || ext in setOf("opus", "ogg", "m4a", "aac", "mp3", "amr") -> MediaType.VOICE
            n.contains("gif") -> MediaType.GIF
            ext in setOf("jpg", "jpeg", "png", "heic") -> MediaType.PHOTO
            ext in setOf("mp4", "3gp", "mov", "mkv") -> MediaType.VIDEO
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "vcf", "zip") -> MediaType.DOC
            ext.isEmpty() || ext == "txt" -> null
            else -> MediaType.OTHER
        }
    }

    fun isZip(b: ByteArray) = b.size > 3 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    fun titleFrom(fileName: String): String? {
        val base = fileName.substringAfterLast('/').trim()
        chatNameRx.find(base)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun chatScore(name: String): Int {
        val n = name.substringAfterLast('/')
        return when {
            n.equals("_chat.txt", true) -> 3
            n.startsWith("WhatsApp", true) && n.endsWith(".txt", true) -> 2
            n.endsWith(".txt", true) -> 1
            else -> 0
        }
    }

    fun decodeText(b: ByteArray): String {
        var s = String(b, Charsets.UTF_8)
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        return s
    }

    /** Accepts any mix of .txt, .zip and media files (WhatsApp "Chat exportieren" with media). */
    fun load(files: List<ImportedFile>): ChatSource {
        if (files.isEmpty()) throw ImportException("Keine Datei erhalten.")
        val media = HashMap<MediaType, Int>()
        var best: Pair<String, String>? = null // name, text
        var bestScore = -1
        var title: String? = null
        fun offerTxt(name: String, text: String) {
            val sc = chatScore(name).let { if (Parser.looksLikeChat(text)) it + 10 else it }
            if (sc > bestScore) { bestScore = sc; best = name to text }
        }
        for (f in files) {
            if (title == null) title = titleFrom(f.name)
            when {
                isZip(f.bytes) -> ZipInputStream(f.bytes.inputStream()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        val n = e.name
                        if (n.contains("..") || n.substringAfterLast('/').startsWith("._")) continue
                        if (n.endsWith(".txt", true)) {
                            val data = readLimited(z)
                            if (title == null) title = titleFrom(n)
                            offerTxt(n, decodeText(data))
                        } else mediaTypeOfFile(n)?.let { media.merge(it, 1, Int::plus) }
                    }
                }
                f.name.endsWith(".txt", true) || mediaTypeOfFile(f.name) == null -> offerTxt(f.name, decodeText(f.bytes))
                else -> mediaTypeOfFile(f.name)?.let { media.merge(it, 1, Int::plus) }
            }
        }
        val b = best ?: throw ImportException("Im Export wurde keine Chat-Textdatei (.txt) gefunden.")
        if (bestScore < 10) throw ImportException("Die Datei sieht nicht wie ein WhatsApp-Chat-Export aus.")
        return ChatSource(title ?: "", b.second, media)
    }

    private fun readLimited(z: ZipInputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val r = z.read(buf); if (r < 0) break
            total += r; if (total > MAX_TXT) throw ImportException("Chat-Datei ist zu groß.")
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }
}

object Parser {
    // [date, time] or date, time -
    private const val DATE = """(\d{1,4})[./-](\d{1,2})[./-](\d{1,4})"""
    private const val TIME = """(\d{1,2})[:.](\d{2})(?:[:.](\d{2}))?(?:\s*([AaPp])\.?\s?[Mm]\.?)?"""
    private val iosRx = Regex("""^\[$DATE,?\s+$TIME\]\s?(.*)$""")
    private val androidRx = Regex("""^$DATE,?\s+$TIME\s?[-–]\s(.*)$""")
    private val nameSplit = Regex("""^([^:\n]{0,80}?):\s(.*)$""", RegexOption.DOT_MATCHES_ALL)

    private val invisible = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")
    fun clean(s: String) = s.replace(invisible, "").replace('\u202F', ' ').replace('\u00A0', ' ').replace('\u2009', ' ')

    private val systemRx = Regex(
        "(?i)(ende-zu-ende-verschlüsselt|end-to-end encrypted|hat die gruppe|hat die community|created (the )?group|" +
            "hat .* hinzugefügt|wurde hinzugefügt|\\badded\\b|hat die gruppe verlassen|\\bleft$|hat .* entfernt|\\bremoved\\b|" +
            "sicherheitsnummer|security code|ist .* beigetreten|beigetreten|\\bjoined\\b|hat den betreff|changed the subject|" +
            "changed the group|gruppenbild|group icon|gruppenbeschreibung|group description|ist jetzt admin|now an admin|" +
            "kein admin mehr|no longer an admin|verschwindende nachrichten|disappearing messages|" +
            "hat die nummer gewechselt|changed (their|his|her) phone number|changed to \\+|" +
            "einstellungen der gruppe|group settings|nachrichten an diese gruppe|community|" +
            "hat diese gruppe erstellt|created this group|du wurdest|you were added|hat dich hinzugefügt|added you|" +
            "verpasster (sprach|video)anruf|missed (voice|video) call|blockiert|unblocked|blocked this contact|" +
            "kontakt (blockiert|freigegeben)|nachrichten werden.*gespeichert|tippe, um mehr)"
    )
    private val encryptionRx = Regex("(?i)^(nachrichten und anrufe sind ende-zu-ende|messages and calls are end-to-end|messages to this (group|chat) are now|deine sicherheitsnummer|your security code)")

    private data class Head(val a: Int, val b: Int, val c: Int, val h: Int, val mi: Int, val s: Int, val ap: String, val rest: String)

    private fun head(line: String): Head? {
        val raw = line.trimEnd('\r')
        val l = clean(raw).trimStart()
        val m = iosRx.find(l) ?: androidRx.find(l) ?: return null
        val g = m.groupValues
        return Head(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt(), g[7].lowercase(), g[8])
    }

    enum class Order { DMY, MDY, YMD }

    /** Scan all dates: a first field > 12 means day-first, a second field > 12 means month-first. */
    fun detectOrder(heads: List<Pair<Int, Int>>, firstIsYear: Boolean, slashAmPm: Boolean): Order {
        if (firstIsYear) return Order.YMD
        var dayFirst = 0; var monthFirst = 0
        for ((a, b) in heads) { if (a > 12 && b <= 12) dayFirst++; if (b > 12 && a <= 12) monthFirst++ }
        return when {
            dayFirst > monthFirst -> Order.DMY
            monthFirst > dayFirst -> Order.MDY
            slashAmPm -> Order.MDY
            else -> Order.DMY
        }
    }

    fun looksLikeChat(text: String): Boolean {
        var hits = 0
        for (line in text.lineSequence().take(60)) if (head(line) != null) hits++
        return hits >= 1
    }

    fun mediaOf(t: String): MediaType {
        val l = t.trim().lowercase()
        if (l.isEmpty()) return MediaType.NONE
        if (l == "<medien ausgeschlossen>" || l == "<media omitted>" || l == "<medien weggelassen>") return MediaType.OTHER
        val attached = Regex("""^<(?:angehängt|attached):\s*([^>]+)>""").find(l)
        if (attached != null) {
            val f = attached.groupValues[1]
            return when {
                "photo" in f -> MediaType.PHOTO; "video" in f -> MediaType.VIDEO
                "audio" in f || "ptt" in f -> MediaType.VOICE; "sticker" in f -> MediaType.STICKER
                "gif" in f -> MediaType.GIF
                else -> Importer.mediaTypeOfFile(f) ?: MediaType.DOC
            }
        }
        val fileAttached = Regex("""^(\S+\.\w{2,5}) \((?:datei angehängt|file attached)\)""").find(l)
        if (fileAttached != null) return Importer.mediaTypeOfFile(fileAttached.groupValues[1]) ?: MediaType.DOC
        return when {
            l.startsWith("bild weggelassen") || l.startsWith("image omitted") || l.startsWith("foto weggelassen") -> MediaType.PHOTO
            l.startsWith("video weggelassen") || l.startsWith("video omitted") -> MediaType.VIDEO
            l.startsWith("audio weggelassen") || l.startsWith("audio omitted") || l.startsWith("sprachnachricht weggelassen") || l == "<sprachnachricht>" -> MediaType.VOICE
            l.startsWith("sticker weggelassen") || l.startsWith("sticker omitted") -> MediaType.STICKER
            l.startsWith("gif weggelassen") || l.startsWith("gif omitted") -> MediaType.GIF
            l.startsWith("dokument weggelassen") || l.startsWith("document omitted") || l.endsWith("dokument weggelassen") || l.endsWith("document omitted") -> MediaType.DOC
            l.startsWith("kontaktkarte weggelassen") || l.startsWith("contact card omitted") -> MediaType.DOC
            else -> MediaType.NONE
        }
    }

    private val deletedRx = Regex("(?i)^(diese nachricht wurde gelöscht\\.?|this message was deleted\\.?|du hast diese nachricht gelöscht\\.?|you deleted this message\\.?)$")
    private val editedRx = Regex("(?i)\\s*<(diese nachricht wurde bearbeitet|this message was edited)>\\s*$")

    fun parse(raw: String): List<Msg> {
        val lines = raw.split('\n')
        // pass 1: date order
        val pairs = ArrayList<Pair<Int, Int>>()
        var yearFirst = false; var slashAmPm = false; var seen = 0
        for (line in lines) {
            val h = head(line) ?: continue
            pairs.add(h.a to h.b)
            if (h.a > 31) yearFirst = true
            if (seen++ < 50 && h.ap.isNotEmpty() && clean(line).contains('/')) slashAmPm = true
        }
        val order = detectOrder(pairs, yearFirst, slashAmPm)

        val out = ArrayList<Msg>()
        var curTime: LocalDateTime? = null; var curSender = ""; var curText: StringBuilder? = null
        fun flush() {
            val sb = curText ?: return
            val t = curTime ?: return
            var text = sb.toString().trimEnd()
            text = text.replace(editedRx, "")
            val del = deletedRx.matches(text.trim())
            out.add(Msg(t, curSender, if (del) "" else text, if (del) MediaType.NONE else mediaOf(text), del))
            curText = null
        }
        for (line in lines) {
            val h = head(line)
            if (h == null) { curText?.append('\n')?.append(clean(line.trimEnd('\r'))); continue }
            flush()
            val time = toTime(h, order) ?: continue
            val ns = nameSplit.find(h.rest)
            if (ns == null) continue // group system line without sender
            val name = ns.groupValues[1].trim()
            val text = ns.groupValues[2]
            val rawText = line.substringAfter(": ", "")
            if (isSystem(name, text, rawText)) continue
            curTime = time; curSender = name; curText = StringBuilder(text)
        }
        flush()
        return out
    }

    /** WhatsApp sometimes exports the own messages with an empty sender ("…12:36 -  : text"). */
    fun hasBlankSender(msgs: List<Msg>) = msgs.any { it.sender.isBlank() }
    fun withSelfName(msgs: List<Msg>, name: String?): List<Msg> {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return msgs
        return if (!hasBlankSender(msgs)) msgs else msgs.map { if (it.sender.isBlank()) it.copy(sender = n) else it }
    }

    private fun isSystem(name: String, text: String, rawText: String): Boolean {
        val t = text.trim()
        if (encryptionRx.containsMatchIn(t)) return true
        if (systemRx.containsMatchIn(name)) return true // colon came from inside a system sentence
        // iOS: system notices are prefixed with U+200E and attributed to the group name
        if (rawText.startsWith("\u200E") && mediaOf(t) == MediaType.NONE && !deletedRx.matches(t) && systemRx.containsMatchIn(t)) return true
        return false
    }

    private fun toTime(h: Head, order: Order): LocalDateTime? = try {
        var (d, mo, y) = when (order) {
            Order.DMY -> Triple(h.a, h.b, h.c)
            Order.MDY -> Triple(h.b, h.a, h.c)
            Order.YMD -> Triple(h.c, h.b, h.a)
        }
        if (mo > 12 && d <= 12) { val x = d; d = mo; mo = x }
        if (y < 100) y += 2000
        var hr = h.h
        if (h.ap == "p" && hr < 12) hr += 12
        if (h.ap == "a" && hr == 12) hr = 0
        LocalDateTime.of(y, mo, d, hr, h.mi, h.s)
    } catch (e: Exception) { null }
}
