@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package de.bartuan.chatwrapped.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bartuan.chatwrapped.analysis.*

class AnalysisUi {
    var job by mutableStateOf<CwJob?>(null)
    var result by mutableStateOf<AnalysisResult?>(null)
    var progress by mutableStateOf<Progress?>(null)
    var lastCheck by mutableLongStateOf(0L)
}

/** Colour for a person = same index as in the charts (ranked order); unknown names get a neutral grey. */
fun personColor(name: String, people: List<String>): Color {
    val i = people.indexOfFirst { it == name || it.split(' ').first() == name.split(' ').first() }
    return if (i < 0) Pastel.inkSoft else Pastel.series[i % Pastel.series.size]
}

/** Coloured dot + the NAME, so a colour is never the only hint. */
@Composable
fun PersonChip(name: String, people: List<String>, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .8f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(personColor(name, people)))
        Spacer(Modifier.width(6.dp))
        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Pastel.ink, maxLines = 1)
    }
}

@Composable
fun AnalysisPanel(ana: AnalysisUi, onAnalyze: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onSavePdf: () -> Unit = {}, onSharePdf: () -> Unit = {}) {
    var consent by remember { mutableStateOf(false) }
    val j = ana.job
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(18.dp).testTag("analysisPanel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("🔍 Komplette Analyse", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Pastel.ink)
        when {
            ana.result != null -> {
                Text("Analyse erhalten${ana.result?.t?.let { " · ${it.take(16).replace('T', ' ')}" } ?: ""}.", color = Pastel.inkSoft, fontSize = 14.sp)
                if (ana.result?.status != "error") {
                    Button(onSavePdf, Modifier.fillMaxWidth().height(50.dp).testTag("savePdf"), shape = RoundedCornerShape(14.dp)) { Text("📄 Als PDF speichern", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onSharePdf, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("📤 PDF teilen", fontWeight = FontWeight.Bold) }
                }
                TextButton({ consent = true }) { Text("Neu analysieren lassen") }
            }
            j == null || j.state == CwState.NONE -> {
                Text("Der ganze Chat (alle Nachrichten) wird verschlüsselt an APK Builder geschickt. Du bekommst eine ausführliche Auswertung mit über 20 Punkten zurück – direkt hier in der App.", fontSize = 14.sp, color = Pastel.inkSoft)
                Button({ consent = true }, Modifier.fillMaxWidth().height(52.dp).testTag("analyzeBtn"), shape = RoundedCornerShape(16.dp)) { Text("Ganzen Chat analysieren lassen", fontWeight = FontWeight.Bold) }
            }
            j.state == CwState.SENDING -> {
                val p = ana.progress
                Text(p?.step ?: "Wird vorbereitet …", fontWeight = FontWeight.SemiBold, color = Pastel.ink)
                if (p != null && p.total > 0) {
                    LinearProgressIndicator({ (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)))
                    Text("${"%.1f".format(p.done / 1_048_576.0)} von ${"%.1f".format(p.total / 1_048_576.0)} MB (verschlüsselt & gepackt)", fontSize = 12.sp, color = Pastel.inkSoft)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Bitte die App offen lassen, bis der Upload fertig ist.", fontSize = 12.sp, color = Pastel.inkSoft)
            }
            j.state == CwState.WAITING -> {
                Text("⏳ Chat ist angekommen – APK Builder analysiert gerade …", fontWeight = FontWeight.SemiBold, color = Pastel.ink)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Die App prüft alle 15 Sekunden, ob die Analyse fertig ist. Du kannst die App schließen – beim nächsten Öffnen dieses Chats wird weiter geprüft.", fontSize = 12.sp, color = Pastel.inkSoft)
                val waitedMin = (System.currentTimeMillis() - j.sentAt) / 60000
                if (waitedMin >= 120) Text("Dauert ungewöhnlich lange. Du kannst neu senden.", fontSize = 12.sp, color = Color(0xFFB3261E))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (waitedMin >= 120) Button(onAnalyze) { Text("Neu senden") }
                    TextButton(onCancel) { Text("Abbrechen", color = Color(0xFFB3261E)) }
                }
            }
            j.state == CwState.ERROR -> {
                Text(j.error ?: "Fehler beim Senden.", color = Color(0xFF8A1C1C), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onRetry) { Text("Erneut versuchen") }; TextButton(onCancel) { Text("Abbrechen") } }
            }
            else -> {}
        }
        if (ana.result?.status == "error") Text(ana.result?.error ?: "Die Analyse ist fehlgeschlagen.", color = Color(0xFF8A1C1C))
    }
    if (consent) AlertDialog({ consent = false },
        confirmButton = { TextButton({ consent = false; onAnalyze() }, Modifier.testTag("consentYes")) { Text("Ja, senden") } },
        dismissButton = { TextButton({ consent = false }) { Text("Abbrechen") } },
        title = { Text("Ganzen Chat senden?") },
        text = { Text("Der ganze Chat wird verschlüsselt an APK Builder geschickt.\n\n• Alle Nachrichten mit Namen, Datum und Text – keine Fotos/Videos.\n• Ende-zu-Ende mit AES-256 verschlüsselt; der Übertragungsdienst (ntfy.sh) sieht nur unlesbare Daten, die er nach wenigen Stunden löscht.\n• Bitte nur Chats senden, bei denen die anderen Personen einverstanden wären.") })
}

