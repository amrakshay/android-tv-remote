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

/**
 * Deep links for the apps on this TV. Best-effort and **unverified** — Android TV
 * apps vary in which link scheme they answer to, so each one needs trying on the
 * real set. docs/PLAN.md §10 has the open question of curating this by hand versus
 * reading the installed list off the TV over ADB.
 */
data class TvApp(val label: String, val initial: String, val link: String, val tint: Color)

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
    onSend: (String) -> Unit,
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
                    if (imeReady) currentApp.ifBlank { "ready" } else "waiting for a text field",
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
                Chip("Clear") { draft = ""; onSend("") }
                Chip("Send", filled = true) { onSend(draft) }
            }

            Text(
                if (imeReady) {
                    "Each send replaces what the TV's focused field holds."
                } else {
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
    onLaunch: (TvApp) -> Unit,
    onDismiss: () -> Unit,
) {
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
                modifier = Modifier.height(240.dp),
            ) {
                items(DEFAULT_APPS) { app ->
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
                "These deep links are best guesses and not all of them will work — each " +
                    "one needs trying on this TV.",
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
