package dev.amrakshay.atvremote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.amrakshay.atvremote.data.SavedTv
import dev.amrakshay.atvremote.data.TvStore
import dev.amrakshay.atvremote.discovery.DiscoveredTv
import dev.amrakshay.atvremote.discovery.TvDiscovery
import dev.amrakshay.atvremote.protocol.ClientIdentity
import dev.amrakshay.atvremote.protocol.DeviceInfo
import dev.amrakshay.atvremote.protocol.InvalidPairingCode
import dev.amrakshay.atvremote.protocol.PairingSession
import dev.amrakshay.atvremote.protocol.RemoteSession
import dev.amrakshay.atvremote.protocol.VolumeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteKeyCode

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

    private lateinit var identity: ClientIdentity
    private var session: RemoteSession? = null
    private var pairingSession: PairingSession? = null
    private var discoveryJob: Job? = null

    init {
        viewModelScope.launch {
            identity = store.identity()
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
                    val saved = SavedTv(current.tv.serviceName, current.tv.host, current.tv.port)
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
        viewModelScope.launch {
            runCatching {
                RemoteSession(tv.host, identity).also { s ->
                    session = s
                    s.connect(viewModelScope)
                    launch { s.volume.collect { _volume.value = it } }
                    launch { s.currentApp.collect { _currentApp.value = it } }
                    launch { s.device.collect { _device.value = it } }
                    launch { s.textField.collect { _textField.value = it } }
                    launch { s.imeReady.collect { _imeReady.value = it } }
                    launch {
                        s.disconnects.collect {
                            _state.value = UiState.Unreachable(tv, it)
                        }
                    }
                }
            }.onSuccess {
                _state.value = UiState.Connected(tv)
            }.onFailure { e ->
                _state.value = UiState.Unreachable(tv, e.friendly())
            }
        }
    }

    fun retry() {
        (_state.value as? UiState.Unreachable)?.let { connect(it.tv) }
    }

    fun forgetAndRediscover() {
        viewModelScope.launch {
            val current = _state.value
            val name = when (current) {
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

    /** Connect to a typed-in address, for networks where mDNS is blocked. */
    fun connectManually(host: String) {
        val cleaned = host.trim()
        if (cleaned.isEmpty()) return
        val tv = SavedTv(serviceName = cleaned, host = cleaned, port = 6466)
        viewModelScope.launch {
            _state.value = UiState.Pairing(
                DiscoveredTv(cleaned, cleaned, 6466), busy = true,
            )
            // An address we have paired with before connects straight away;
            // otherwise it needs the usual code.
            val known = store.pairedTvs().any { it.host == cleaned }
            if (known) connect(tv) else choose(DiscoveredTv(cleaned, cleaned, 6466))
        }
    }

    fun key(code: RemoteKeyCode) {
        send("key $code") { sendKey(code) }
    }

    fun sendText(text: String) {
        send("text") { sendText(text) }
    }

    fun launchApp(appLink: String) {
        send("launch $appLink") { launchApp(appLink) }
    }

    fun holdKey(code: RemoteKeyCode) {
        send("hold $code") { sendKey(code, RemoteDirection.START_LONG) }
    }

    fun releaseKey(code: RemoteKeyCode) {
        send("release $code") { sendKey(code, RemoteDirection.END_LONG) }
    }

    /**
     * Failures used to be swallowed by a bare runCatching, which hid every key
     * press going nowhere for an afternoon. Log them.
     */
    private inline fun send(what: String, block: RemoteSession.() -> Unit) {
        val s = session
        if (s == null) {
            android.util.Log.w("RemoteViewModel", "dropped: $what (no session)")
            return
        }
        runCatching { s.block() }
            .onFailure { android.util.Log.w("RemoteViewModel", "failed: $what", it) }
    }

    override fun onCleared() {
        session?.close()
        pairingSession?.close()
        super.onCleared()
    }

    private fun Throwable.friendly(): String = when (this) {
        is InvalidPairingCode -> message ?: "that code does not look right"
        is java.net.NoRouteToHostException,
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        -> "TV is asleep or off the network"
        else -> message ?: this::class.simpleName ?: "something went wrong"
    }
}
