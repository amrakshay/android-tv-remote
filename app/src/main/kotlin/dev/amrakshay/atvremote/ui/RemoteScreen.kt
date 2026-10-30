package dev.amrakshay.atvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import dev.amrakshay.atvremote.protocol.DeviceInfo
import dev.amrakshay.atvremote.protocol.VolumeInfo
import remote.Remotemessage.RemoteKeyCode

@Composable
fun RemoteScreen(
    tvName: String,
    device: DeviceInfo?,
    volume: VolumeInfo?,
    currentApp: String,
    onKey: (RemoteKeyCode) -> Unit,
    onHold: (RemoteKeyCode) -> Unit,
    onRelease: (RemoteKeyCode) -> Unit,
    onType: () -> Unit,
    onApps: () -> Unit,
    onSwitchTv: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val press: (RemoteKeyCode) -> Unit = { code ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onKey(code)
    }
    val hold: (RemoteKeyCode) -> Unit = { code ->
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onHold(code)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtvColors.Background)
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(tvName, device, currentApp, onSwitchTv)

        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PillButton("Power", Icons.Default.PowerSettingsNew, AtvColors.DangerSurface, AtvColors.Danger) {
                press(RemoteKeyCode.KEYCODE_POWER)
            }
            PillButton("Input", Icons.Default.Input) { press(RemoteKeyCode.KEYCODE_TV_INPUT) }
            PillButton("Mute", Icons.AutoMirrored.Filled.VolumeOff) {
                press(RemoteKeyCode.KEYCODE_VOLUME_MUTE)
            }
        }

        // Takes whatever vertical space is left, so the layout survives short screens.
        DPad(Modifier.weight(1f), press, hold, onRelease)

        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Back in the middle, under the thumb — it is the most-pressed of
            // the three and the hardest to hit blind at either edge.
            PillButton("Search", Icons.Default.Search, small = true) {
                press(RemoteKeyCode.KEYCODE_SEARCH)
            }
            PillButton("Back", Icons.Default.ArrowBack, small = true) {
                press(RemoteKeyCode.KEYCODE_BACK)
            }
            PillButton("Home", Icons.Default.Home, small = true) {
                press(RemoteKeyCode.KEYCODE_HOME)
            }
        }

        // Tall on purpose: the space freed by the smaller D-pad comes here, so
        // + and − are the easiest things to hit without looking at the screen.
        Row(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VolumeRocker(volume, Modifier.weight(1f), press, hold, onRelease)
            Rocker(
                label = "CH",
                modifier = Modifier.width(100.dp),
                onUp = { press(RemoteKeyCode.KEYCODE_CHANNEL_UP) },
                onDown = { press(RemoteKeyCode.KEYCODE_CHANNEL_DOWN) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediaButton(Icons.Default.FastRewind, "Rewind", Modifier.weight(1f)) {
                press(RemoteKeyCode.KEYCODE_MEDIA_REWIND)
            }
            MediaButton(
                Icons.Default.PlayArrow, "Play or pause",
                Modifier.weight(1.4f), AtvColors.SurfaceBright,
            ) { press(RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE) }
            MediaButton(Icons.Default.FastForward, "Fast forward", Modifier.weight(1f)) {
                press(RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomAction("Type", Icons.Default.Keyboard, onType)
            BottomAction("Apps", Icons.Default.Apps, onApps)
            BottomAction("TVs", Icons.Default.Tv, onSwitchTv)
        }
    }
}

private const val HOLD_THRESHOLD_MS = 350L

/**
 * A tap sends SHORT; holding past [HOLD_THRESHOLD_MS] switches to the protocol's
 * START_LONG / END_LONG pair so the TV does the repeating itself.
 *
 * Sending START_LONG + END_LONG for *every* press looks equivalent but is not:
 * the TV ignores the pair when they arrive back to back, so a quick tap did
 * nothing at all. Found on the device — three taps on volume left it unchanged.
 * END_LONG is still sent when the gesture is cancelled, or the key sticks down.
 */
private fun Modifier.repeatable(
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    detectTapGestures(
        onPress = {
            val releasedQuickly = withTimeoutOrNull(HOLD_THRESHOLD_MS) { tryAwaitRelease() }
            if (releasedQuickly != null) {
                onTap()
            } else {
                onHoldStart()
                tryAwaitRelease()
                onHoldEnd()
            }
        },
    )
}

@Composable
private fun Header(
    tvName: String,
    device: DeviceInfo?,
    currentApp: String,
    onSwitchTv: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AtvColors.SurfaceBright),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Tv, null, tint = AtvColors.Accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tvName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AtvColors.Text)
            Spacer(Modifier.size(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(AtvColors.Online))
                Spacer(Modifier.width(6.dp))
                Text(
                    currentApp.ifBlank { device?.model ?: "connected" },
                    fontSize = 13.sp,
                    color = AtvColors.Muted,
                )
            }
        }
        Text(
            "Switch",
            fontSize = 13.sp,
            color = AtvColors.Accent,
            modifier = Modifier.clickable(onClick = onSwitchTv).padding(8.dp),
        )
    }
}

