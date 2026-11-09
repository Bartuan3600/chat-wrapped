package de.bartuan.chatwrapped.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bartuan.chatwrapped.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun HomeScreen(saved: List<SavedChat>, busy: Boolean, error: String?, onPick: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    var confirm by remember { mutableStateOf<SavedChat?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(Pastel.bg).testTag("home"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFFFFD6E0), Color(0xFFD9D2FF)))).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🎁", fontSize = 44.sp)
                Text("Chat Wrapped", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Pastel.ink)
                Text("Dein WhatsApp-Chat als Jahresrückblick. Statistiken offline – auf Wunsch eine komplette Analyse durch APK Builder.", fontSize = 16.sp, color = Pastel.ink)
                Spacer(Modifier.height(4.dp))
                Button(onPick, Modifier.fillMaxWidth().height(54.dp), enabled = !busy, shape = RoundedCornerShape(18.dp)) {
                    Text(if (busy) "Wird analysiert …" else "Chat-Export auswählen (.txt / .zip)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (error != null) item {
            Text(error, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFFFE0E0)).padding(16.dp), color = Color(0xFF8A1C1C))
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("So geht's", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Pastel.ink)
                listOf("Öffne in WhatsApp den Chat oder die Gruppe.", "Tippe auf ⋮ › Mehr › Chat exportieren (iPhone: Kontaktname › Chat exportieren).",
                    "Wähle „Ohne Medien“ (schneller) oder „Medien anhängen“.", "Teile den Export mit „Chat Wrapped“ – oder speichere ihn und wähle ihn oben aus.")
                    .forEachIndexed { i, t -> Text("${i + 1}. $t", color = Pastel.ink, fontSize = 15.sp) }
            }
        }
        if (saved.isNotEmpty()) item { Text("Gespeicherte Analysen", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Pastel.ink) }
        items(saved, key = { it.id }) { c ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).clickable { onOpen(c.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Pastel.ink, maxLines = 1)
                    Text("${fmtInt(c.messages)} Nachrichten · ${c.people.size} Personen · ${SimpleDateFormat("dd.MM.yyyy", DE).format(Date(c.importedAt))}", fontSize = 13.sp, color = Pastel.inkSoft)
                }
                TextButton({ confirm = c }) { Text("Löschen", color = Color(0xFFB3261E)) }
            }
        }
        item { Text("🔒 Statistiken entstehen offline auf dem Handy. Nur wenn du „Ganzen Chat analysieren lassen“ bestätigst, wird der Chat verschlüsselt gesendet. Alles lässt sich hier löschen.", fontSize = 13.sp, color = Pastel.inkSoft) }
    }
    confirm?.let { c ->
        AlertDialog({ confirm = null }, confirmButton = { TextButton({ onDelete(c.id); confirm = null }) { Text("Löschen") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Abbrechen") } },
            title = { Text("Analyse löschen?") }, text = { Text("Chat-Text, Statistiken und KI-Fazit von „${c.title}“ werden von diesem Gerät entfernt.") })
    }
}

enum class ChatTab(val label: String) { STORY("Story"), GRID("Übersicht"), VERDICT("Analyse") }

@Composable
fun ChatScreen(
    title: String, stats: ChatStats, cards: List<StoryCard>, verdict: String?, onBack: () -> Unit,
    onShare: (String) -> Unit, onSaveVerdict: (String) -> Unit, onDeleteVerdict: () -> Unit,
    initialTab: ChatTab = ChatTab.STORY, initialPage: Int = 0,
    ana: AnalysisUi = remember { AnalysisUi() }, onSavePdf: () -> Unit = {}, onSharePdf: () -> Unit = {}, onAnalyze: () -> Unit = {}, onRetry: () -> Unit = {}, onCancel: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(initialTab) }
    var page by remember { mutableIntStateOf(initialPage) }
    Column(Modifier.fillMaxSize().background(Pastel.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("‹ Zurück", color = Pastel.ink) }
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Pastel.ink, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(50)).background(Color.White).padding(4.dp)) {
            ChatTab.values().forEach { t ->
                val sel = t == tab
                Text(t.label, Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (sel) Pastel.accent else Color.Transparent).clickable { tab = t }.padding(vertical = 10.dp),
                    color = if (sel) Color.White else Pastel.ink, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            ChatTab.STORY -> StoryPager(cards, page) { page = it }
            ChatTab.GRID -> CardGrid(cards) { page = it; tab = ChatTab.STORY }
            ChatTab.VERDICT -> VerdictScreen(stats, verdict, onDeleteVerdict, ana, onAnalyze, onRetry, onCancel, onSavePdf, onSharePdf)
        }
    }
}