/** Renders the result sections as cards; every person is shown by NAME. */
fun LazyListScope.analysisCards(r: AnalysisResult, people: List<String>) {
    r.score?.let { sc ->
        item(key = "ana_score") {
            CardFrame(StoryCard("ana_score", "💯", "Kompatibilität", "$sc / 100") {
                LinearProgressIndicator({ sc / 100f }, Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)), color = Pastel.pink, trackColor = Color.White.copy(alpha = .6f))
                r.summary?.let { Text(it, fontSize = 16.sp, color = Pastel.ink) }
            }, 3, Modifier.fillMaxWidth())
        }
    }
    r.sections.forEachIndexed { i, s ->
        item(key = "ana_${s.key}_$i") {
            CardFrame(StoryCard(s.key, s.emoji, s.title, s.headline ?: s.title) {
                if (s.score != null && s.key != "compatibility") LinearProgressIndicator({ s.score / 100f }, Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)))
                s.body?.let { Text(it.replace("**", ""), fontSize = 16.sp, lineHeight = 22.sp, color = Pastel.ink) }
                s.people.forEach { p ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .55f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PersonChip(p.name, people); Spacer(Modifier.weight(1f))
                            p.value?.let { Text(it, fontWeight = FontWeight.Black, color = Pastel.ink, fontSize = 15.sp) }
                        }
                        p.text?.let { Text(it, fontSize = 15.sp, color = Pastel.ink, lineHeight = 20.sp) }
                    }
                }
                s.timeline.forEach { ph ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(ph.period, Modifier.width(92.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Pastel.accent)
                        Column(Modifier.weight(1f)) { Text(ph.title, fontWeight = FontWeight.Bold, color = Pastel.ink); ph.text?.let { Text(it, fontSize = 14.sp, color = Pastel.ink) } }
                    }
                }
                s.bullets.forEach { Text("• $it", fontSize = 16.sp, color = Pastel.ink, lineHeight = 22.sp) }
                s.quotes.forEach { q ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .7f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            q.sender?.let { PersonChip(it, people) }; Spacer(Modifier.weight(1f))
                            q.date?.let { Text(it, fontSize = 12.sp, color = Pastel.inkSoft) }
                        }
                        Text("„${q.text}“", fontSize = 16.sp, fontStyle = FontStyle.Italic, color = Pastel.ink)
                        q.note?.let { Text(it, fontSize = 13.sp, color = Pastel.inkSoft) }
                    }
                }
            }, i + 1, Modifier.fillMaxWidth())
        }
    }
}


/** Asked once per chat when the export contains messages with an empty sender name. */
@Composable
fun NameDialog(onConfirm: (String) -> Unit) {
    androidx.compose.ui.window.Dialog({ }) { NameForm(onConfirm) }
}

@Composable
fun NameForm(onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("Du") }
    Column(Modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFFF3EDF7)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Wie heißt du in diesem Chat?", fontSize = 22.sp, color = Pastel.ink)
        Text("Im Export stehen Nachrichten ohne Absender – das sind meistens deine eigenen. Unter welchem Namen sollen sie erscheinen?", fontSize = 14.sp, color = Pastel.inkSoft)
        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth().testTag("nameField"), singleLine = true, label = { Text("Dein Name") })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({ onConfirm(name) }, Modifier.testTag("nameOk")) { Text("Übernehmen") }
        }
    }
}
