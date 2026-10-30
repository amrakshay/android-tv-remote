package dev.amrakshay.atvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amrakshay.atvremote.protocol.RemoteApp

/**
 * One tile in the Apps sheet.
 *
 * Over ADB the device lists what it actually has, so these are real. The
 * native protocol has no way to ask, so [DEFAULT_APPS] below is all we have
 * there, and it is guesswork.
 */
data class TvApp(val label: String, val initial: String, val link: String, val tint: Color)

private val APP_TINTS = listOf(
    Color(0xFFFF6B6B), Color(0xFF5AB9F5), Color(0xFF4ADE80),
    Color(0xFFF5C84B), Color(0xFFC084FC), Color(0xFF38BDF8),
)

/** Stable per app, so a tile keeps its colour between sessions. */
fun tintFor(key: String): Color =
    APP_TINTS[((key.hashCode() % APP_TINTS.size) + APP_TINTS.size) % APP_TINTS.size]

fun RemoteApp.toTile(): TvApp =
    TvApp(label, label.take(1).uppercase(), target, tintFor(target))

val DEFAULT_APPS = listOf(
    TvApp("YouTube", "Y", "https://www.youtube.com", Color(0xFFFF6B6B)),
    TvApp("Netflix", "N", "https://www.netflix.com", Color(0xFFFF5C7A)),
    TvApp("Prime Video", "P", "https://app.primevideo.com", Color(0xFF5AB9F5)),
    TvApp("Hotstar", "H", "https://www.hotstar.com", Color(0xFF4ADE80)),
    TvApp("Spotify", "S", "spotify://", Color(0xFFF5C84B)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSheet(
    tvText: String,
    currentApp: String,
    imeReady: Boolean,
    /** False over ADB: text is typed at the cursor rather than replacing the field. */
    replacesText: Boolean,
    /** False over ADB: nothing tells us whether a field is focused. */
    announcesFields: Boolean,
    onSend: (String) -> Unit,
    onBackspace: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(tvText) }

    // The TV is the source of truth: if it reports a different value (the user
    // typed on the TV, or switched fields), follow it.
    LaunchedEffect(tvText) { if (tvText != draft) draft = tvText }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AtvColors.Pad,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Type on TV",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AtvColors.Text,
                )
                Text(
                    when {
                        !announcesFields -> currentApp.ifBlank { "ready" }
                        imeReady -> currentApp.ifBlank { "ready" }
                        else -> "waiting for a text field"
                    },
                    fontSize = 12.sp,
                    color = if (imeReady) AtvColors.Online else AtvColors.Muted,
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Type here", color = AtvColors.Faint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend(draft) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AtvColors.Text,
                    unfocusedTextColor = AtvColors.Text,
                    focusedBorderColor = AtvColors.Accent,
                    unfocusedBorderColor = AtvColors.Track,
                    cursorColor = AtvColors.Accent,
                ),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (replacesText) {
                    Chip("Clear") { draft = ""; onSend("") }
                } else {
                    // Nothing can clear the field from here, so offer the one
                    // key that undoes a typo.
                    Chip("Backspace", onClick = onBackspace)
                }
                Chip("Send", filled = true) { onSend(draft) }
            }

            Text(
                when {
                    !announcesFields ->
                        "Typed straight into whatever is focused on the device — including " +
                            "apps that draw their own keyboard, which the TV protocol cannot " +
                            "reach. Text is added at the cursor rather than replacing the field."

                    imeReady -> "Each send replaces what the TV's focused field holds."

                    else ->
                        "No text field is listening. Open a search box on the TV first — and " +
                            "note that apps drawing their own keyboard (YouTube among them) " +
                            "never report one, so typing will not reach them."
                },
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = AtvColors.Faint,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsSheet(
    currentApp: String,
    /** What the device says it has; empty means we are guessing. */
    apps: List<TvApp>,
    onLaunch: (TvApp) -> Unit,
    onDismiss: () -> Unit,
) {
    val listed = apps.ifEmpty { DEFAULT_APPS }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AtvColors.Pad,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Apps",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AtvColors.Text,
            )

            if (currentApp.isNotBlank()) {
                Text("Open now: $currentApp", fontSize = 12.5.sp, color = AtvColors.Muted)
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.height(320.dp),
            ) {
                items(listed) { app ->
                    Column(
                        modifier = Modifier
                            .height(104.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(AtvColors.Surface)
                            .clickable { onLaunch(app) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(app.tint.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                app.initial,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = app.tint,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(app.label, fontSize = 12.sp, color = AtvColors.TextDim)
                    }
                }
            }

            Text(
                if (apps.isEmpty()) {
                    "These deep links are best guesses and not all of them will work — each " +
                        "one needs trying on this TV."
                } else {
                    "Read off the device, so every one of these is really installed."
                },
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = AtvColors.Faint,
            )
        }
    }
}

@Composable
private fun Chip(label: String, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (filled) AtvColors.Accent else AtvColors.SurfaceBright)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
            color = if (filled) AtvColors.OnAccent else AtvColors.TextDim,
        )
    }
}

@Composable
fun Spacer8() = Spacer(Modifier.width(8.dp))
