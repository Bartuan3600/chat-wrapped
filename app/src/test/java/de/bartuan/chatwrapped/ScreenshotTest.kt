@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
package de.bartuan.chatwrapped

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import de.bartuan.chatwrapped.ui.*
import org.junit.Rule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    private val stats by lazy { ChatStats(Parser.parse(File(System.getProperty("sampleChat")!!).readText())) }
    private val title = "Lena & Jonas"

    private fun snap(name: String, content: @Composable () -> Unit) {
        compose.setContent { WrappedTheme { Surface(Modifier.fillMaxSize(), color = Pastel.bg) { content() } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "$name.png").absolutePath)
    }

    private fun card(key: String): Int = buildCards(stats, title).indexOfFirst { it.key == key }.also { require(it >= 0) { key } }

    private fun story(name: String, key: String) = snap(name) {
        ChatScreen(title, stats, buildCards(stats, title), null, {}, {}, {}, {}, ChatTab.STORY, card(key))
    }

    @Test fun s01_import() = snap("01_import") {
        HomeScreen(listOf(SavedChat("x", "Lena & Jonas", 1_792_000_000_000, stats.total, listOf("Lena", "Jonas"), emptyMap()),
            SavedChat("y", "WG Küche 🍳", 1_790_000_000_000, 8213, listOf("A", "B", "C", "D"), emptyMap())), false, null, {}, {}, {})
    }
    @Test fun s02_share() = story("02_card_share", "share")
    @Test fun s03_reply() = story("03_card_reply", "reply")
    @Test fun s04_hours() = story("04_card_hours", "hours")
    @Test fun s05_words() = story("05_card_words", "words")
    @Test fun s06_heatmap() = story("06_card_heatmap", "heatmap")
    @Test fun s07_grid() = snap("07_overview_grid") { ChatScreen(title, stats, buildCards(stats, title), null, {}, {}, {}, {}, ChatTab.GRID) }
    private fun anaResult(): de.bartuan.chatwrapped.analysis.AnalysisResult =
        de.bartuan.chatwrapped.analysis.AnalysisResult.parse(javaClass.classLoader!!.getResource("sample_result.json")!!.readText())

    private fun anaShot(name: String, ui: AnalysisUi, scrollTo: Int = 0) {
        compose.setContent { WrappedTheme { Surface(Modifier.fillMaxSize(), color = Pastel.bg) { ChatScreen(title, stats, buildCards(stats, title), null, {}, {}, {}, {}, ChatTab.VERDICT, ana = ui) } } }
        compose.waitForIdle()
        if (scrollTo > 0) { compose.onNodeWithTag("verdict").performScrollToIndex(scrollTo); compose.waitForIdle() }
        compose.onRoot().captureRoboImage(File(dir, "$name.png").absolutePath)
    }
    @Test fun s10_consent() {
        compose.setContent { WrappedTheme { Surface(Modifier.fillMaxSize(), color = Pastel.bg) { ChatScreen(title, stats, buildCards(stats, title), null, {}, {}, {}, {}, ChatTab.VERDICT) } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "10_analysis_start.png").absolutePath)
        compose.onNodeWithTag("analyzeBtn").performClick(); compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage(File(dir, "11_consent.png").absolutePath)
    }
    @Test fun s12_sending() = anaShot("12_sending", AnalysisUi().apply {
        job = de.bartuan.chatwrapped.analysis.CwJob("x", "u", de.bartuan.chatwrapped.analysis.CwState.SENDING, 0)
        progress = de.bartuan.chatwrapped.analysis.Progress("Lade hoch: Teil 1 von 1", 61_000, 98_000) })
    @Test fun s13_waiting() = anaShot("13_waiting", AnalysisUi().apply {
        job = de.bartuan.chatwrapped.analysis.CwJob("x", "u", de.bartuan.chatwrapped.analysis.CwState.WAITING, 0, sentAt = System.currentTimeMillis()) })
    @Test fun s14_result() = anaShot("14_result_score", AnalysisUi().apply { result = anaResult() }, 1)
    @Test fun s15_result() = anaShot("15_result_people", AnalysisUi().apply { result = anaResult() }, 3)
    @Test fun s16_result() = anaShot("16_result_quotes", AnalysisUi().apply { result = anaResult() }, 18)
    @Test fun s17_result() = anaShot("17_result_timeline", AnalysisUi().apply { result = anaResult() }, 8)
    @Test fun s19_pdf_buttons() = anaShot("19_result_pdf_buttons", AnalysisUi().apply { result = anaResult() })
    @Test fun s20_name_dialog() = snap("20_name_dialog") {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            HomeScreen(emptyList(), false, null, {}, {}, {})
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0x66000000)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.foundation.layout.Box(Modifier.padding(24.dp)) { NameForm {} }
            }
        }
    }
    @Test fun s21_blank_named() {
        val raw = File(System.getProperty("sampleChat")!!).readText().replace(" - Lena: ", " -  : ")
        val st = ChatStats(Parser.withSelfName(Parser.parse(raw), "Alex"))
        compose.setContent { WrappedTheme { Surface(Modifier.fillMaxSize(), color = Pastel.bg) {
            val cards = buildCards(st, "Alex & Jonas")
            ChatScreen("Alex & Jonas", st, cards, null, {}, {}, {}, {}, ChatTab.STORY, cards.indexOfFirst { it.key == "share" }) } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "21_blank_sender_named.png").absolutePath)
    }
    @Test fun s18_starter() = story("18_card_starter_trend", "starter")
    @Test fun s09_quick() = story("09_card_quick", "quick")
}
