package dev.amrakshay.atvremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.amrakshay.atvremote.ui.AppsSheet
import dev.amrakshay.atvremote.ui.AtvColors
import dev.amrakshay.atvremote.ui.AtvRemoteTheme
import dev.amrakshay.atvremote.ui.DiscoverScreen
import dev.amrakshay.atvremote.ui.KeyboardSheet
import dev.amrakshay.atvremote.ui.PairingScreen
import dev.amrakshay.atvremote.ui.RemoteScreen
import dev.amrakshay.atvremote.ui.toTile
import remote.Remotemessage.RemoteKeyCode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AtvRemoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AtvColors.Background,
                ) {
                    App()
                }
            }
        }
    }
}

private enum class Sheet { Keyboard, Apps }

@Composable
private fun App(viewModel: RemoteViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val currentApp by viewModel.currentApp.collectAsStateWithLifecycle()
    val device by viewModel.device.collectAsStateWithLifecycle()
    val textField by viewModel.textField.collectAsStateWithLifecycle()
    val imeReady by viewModel.imeReady.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    when (val s = state) {
        is UiState.Starting -> Centered { CircularProgressIndicator(color = AtvColors.Accent) }

        is UiState.Discovering -> DiscoverScreen(
            tvs = s.tvs,
            paired = s.paired,
            error = s.error,
            onChoose = viewModel::choose,
            onConnect = viewModel::connect,
            onManualConnect = viewModel::connectManually,
        )

        is UiState.Pairing -> PairingScreen(
            tv = s.tv,
            code = s.code,
            busy = s.busy,
            error = s.error,
            onCodeChanged = viewModel::onCodeChanged,
            onSubmit = viewModel::submitCode,
            onBack = viewModel::startDiscovery,
        )

        is UiState.Authorising -> AuthorisingScreen(
            title = s.title,
            host = s.host,
            onSwitchTv = viewModel::startDiscovery,
        )

        is UiState.Connected -> {
            RemoteScreen(
                tvName = s.tv.title,
                device = device,
                volume = volume,
                currentApp = currentApp,
                onKey = viewModel::key,
                onHold = viewModel::holdKey,
                onRelease = viewModel::releaseKey,
                onType = { sheet = Sheet.Keyboard },
                onApps = { sheet = Sheet.Apps },
                onSwitchTv = viewModel::startDiscovery,
            )
            when (sheet) {
                Sheet.Keyboard -> KeyboardSheet(
                    tvText = textField,
                    currentApp = currentApp,
                    imeReady = imeReady,
                    replacesText = capabilities?.replacesFieldText ?: true,
                    announcesFields = capabilities?.announcesTextFields ?: true,
                    onSend = viewModel::sendText,
                    onBackspace = { viewModel.key(RemoteKeyCode.KEYCODE_DEL) },
                    onDismiss = { sheet = null },
                )

                Sheet.Apps -> AppsSheet(
                    currentApp = currentApp,
                    apps = apps.map { it.toTile() },
                    // Close on launch: leaving it open hides the remote behind a
                    // sheet you have just finished with, and swallows the next tap.
                    onLaunch = { viewModel.launchApp(it.link); sheet = null },
                    onDismiss = { sheet = null },
                )

                null -> Unit
            }
        }

        is UiState.Unreachable -> UnreachableScreen(
            tvName = s.tv.title,
            reason = s.reason,
            onRetry = viewModel::retry,
            onSwitchTv = viewModel::startDiscovery,
        )
    }
}

/**
 * adbd will not talk to a key it has not been told about, and says so only on
 * the device's own screen. Nothing we can do from here moves it along, so the
 * screen says where to look.
 */
@Composable
private fun AuthorisingScreen(title: String, host: String, onSwitchTv: () -> Unit) {
    Centered {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            CircularProgressIndicator(color = AtvColors.Accent)
            Text(
                "Allow this phone on $title",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = AtvColors.Text,
            )
            Text(
                "The device at $host is asking whether to trust this phone. Accept the " +
                    "\"Allow debugging?\" box on its screen, and tick \"always allow\" so " +
                    "it stops asking.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = AtvColors.Muted,
            )
            Text(
                "Choose another device",
                fontSize = 13.sp,
                color = AtvColors.Muted,
                modifier = Modifier.clickable(onClick = onSwitchTv).padding(8.dp),
            )
        }
    }
}

/**
 * This TV leaves the network completely when it sleeps and cannot be woken over
 * it — see docs/PLAN.md §4.4 — so saying so plainly beats a Power button that does
 * nothing.
 */
@Composable
private fun UnreachableScreen(
    tvName: String,
    reason: String,
    onRetry: () -> Unit,
    onSwitchTv: () -> Unit,
) {
    Centered {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(tvName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = AtvColors.Text)
            Text(
                "TV is asleep — turn it on with the physical remote.",
                fontSize = 14.sp,
                color = AtvColors.Muted,
            )
            Text(reason, fontSize = 12.sp, color = AtvColors.Faint)
            Text(
                "Try again",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = AtvColors.OnAccent,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(AtvColors.Accent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Text(
                "Choose another TV",
                fontSize = 13.sp,
                color = AtvColors.Muted,
                modifier = Modifier.clickable(onClick = onSwitchTv).padding(8.dp),
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(AtvColors.Background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
