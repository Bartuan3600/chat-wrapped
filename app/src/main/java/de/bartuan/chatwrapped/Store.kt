package de.bartuan.chatwrapped

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class SavedChat(val id: String, val title: String, val importedAt: Long, val messages: Int, val people: List<String>, val media: Map<MediaType, Int>)

/** Everything lives in the app's private storage (no backup, no network). */
class ChatStore(root: File) {
    private val dir = File(root, "chats").apply { mkdirs() }

    fun idFor(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun save(src: ChatSource, stats: ChatStats): SavedChat {
        val id = idFor(src.text)
        val d = File(dir, id).apply { mkdirs() }
        File(d, "chat.txt").writeText(src.text)
        val old = runCatching { JSONObject(File(d, "meta.json").readText()) }.getOrNull()
        val title = src.title.ifBlank { stats.ranked.joinToString(" & ") { it.name }.take(60) }
        val meta = JSONObject().put("title", title).put("importedAt", System.currentTimeMillis()).put("messages", stats.total)
            .put("people", org.json.JSONArray(stats.ranked.map { it.name }))
            .put("media", JSONObject().apply { src.mediaFiles.forEach { (k, v) -> put(k.name, v) } })
        old?.optString("selfName")?.takeIf { it.isNotBlank() }?.let { meta.put("selfName", it) }
        File(d, "meta.json").writeText(meta.toString())
        return readMeta(id)!!
    }

    private fun readMeta(id: String): SavedChat? = try {
        val o = JSONObject(File(File(dir, id), "meta.json").readText())
        val pa = o.optJSONArray("people")
        val mo = o.optJSONObject("media")
        SavedChat(id, o.optString("title"), o.optLong("importedAt"), o.optInt("messages"),
            (0 until (pa?.length() ?: 0)).map { pa!!.getString(it) },
            mo?.keys()?.asSequence()?.mapNotNull { k -> runCatching { MediaType.valueOf(k) }.getOrNull()?.let { it to mo.getInt(k) } }?.toMap() ?: emptyMap())
    } catch (e: Exception) { null }

    fun list(): List<SavedChat> = (dir.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { readMeta(it.name) }.sortedByDescending { it.importedAt }

    fun source(id: String): ChatSource? {
        val m = readMeta(id) ?: return null
        val f = File(File(dir, id), "chat.txt"); if (!f.exists()) return null
        return ChatSource(m.title, f.readText(), m.media)
    }

    fun verdict(id: String): String? = File(File(dir, id), "verdict.md").takeIf { it.exists() }?.readText()
    fun saveVerdict(id: String, text: String) { File(File(dir, id).apply { mkdirs() }, "verdict.md").writeText(text) }
    fun deleteVerdict(id: String) { File(File(dir, id), "verdict.md").delete() }
    /** Name for messages exported with an empty sender ("Wie heißt du in diesem Chat?"). */
    fun selfName(id: String): String? = runCatching { JSONObject(File(File(dir, id), "meta.json").readText()).optString("selfName").takeIf { it.isNotBlank() } }.getOrNull()
    fun setSelfName(id: String, name: String, people: List<String>? = null) {
        val f = File(File(dir, id), "meta.json"); val o = JSONObject(f.readText()).put("selfName", name.trim())
        people?.let { o.put("people", org.json.JSONArray(it)) }
        f.writeText(o.toString())
    }
    fun chatDir(id: String) = File(dir, id)
    fun delete(id: String) { File(dir, id).deleteRecursively() }
}