@Composable
private fun ColumnScope.DPad(
    modifier: Modifier,
    onPress: (RemoteKeyCode) -> Unit,
    onHold: (RemoteKeyCode) -> Unit,
    onRelease: (RemoteKeyCode) -> Unit,
) {
    // The outer box takes the slack so the pad stays centred on a tall screen;
    // the ring itself is capped at the mock's proportion (236 of a 350 content
    // width) rather than filling, which made it dominate everything else.
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(AtvColors.Pad),
        ) {
            Arrow(Icons.Default.KeyboardArrowUp, "Up", Alignment.TopCenter,
                { onPress(RemoteKeyCode.KEYCODE_DPAD_UP) },
                { onHold(RemoteKeyCode.KEYCODE_DPAD_UP) },
                { onRelease(RemoteKeyCode.KEYCODE_DPAD_UP) })
            Arrow(Icons.Default.KeyboardArrowDown, "Down", Alignment.BottomCenter,
                { onPress(RemoteKeyCode.KEYCODE_DPAD_DOWN) },
                { onHold(RemoteKeyCode.KEYCODE_DPAD_DOWN) },
                { onRelease(RemoteKeyCode.KEYCODE_DPAD_DOWN) })
            Arrow(Icons.Default.KeyboardArrowLeft, "Left", Alignment.CenterStart,
                { onPress(RemoteKeyCode.KEYCODE_DPAD_LEFT) },
                { onHold(RemoteKeyCode.KEYCODE_DPAD_LEFT) },
                { onRelease(RemoteKeyCode.KEYCODE_DPAD_LEFT) })
            Arrow(Icons.Default.KeyboardArrowRight, "Right", Alignment.CenterEnd,
                { onPress(RemoteKeyCode.KEYCODE_DPAD_RIGHT) },
                { onHold(RemoteKeyCode.KEYCODE_DPAD_RIGHT) },
                { onRelease(RemoteKeyCode.KEYCODE_DPAD_RIGHT) })
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.39f)
                    .clip(CircleShape)
                    .background(AtvColors.Accent)
                    .clickable { onPress(RemoteKeyCode.KEYCODE_DPAD_CENTER) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "OK",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = AtvColors.OnAccent,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.Arrow(
    icon: ImageVector,
    label: String,
    alignment: Alignment,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .fillMaxSize(0.34f)
            .clip(CircleShape)
            .repeatable(onTap, onHoldStart, onHoldEnd),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = AtvColors.TextDim, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun RowScope.PillButton(
    label: String,
    icon: ImageVector,
    background: Color = AtvColors.Surface,
    tint: Color = AtvColors.TextDim,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .clip(RoundedCornerShape(if (small) 14.dp else 18.dp))
            .background(background)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(if (small) 17.dp else 18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = if (small) 13.sp else 14.sp, color = tint)
    }
}

@Composable
private fun RowScope.BottomAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = AtvColors.Muted, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, color = AtvColors.Muted)
    }
}

@Composable
private fun VolumeRocker(
    volume: VolumeInfo?,
    modifier: Modifier,
    onPress: (RemoteKeyCode) -> Unit,
    onHold: (RemoteKeyCode) -> Unit,
    onRelease: (RemoteKeyCode) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(AtvColors.Surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RockerHalf(
            Icons.Default.Add, "Volume up", Modifier.weight(1f),
            { onPress(RemoteKeyCode.KEYCODE_VOLUME_UP) },
            { onHold(RemoteKeyCode.KEYCODE_VOLUME_UP) },
            { onRelease(RemoteKeyCode.KEYCODE_VOLUME_UP) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("VOL", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AtvColors.Muted)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(AtvColors.Track),
            ) {
                // The TV's own number, so it moves when the physical remote is used.
                Box(
                    modifier = Modifier
                        .fillMaxWidth(volume?.fraction ?: 0f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(AtvColors.Accent),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                volume?.level?.toString() ?: "–",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AtvColors.TextDim,
            )
        }
        RockerHalf(
            Icons.Default.Remove, "Volume down", Modifier.weight(1f),
            { onPress(RemoteKeyCode.KEYCODE_VOLUME_DOWN) },
            { onHold(RemoteKeyCode.KEYCODE_VOLUME_DOWN) },
            { onRelease(RemoteKeyCode.KEYCODE_VOLUME_DOWN) },
        )
    }
}

@Composable
private fun Rocker(
    label: String,
    modifier: Modifier,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(AtvColors.Surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().clickable(onClick = onUp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp, "$label up",
                tint = AtvColors.TextDim, modifier = Modifier.size(22.dp),
            )
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AtvColors.Muted)
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().clickable(onClick = onDown),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown, "$label down",
                tint = AtvColors.TextDim, modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun RockerHalf(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth().repeatable(onTap, onHoldStart, onHoldEnd),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = AtvColors.TextDim, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MediaButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    background: Color = AtvColors.Surface,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = AtvColors.TextDim, modifier = Modifier.size(22.dp))
    }
}