@Composable
fun StoryPager(cards: List<StoryCard>, start: Int, onPage: (Int) -> Unit) {
    val state = rememberPagerState(initialPage = start.coerceIn(0, cards.size - 1)) { cards.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.currentPage) { onPage(state.currentPage) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            cards.indices.forEach { i -> Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= state.currentPage) Pastel.accent else Color(0x33000000))) }
        }
        HorizontalPager(state, Modifier.fillMaxSize().testTag("pager"), contentPadding = PaddingValues(16.dp), pageSpacing = 12.dp) { i ->
            Box(Modifier.fillMaxSize()) {
                CardFrame(cards[i], i, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                Row(Modifier.matchParentSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { if (i > 0) scope.launch { state.animateScrollToPage(i - 1) } })
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { if (i < cards.size - 1) scope.launch { state.animateScrollToPage(i + 1) } })
                }
            }
        }
    }
}

@Composable
fun CardGrid(cards: List<StoryCard>, onOpen: (Int) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().testTag("grid"), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(cards) { i, c ->
            val (a, b) = Pastel.gradients[i % Pastel.gradients.size]
            Column(Modifier.height(150.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(a, b))).clickable { onOpen(i) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.emoji, fontSize = 28.sp)
                Text(c.title.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = Pastel.inkSoft, maxLines = 1)
                Text(c.headline, fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black, color = Pastel.ink, maxLines = 3)
            }
        }
    }
}

@Composable
fun VerdictScreen(stats: ChatStats, saved: String?, onDelete: () -> Unit, ana: AnalysisUi, onAnalyze: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit,
                  onSavePdf: () -> Unit = {}, onSharePdf: () -> Unit = {}) {
    val sections = remember(saved) { saved?.let { VerdictParser.parse(it) } ?: emptyList() }
    val quick = remember(stats) { QuickVerdict.build(stats) }
    val people = remember(stats) { stats.ranked.map { it.name } }
    LazyColumn(Modifier.fillMaxSize().testTag("verdict"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { AnalysisPanel(ana, onAnalyze, onRetry, onCancel, onSavePdf, onSharePdf) }
        ana.result?.takeIf { it.status != "error" }?.let { analysisCards(it, people) }
        item {
            CardFrame(StoryCard("quick", "🧠", "Schnell-Fazit (offline)", "Das sagen die Zahlen") { quick.forEach { Text("• $it", fontSize = 16.sp, color = Pastel.ink) } }, 1, Modifier.fillMaxWidth())
        }
        if (sections.isNotEmpty()) {
            item { Text("Älteres KI-Fazit (eingefügt)", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Pastel.ink) }
            sections.forEachIndexed { i, sec ->
                item(key = "old_" + sec.title) {
                    CardFrame(StoryCard(sec.title, "✨", "KI-Fazit", sec.title) {
                        Text(sec.body.replace("**", ""), fontSize = 16.sp, lineHeight = 22.sp, color = Pastel.ink)
                    }, i + 2, Modifier.fillMaxWidth())
                }
            }
            item { TextButton(onDelete) { Text("Älteres KI-Fazit löschen", color = Color(0xFFB3261E)) } }
        }
    }
}
