package de.bartuan.chatwrapped

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import de.bartuan.chatwrapped.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import de.bartuan.chatwrapped.analysis.*

class OpenChat(val id: String, val title: String, val stats: ChatStats, val cards: List<StoryCard>, verdict: String?) {
    var verdict by mutableStateOf(verdict)
    val ana = AnalysisUi()
}

/** UI state + background work. Network only for the opt-in full analysis (see analysis/Analysis.kt). */
class AppController(val store: ChatStore, private val scope: CoroutineScope, private val client: CwClient? = CwConfig.default()?.let { CwClient(it) }) {
    private var pollJob: Job? = null

    private fun files(o: OpenChat) = CwFiles(store.chatDir(o.id))

    /** Load persisted job/result; resume an interrupted upload and start polling. */
    fun attachAnalysis(o: OpenChat) {
        val f = files(o)
        o.ana.result = f.result(); o.ana.job = f.job()
        if (o.ana.result == null) when (o.ana.job?.state) {
            CwState.SENDING -> sendAnalysis(o)
            CwState.WAITING -> startPolling(o)
            else -> {}
        }
    }

    fun startAnalysis() { val o = open ?: return; if (client == null) { o.ana.job = null; error = "Analyse ist in dieser Version nicht eingerichtet."; return }
        files(o).clear(); o.ana.result = null
        val j = client.newJob(); files(o).saveJob(j); o.ana.job = j; sendAnalysis(o) }

    private fun sendAnalysis(o: OpenChat) {
        val c = client ?: return
        val j0 = o.ana.job ?: return
        scope.launch {
            try {
                val src = withContext(Dispatchers.IO) { store.source(o.id) } ?: throw ImportException("Chat nicht gefunden.")
                val j = withContext(Dispatchers.IO) {
                    c.send(j0, src.text, o.title, o.stats.ranked.map { it.name }, o.stats.total, o.stats.first.toString(), o.stats.last.toString(),
                        selfName = store.selfName(o.id),
                        persist = { files(o).saveJob(it) }, progress = { p -> scope.launch { o.ana.progress = p } })
                }
                o.ana.job = j; o.ana.progress = null; startPolling(o)
            } catch (e: Exception) {
                val j = j0.let { files(o).job() ?: it }.copy(state = CwState.ERROR, error = "Senden fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}. Prüfe die Internetverbindung.")
                files(o).saveJob(j); o.ana.job = j; o.ana.progress = null
            }
        }
    }

    /** Retry after an error: keeps id + already uploaded parts (if still fresh). */
    fun retryAnalysis() { val o = open ?: return; val j = o.ana.job ?: return startAnalysis()
        val nj = j.copy(state = CwState.SENDING, error = null); files(o).saveJob(nj); o.ana.job = nj; sendAnalysis(o) }

    fun cancelAnalysis() { val o = open ?: return; pollJob?.cancel(); files(o).clear(); o.ana.job = null; o.ana.result = null; o.ana.progress = null }

    fun startPolling(o: OpenChat) {
        val c = client ?: return
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && open === o) {
                val j = o.ana.job ?: break
                if (j.state != CwState.WAITING) break
                o.ana.lastCheck = System.currentTimeMillis()
                val r = withContext(Dispatchers.IO) { runCatching { c.poll(j) }.getOrNull() }
                if (r != null) {
                    withContext(Dispatchers.IO) { files(o).saveResult(r); files(o).saveJob(j.copy(state = CwState.DONE)) }
                    o.ana.result = r; o.ana.job = j.copy(state = CwState.DONE); break
                }
                delay(15_000)
            }
        }
    }

    var saved by mutableStateOf(store.list())
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var open by mutableStateOf<OpenChat?>(null)
    /** chat id waiting for "Wie heißt du in diesem Chat?" */
    var askName by mutableStateOf<String?>(null)

    fun confirmName(name: String) {
        val id = askName ?: return; askName = null
        val n = name.trim().ifEmpty { "Du" }
        store.setSelfName(id, n)
        openSaved(id)
    }

    fun import(load: () -> List<ImportedFile>) {
        busy = true; error = null
        scope.launch {
            try {
                val chat = withContext(Dispatchers.Default) {
                    val src = Importer.load(load())
                    val msgs = Parser.parse(src.text)
                    if (msgs.isEmpty()) throw ImportException("Im Export wurden keine Nachrichten erkannt.")
                    val id = store.idFor(src.text)
                    val self = store.selfName(id)
                    val named = Parser.withSelfName(msgs, self ?: "Du")
                    val st = ChatStats(named, src.mediaFiles)
                    val meta = store.save(src, st)
                    meta.id to (if (Parser.hasBlankSender(msgs) && self == null) null
                    else OpenChat(meta.id, meta.title, st, buildCards(st, meta.title), store.verdict(meta.id)))
                }
                saved = store.list()
                if (chat.second == null) askName = chat.first
                else { open = chat.second; attachAnalysis(chat.second!!) }
            } catch (e: ImportException) { error = e.message
            } catch (e: OutOfMemoryError) { error = "Der Chat ist zu groß für dieses Gerät."
            } catch (e: Exception) { error = "Import fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}" }
            busy = false
        }
    }

    fun openSaved(id: String) {
        busy = true; error = null
        scope.launch {
            try {
                open = withContext(Dispatchers.Default) {
                    val src = store.source(id) ?: throw ImportException("Analyse nicht gefunden.")
                    val raw = Parser.parse(src.text)
                    val st = ChatStats(Parser.withSelfName(raw, store.selfName(id) ?: "Du"), src.mediaFiles)
                    if (Parser.hasBlankSender(raw)) store.setSelfName(id, store.selfName(id) ?: "Du", st.ranked.map { it.name })
                    OpenChat(id, src.title, st, buildCards(st, src.title), store.verdict(id))
                }
                open?.let { attachAnalysis(it) }
            } catch (e: Exception) { error = e.message }
            busy = false
        }
    }

    fun delete(id: String) { store.delete(id); saved = store.list(); if (open?.id == id) open = null }
    fun saveVerdict(text: String) { val o = open ?: return; store.saveVerdict(o.id, text); o.verdict = text }
    fun deleteVerdict() { val o = open ?: return; store.deleteVerdict(o.id); o.verdict = null }
}

