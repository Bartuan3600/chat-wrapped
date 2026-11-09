package de.bartuan.chatwrapped.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Pastel {
    val bg = Color(0xFFF6EEFF)
    val ink = Color(0xFF2B2140)
    val inkSoft = Color(0xFF5F5577)
    val accent = Color(0xFF8E7BFF)
    val pink = Color(0xFFFF8FAB)
    val series = listOf(Color(0xFF8E7BFF), Color(0xFFFF8FAB), Color(0xFF5CC8B0), Color(0xFFFFB45E), Color(0xFF5FA8FF), Color(0xFFE48BE6), Color(0xFFA3C95A), Color(0xFFFF7A7A))
    val gradients = listOf(
        Color(0xFFFFD6E0) to Color(0xFFFFF1C9),
        Color(0xFFD9D2FF) to Color(0xFFFFD9F2),
        Color(0xFFC9F2E7) to Color(0xFFDDEBFF),
        Color(0xFFFFE3C7) to Color(0xFFFFCFD8),
        Color(0xFFE2F5C9) to Color(0xFFC9EEF5),
        Color(0xFFEAD7FF) to Color(0xFFC9E4FF),
        Color(0xFFFFF0B8) to Color(0xFFD5F5D0),
        Color(0xFFFFD2C2) to Color(0xFFE7D4FF),
    )
}

@Composable
fun WrappedTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Pastel.accent, onPrimary = Color.White, secondary = Pastel.pink,
            background = Pastel.bg, surface = Color.White, onBackground = Pastel.ink, onSurface = Pastel.ink,
            surfaceVariant = Color(0xFFEDE4FA), primaryContainer = Color(0xFFE3DCFF), onPrimaryContainer = Pastel.ink,
        ),
        content = content,
    )
}
