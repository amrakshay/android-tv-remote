package dev.amrakshay.atvremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark by default and not switchable: this is used in a dark room, at arm's
 * length, often without looking at it.
 */
object AtvColors {
    val Background = Color(0xFF0B0B0F)
    val Surface = Color(0xFF17171D)
    val SurfaceDim = Color(0xFF121218)
    val SurfaceBright = Color(0xFF22222B)
    val Pad = Color(0xFF15151B)
    val Accent = Color(0xFF7C8CFF)
    val OnAccent = Color(0xFF0B0B0F)
    val Text = Color(0xFFF2F2F5)
    val TextDim = Color(0xFFC9C9D6)
    val Muted = Color(0xFF9A9AAB)
    val Faint = Color(0xFF6E6E80)
    val Online = Color(0xFF4ADE80)
    val DangerSurface = Color(0xFF241519)
    val Danger = Color(0xFFFF8A8A)
    val Divider = Color(0xFF1E1E26)
    val Track = Color(0xFF2B2B36)
}

@Composable
fun AtvRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AtvColors.Accent,
            onPrimary = AtvColors.OnAccent,
            background = AtvColors.Background,
            onBackground = AtvColors.Text,
            surface = AtvColors.Surface,
            onSurface = AtvColors.Text,
            surfaceVariant = AtvColors.SurfaceBright,
            onSurfaceVariant = AtvColors.TextDim,
            error = AtvColors.Danger,
        ),
        typography = Typography(),
        content = content,
    )
}