class MainActivity : ComponentActivity() {
    private lateinit var ctl: AppController
    private val scope = MainScope()

    private var pendingPdf: ByteArray? = null
    private val pdfSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val b = pendingPdf; pendingPdf = null
        if (uri != null && b != null) runCatching { contentResolver.openOutputStream(uri)?.use { it.write(b) } }
            .onSuccess { toast("PDF gespeichert") }.onFailure { toast("Speichern fehlgeschlagen") }
    }
    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()
    private fun pdfName(o: OpenChat) = "Analyse_" + o.title.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifEmpty { "Chat" } + ".pdf"

    private fun withPdf(then: (OpenChat, ByteArray) -> Unit) {
        val o = ctl.open ?: return; val r = o.ana.result ?: return
        scope.launch {
            val b = runCatching { withContext(Dispatchers.Default) { PdfReport.pdf(r, o.stats, o.title) } }.getOrNull()
            if (b == null) toast("PDF konnte nicht erstellt werden") else then(o, b)
        }
    }
    private fun savePdf() = withPdf { o, b -> pendingPdf = b; pdfSaver.launch(pdfName(o)) }
    private fun sharePdf() = withPdf { o, b ->
        val dir = java.io.File(cacheDir, "pdf").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = java.io.File(dir, pdfName(o)).apply { writeBytes(b) }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(Intent.createChooser(send, "Analyse teilen"))
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ctl.import { uris.map { read(it) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ctl = AppController(ChatStore(filesDir), scope)
        handle(intent)
        setContent {
            WrappedTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Pastel.bg) {
                    val o = ctl.open
                    ctl.askName?.let { NameDialog(ctl::confirmName) }
                    if (o == null) {
                        HomeScreen(ctl.saved, ctl.busy, ctl.error, { picker.launch(arrayOf("text/plain", "application/zip", "application/x-zip-compressed", "application/octet-stream")) }, ctl::openSaved, ctl::delete)
                    } else {
                        BackHandler { ctl.open = null }
                        ChatScreen(o.title, o.stats, o.cards, o.verdict, { ctl.open = null }, ::sharePrompt, ctl::saveVerdict, ctl::deleteVerdict,
                            ana = o.ana, onSavePdf = ::savePdf, onSharePdf = ::sharePdf, onAnalyze = ctl::startAnalysis, onRetry = ctl::retryAnalysis, onCancel = ctl::cancelAnalysis)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    @Suppress("DEPRECATION")
    private fun handle(i: Intent?) {
        i ?: return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> {
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))?.let { uris.add(it) }
                if (uris.isEmpty()) {
                    val text = i.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) { ctl.import { listOf(ImportedFile(i.getStringExtra(Intent.EXTRA_SUBJECT)?.let { "$it.txt" } ?: "chat.txt", text.toByteArray())) }; return }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let { uris.addAll(it) }
            }
            else -> return
        }
        if (uris.isNotEmpty()) ctl.import { uris.map { read(it) } }
        setIntent(Intent())
    }

    private fun read(uri: Uri): ImportedFile {
        var name = uri.lastPathSegment ?: "datei"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0) }
        // media files from "Medien anhängen" are only counted, so don't read huge videos into memory
        val mt = Importer.mediaTypeOfFile(name)
        if (mt != null && !name.endsWith(".zip", true)) return ImportedFile(name, ByteArray(0))
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw ImportException("Datei konnte nicht gelesen werden.")
        return ImportedFile(name, bytes)
    }

    private fun sharePrompt(prompt: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, prompt); putExtra(Intent.EXTRA_SUBJECT, "Chat Wrapped – Analyse") }
        startActivity(Intent.createChooser(send, "Von Grok Bot analysieren lassen"))
    }

    override fun onResume() { super.onResume(); ctl.open?.let { if (it.ana.job?.state == CwState.WAITING) ctl.startPolling(it) } }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}

