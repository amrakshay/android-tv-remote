package dev.amrakshay.atvremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.amrakshay.atvremote.data.SavedTv
import dev.amrakshay.atvremote.data.TvStore
import dev.amrakshay.atvremote.discovery.DiscoveredTv
import dev.amrakshay.atvremote.discovery.TvDiscovery
import dev.amrakshay.atvremote.protocol.AdbRemote
import dev.amrakshay.atvremote.protocol.Capabilities
import dev.amrakshay.atvremote.protocol.ClientIdentity
import dev.amrakshay.atvremote.protocol.DeviceInfo
import dev.amrakshay.atvremote.protocol.InvalidPairingCode
import dev.amrakshay.atvremote.protocol.PairingSession
import dev.amrakshay.atvremote.protocol.Remote
import dev.amrakshay.atvremote.protocol.RemoteApp
import dev.amrakshay.atvremote.protocol.RemoteSession
import dev.amrakshay.atvremote.protocol.TransportKind
import dev.amrakshay.atvremote.protocol.VolumeInfo
import dev.amrakshay.atvremote.protocol.adb.AdbConnection
import dev.amrakshay.atvremote.protocol.adb.AdbKey
import dev.amrakshay.atvremote.protocol.adb.AdbNotAuthorised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteKeyCode
import java.net.InetSocketAddress
import java.net.Socket

/** The TV's own remote service. */
private const val REMOTE_PORT = 6466

sealed interface UiState {
    data object Starting : UiState

    data class Discovering(
        val tvs: List<DiscoveredTv>,
        val paired: List<SavedTv>,
        val error: String? = null,
    ) : UiState

    data class Pairing(
        val tv: DiscoveredTv,
        val code: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    ) : UiState

    /**
     * An ADB device that has not seen this key before is showing its "Allow
     * debugging?" dialog, and nothing happens until someone accepts it.
     */
    data class Authorising(val title: String, val host: String) : UiState

    data class Connected(val tv: SavedTv) : UiState

    /** Reachable TVs sleep off the network entirely; see docs/PLAN.md §4.4. */
    data class Unreachable(val tv: SavedTv, val reason: String) : UiState
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val store = TvStore(app)
    private val discovery = TvDiscovery(app)

    private val _state = MutableStateFlow<UiState>(UiState.Starting)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _volume = MutableStateFlow<VolumeInfo?>(null)
    val volume: StateFlow<VolumeInfo?> = _volume.asStateFlow()

    private val _currentApp = MutableStateFlow("")
    val currentApp: StateFlow<String> = _currentApp.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _textField = MutableStateFlow("")
    val textField: StateFlow<String> = _textField.asStateFlow()

    private val _imeReady = MutableStateFlow(false)
    val imeReady: StateFlow<Boolean> = _imeReady.asStateFlow()

    private val _apps = MutableStateFlow<List<RemoteApp>>(emptyList())
    val apps: StateFlow<List<RemoteApp>> = _apps.asStateFlow()

    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

    private lateinit var identity: ClientIdentity
    private lateinit var adbKey: AdbKey
    private var session: Remote? = null
    private var pairingSession: PairingSession? = null
    private var discoveryJob: Job? = null

    init {
        viewModelScope.launch {
            identity = store.identity()
            adbKey = store.adbKey()
            val paired = store.pairedTvs()
            if (paired.isEmpty()) startDiscovery() else connect(paired.first())
        }
    }

    fun startDiscovery() {
        discoveryJob?.cancel()
        viewModelScope.launch {
            val paired = store.pairedTvs()
            _state.value = UiState.Discovering(emptyList(), paired)
            discoveryJob = viewModelScope.launch {
                discovery.discover()
                    .catch { e ->
                        _state.value = UiState.Discovering(
                            emptyList(), paired, e.message ?: "discovery failed",
                        )
                    }
                    .collect { tvs ->
                        val current = _state.value
                        if (current is UiState.Discovering) {
                            _state.value = current.copy(tvs = tvs)
                        }
                    }
            }
        }
    }

    fun choose(tv: DiscoveredTv) {
        discoveryJob?.cancel()
        // An ADB device has no code to type: adbd either knows our key already
        // or asks the person in front of the screen to approve it.
        if (tv.kind == TransportKind.Adb) {
            connect(tv.toSaved(label = tv.label))
            return
        }
        viewModelScope.launch {
            _state.value = UiState.Pairing(tv, busy = true)
            runCatching {
                PairingSession(tv.host, identity, clientName = android.os.Build.MODEL)
                    .also { pairingSession = it }
                    .start()
            }.onSuccess {
                _state.value = UiState.Pairing(tv)
            }.onFailure { e ->
                _state.value = UiState.Pairing(tv, error = e.friendly())
            }
        }
    }

    fun onCodeChanged(code: String) {
        val current = _state.value
        if (current is UiState.Pairing) {
            _state.value = current.copy(
                code = code.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6),
                error = null,
            )
        }
    }

    fun submitCode() {
        val current = _state.value as? UiState.Pairing ?: return
        val pairing = pairingSession ?: return
        viewModelScope.launch {
            _state.value = current.copy(busy = true)
            runCatching { pairing.finish(current.code) }
                .onSuccess {
                    pairing.close()
                    pairingSession = null
                    val saved = current.tv.toSaved()
                    store.remember(saved)
                    connect(saved)
                }
                .onFailure { e ->
                    _state.value = current.copy(busy = false, code = "", error = e.friendly())
                }
        }
    }

    fun connect(tv: SavedTv) {
        session?.close()
        session = null
        resetDeviceState()
        viewModelScope.launch {
            runCatching {
                val remote = newRemote(tv)
                session = remote
                _capabilities.value = remote.capabilities
                remote.connect(viewModelScope)
                launch { remote.volume.collect { _volume.value = it } }
                launch { remote.currentApp.collect { _currentApp.value = it } }
                launch { remote.device.collect { _device.value = it } }
                launch { remote.textField.collect { _textField.value = it } }
                launch { remote.imeReady.collect { _imeReady.value = it } }
                launch { remote.apps.collect { _apps.value = it } }
                launch {
                    remote.disconnects.collect { _state.value = UiState.Unreachable(tv, it) }
                }
                remote
            }.onSuccess { remote ->
                // Only now do we know what an ADB device is called, so the saved
                // entry is relabelled from "Android device" to its model. A native
                // TV keeps the name it advertised, which is one a person chose.
                val model = remote.device.value?.model.orEmpty()
                val named = if (tv.kind == TransportKind.Adb && model.isNotBlank()) {
                    tv.copy(label = model)
                } else {
                    tv
                }
                store.remember(named)
                _state.value = UiState.Connected(named)
            }.onFailure { e ->
                session?.close()
                session = null
                _state.value = UiState.Unreachable(tv, e.friendly())
            }
        }
    }

    private fun newRemote(tv: SavedTv): Remote = when (tv.kind) {
        TransportKind.Native -> RemoteSession(tv.host, identity)
        TransportKind.Adb -> AdbRemote(tv.host, adbKey, tv.port).also { remote ->
            viewModelScope.launch {
                remote.awaitingAuthorisation.collect { waiting ->
                    if (waiting) _state.value = UiState.Authorising(tv.title, tv.host)
                }
            }
        }
    }

    fun retry() {
        (_state.value as? UiState.Unreachable)?.let { connect(it.tv) }
    }

    fun forgetAndRediscover() {
        viewModelScope.launch {
            val name = when (val current = _state.value) {
                is UiState.Connected -> current.tv.serviceName
                is UiState.Unreachable -> current.tv.serviceName
                else -> null
            }
            name?.let { store.forget(it) }
            session?.close()
            session = null
            startDiscovery()
        }
    }

    /**
     * Connect to a typed-in address, for networks where mDNS is blocked.
     *
     * Which transport is not something a person should have to know, so the
     * ports are probed: the remote service first, then adbd.
     */
    fun connectManually(host: String) {
        val cleaned = host.trim()
        if (cleaned.isEmpty()) return
        viewModelScope.launch {
            _state.value = UiState.Discovering(emptyList(), store.pairedTvs())
            when (probe(cleaned)) {
                TransportKind.Native -> {
                    val known = store.pairedTvs().any {
                        it.host == cleaned && it.kind == TransportKind.Native
                    }
                    val tv = DiscoveredTv(cleaned, cleaned, REMOTE_PORT, TransportKind.Native)
                    if (known) connect(tv.toSaved(label = "")) else choose(tv)
                }

                TransportKind.Adb -> choose(
                    DiscoveredTv(cleaned, cleaned, AdbConnection.ADB_PORT, TransportKind.Adb),
                )

                null -> _state.value = UiState.Discovering(
                    emptyList(),
                    store.pairedTvs(),
                    "Nothing answered at $cleaned — check it is awake and on this Wi-Fi.",
                )
            }
        }
    }

    private suspend fun probe(host: String): TransportKind? = withContext(Dispatchers.IO) {
        listOf(
            REMOTE_PORT to TransportKind.Native,
            AdbConnection.ADB_PORT to TransportKind.Adb,
        ).firstOrNull { (port, _) ->
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
            }.isSuccess
        }?.second
    }

    fun key(code: RemoteKeyCode) = send("key $code") { sendKey(code) }

    fun sendText(text: String) = send("text") { sendText(text) }

    fun launchApp(target: String) = send("launch $target") { launchApp(target) }

    fun holdKey(code: RemoteKeyCode) =
        send("hold $code") { sendKey(code, RemoteDirection.START_LONG) }

    fun releaseKey(code: RemoteKeyCode) =
        send("release $code") { sendKey(code, RemoteDirection.END_LONG) }

    /**
     * Failures used to be swallowed by a bare runCatching, which hid every key
     * press going nowhere for an afternoon. Log them.
     */
    private inline fun send(what: String, block: Remote.() -> Unit) {
        val s = session
        if (s == null) {
            android.util.Log.w("RemoteViewModel", "dropped: $what (no session)")
            return
        }
        runCatching { s.block() }
            .onFailure { android.util.Log.w("RemoteViewModel", "failed: $what", it) }
    }

    private fun resetDeviceState() {
        _volume.value = null
        _currentApp.value = ""
        _device.value = null
        _textField.value = ""
        _imeReady.value = false
        _apps.value = emptyList()
    }

    override fun onCleared() {
        session?.close()
        pairingSession?.close()
        super.onCleared()
    }

    private fun DiscoveredTv.toSaved(label: String = "") =
        SavedTv(serviceName, host, port, kind, label)

    private fun Throwable.friendly(): String = when (this) {
        is InvalidPairingCode -> message ?: "that code does not look right"
        is AdbNotAuthorised -> message ?: "the device did not accept this phone"
        is java.net.NoRouteToHostException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        -> "TV is asleep or off the network"
        else -> message ?: this::class.simpleName ?: "something went wrong"
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 2_000
    }
}
