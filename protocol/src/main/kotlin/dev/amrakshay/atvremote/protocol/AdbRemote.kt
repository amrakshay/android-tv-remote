package dev.amrakshay.atvremote.protocol

import dev.amrakshay.atvremote.protocol.adb.AdbConnection
import dev.amrakshay.atvremote.protocol.adb.AdbKey
import dev.amrakshay.atvremote.protocol.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteKeyCode

/**
 * A remote for devices that have no remote service but do have adbd — plenty of
 * Android TV boxes and projectors, which ship a leanback image without
 * `com.google.android.tv.remote.service`. See issue #1.
 *
 * Two paths, because one is fast and one is clean:
 *
 *  - **Keys and text go through monkey's command port.** `input keyevent` costs
 *    about 300 ms on this class of hardware, which is far too slow to use as a
 *    remote; monkey holds a socket open and answers in about 70 ms, and its
 *    `key down` / `key up` map exactly onto press-and-hold.
 *  - **Everything else is a one-shot `shell:` stream.** Those cost the same
 *    300 ms, which does not matter for a two-second poll, and they end with the
 *    device closing the stream — so there is no prompt to strip, no echo to
 *    confuse the parse, and no marker protocol to get wrong.
 */
class AdbRemote(
    private val host: String,
    private val adbKey: AdbKey,
    private val port: Int = AdbConnection.ADB_PORT,
) : Remote {

    override val capabilities = Capabilities(
        kind = TransportKind.Adb,
        // adbd tells us nothing unasked; the state below is polled.
        pushesState = false,
        announcesTextFields = false,
        replacesFieldText = false,
        listsApps = true,
    )

    private val _volume = MutableStateFlow<VolumeInfo?>(null)
    override val volume: StateFlow<VolumeInfo?> = _volume.asStateFlow()

    private val _currentApp = MutableStateFlow("")
    override val currentApp: StateFlow<String> = _currentApp.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    override val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    /** adbd never reports a focused field, so this stays empty. */
    override val textField: StateFlow<String> = MutableStateFlow("").asStateFlow()

    /** Typing always goes somewhere — to whatever has focus on the device. */
    override val imeReady: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

    private val _apps = MutableStateFlow<List<RemoteApp>>(emptyList())
    override val apps: StateFlow<List<RemoteApp>> = _apps.asStateFlow()

    private val _disconnects = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val disconnects: SharedFlow<String> = _disconnects.asSharedFlow()

    /** Set while the device is showing its "Allow debugging?" dialog. */
    val awaitingAuthorisation = MutableStateFlow(false)

    private var connection: AdbConnection? = null
    private var monkey: Monkey? = null
    private var pollJob: Job? = null

    /**
     * Commands are serialised, for the same reason the native session serialises
     * its writes: monkey answers one line per command, and two in flight would
     * read each other's replies. One thread also keeps key presses in order,
     * which matters rather a lot for a D-pad.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val commands = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * State reads live off the command thread, so a volume bar catching up never
     * delays the next key press. [stateLock] keeps two of them from racing.
     */
    private val reads = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Mutex()

    override suspend fun connect(scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            val c = AdbConnection(host, port, adbKey)
            c.onAwaitingAuthorisation = { awaitingAuthorisation.value = true }
            c.onLost = { reason -> _disconnects.tryEmit(reason) }
            c.connect()
            awaitingAuthorisation.value = false
            connection = c

            _device.value = deviceFromBanner(c.banner)
            monkey = Monkey.start(c)

            val appsOutput = shell(APPS_COMMAND, c).orEmpty()
            _apps.value = parseApps(appsOutput)
            // The same round trip carries the Android version, which the banner
            // does not include.
            parseVersion(appsOutput).takeIf { it.isNotEmpty() }?.let { version ->
                _device.value = _device.value?.copy(version = version)
            }
        }
        pollJob = scope.launch(Dispatchers.IO) { pollLoop() }
    }

    override fun sendKey(key: RemoteKeyCode, direction: RemoteDirection) {
        // The remote protocol's key codes are Android's own key codes, so the
        // number carries straight across with no table in between.
        val code = key.number
        if (code <= 0) return
        commands.launch {
            val monkeyCommand = when (direction) {
                RemoteDirection.START_LONG -> "key down $code"
                RemoteDirection.END_LONG -> "key up $code"
                else -> "press $code"
            }
            if (monkey?.command(monkeyCommand) == true) {
                if (code in VOLUME_KEYS) refreshSoon()
                return@launch
            }
            // Without monkey, a hold is one long press and the release is a no-op.
            when (direction) {
                RemoteDirection.END_LONG -> Unit
                RemoteDirection.START_LONG -> shell("input keyevent --longpress $code")
                else -> shell("input keyevent $code")
            }
            if (code in VOLUME_KEYS) refreshSoon()
        }
    }

    /**
     * Nothing is pushed over adbd, so a volume press would otherwise leave the
     * bar showing the old level until the next poll — up to two seconds of the
     * UI contradicting what you just heard.
     */
    private fun refreshSoon() {
        reads.launch {
            delay(300)
            readState()
        }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        commands.launch {
            // monkey's `type` takes the rest of the line verbatim, so a newline
            // is the one character it cannot carry.
            val line = text.replace('\n', ' ')
            if (monkey?.command("type $line") == true) return@launch
            shell("input text ${singleQuote(line)}")
        }
    }

    /** A `package/activity` component, or anything else `am start` understands. */
    override fun launchApp(target: String) {
        commands.launch {
            val command = when {
                target.startsWith("http://") || target.startsWith("https://") || "://" in target ->
                    "am start -a android.intent.action.VIEW -d ${singleQuote(target)}"
                "/" in target -> "am start -n ${singleQuote(target)}"
                else -> "monkey -p ${singleQuote(target)} -c android.intent.category.LAUNCHER 1"
            }
            shell(command)
            // The foreground app is polled, so without this the header keeps
            // naming the launcher for a second after the app has opened.
            reads.launch {
                delay(800)
                readState()
            }
        }
    }

    override fun close() {
        pollJob?.cancel()
        commands.cancel()
        reads.cancel()
        runCatching { monkey?.close() }
        monkey = null
        runCatching { connection?.close() }
        connection = null
    }

    /** [delay] is cancellable, so cancelling the job ends the loop. */
    private suspend fun pollLoop() {
        while (true) {
            runCatching { readState() }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun readState() = stateLock.withLock {
        val out = shell(STATE_COMMAND) ?: return@withLock
        parseVolume(out)?.let { _volume.value = it }
        parseForegroundPackage(out)?.let { _currentApp.value = it }
    }

    /** Runs one command and returns its output, or null if the device is gone. */
    private fun shell(command: String, on: AdbConnection? = null): String? {
        val c = on ?: connection ?: return null
        return runCatching {
            c.open("shell:$command").use { it.readToEnd(SHELL_TIMEOUT_MS) }
        }.getOrNull()
    }

    /**
     * monkey's command port: one socket, one line per command, "OK" back.
     *
     * The `shell:monkey --port` stream has to stay open — monkey exits with it,
     * taking the port with it.
     */
    private class Monkey(private val host: AdbStream, private val port: AdbStream) {

        fun command(line: String): Boolean = runCatching {
            port.write(line + "\n")
            port.readUntil("\n", MONKEY_TIMEOUT_MS).startsWith("OK")
        }.getOrDefault(false)

        fun close() {
            runCatching { port.write("quit\n") }
            runCatching { port.close() }
            runCatching { host.close() }
        }

        companion object {
            private const val MONKEY_PORT = 1080
            private const val MONKEY_TIMEOUT_MS = 4_000L

            /**
             * Null if monkey will not run here; the caller falls back to `input`.
             *
             * If the app was killed last time, a monkey from that session may
             * still hold the port. Then this `monkey --port` exits straight away
             * with a bind error and the `tcp:` connect below reaches the old one,
             * which is exactly what we want — so a failure to bind is not checked
             * for, only a failure to connect.
             */
            fun start(connection: AdbConnection): Monkey? = runCatching {
                val host = connection.open("shell:monkey --port $MONKEY_PORT")
                // monkey takes a moment to bind, and says nothing when it has.
                var port: AdbStream? = null
                repeat(10) {
                    if (port == null) {
                        Thread.sleep(400)
                        port = runCatching { connection.open("tcp:$MONKEY_PORT", 2_000) }.getOrNull()
                    }
                }
                val opened = port
                if (opened == null) {
                    host.close()
                    null
                } else {
                    Monkey(host, opened)
                }
            }.getOrNull()
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L

        /** After one of these the level has changed, so the bar is re-read at once. */
        private val VOLUME_KEYS = setOf(
            RemoteKeyCode.KEYCODE_VOLUME_UP.number,
            RemoteKeyCode.KEYCODE_VOLUME_DOWN.number,
            RemoteKeyCode.KEYCODE_VOLUME_MUTE.number,
            RemoteKeyCode.KEYCODE_MUTE.number,
        )
        private const val SHELL_TIMEOUT_MS = 8_000L

        /**
         * Volume and the foreground app in one round trip.
         *
         * The STREAM_MUSIC block is picked out with awk rather than a `sed`
         * range because the block that follows it is not always STREAM_ALARM.
         */
        private const val STATE_COMMAND =
            "dumpsys audio | awk '/^- STREAM_MUSIC:/{f=1;next} /^- /{f=0} f' " +
                "| grep -E 'streamVolume|Max:|Muted:'; " +
                "dumpsys activity activities | grep -m1 topResumedActivity"

        /**
         * Both launcher categories: a TV box lists its leanback apps, but
         * sideloaded phone apps — Netflix on this projector — only ever appear
         * under the plain LAUNCHER category.
         */
        internal const val HOME_MARKER = "--home--"

        private const val APPS_COMMAND =
            "for c in LEANBACK_LAUNCHER LAUNCHER; do " +
                "cmd package query-activities --brief " +
                "-a android.intent.action.MAIN -c android.intent.category.\$c; " +
                "done; echo $HOME_MARKER; " +
                "cmd package query-activities --brief " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME; " +
                "getprop ro.build.version.release"

        private val COMPONENT = Regex("""^\s*([a-zA-Z][\w.]+)/([\w./$]+)\s*$""")
        private val FOREGROUND = Regex("""topResumedActivity=\S+\s+\S+\s+([a-zA-Z][\w.]+)/""")
        private val STREAM_VOLUME = Regex("""streamVolume:(\d+)""")
        private val STREAM_MAX = Regex("""Max:\s*(\d+)""")
        private val STREAM_MUTED = Regex("""^\s*Muted:\s*(\w+)""", RegexOption.MULTILINE)
        private val VERSION = Regex("""^\s*(\d+(\.\d+)*)\s*$""", RegexOption.MULTILINE)

        /**
         * There is no way to read an app's display name over adbd — the label
         * lives in the APK's resources, which needs aapt. So the common ones are
         * named here and the rest are made presentable from the package name,
         * which is wrong-looking far less often than it is wrong.
         */
        private val KNOWN_LABELS = mapOf(
            "com.google.android.youtube" to "YouTube",
            "com.netflix" to "Netflix",
            "com.amazon.amazonvideo" to "Prime Video",
            "com.amazon.avod" to "Prime Video",
            "com.android.vending" to "Play Store",
            "com.google.android.apps.tv" to "Google TV",
            "com.android.tv.settings" to "Settings",
            "com.android.chrome" to "Chrome",
            "org.videolan.vlc" to "VLC",
            "com.spotify" to "Spotify",
            "in.startv.hotstar" to "Hotstar",
            "com.disney" to "Disney+",
            "com.plexapp" to "Plex",
            "com.jio" to "JioCinema",
            "com.sonyliv" to "SonyLIV",
            "com.ionitech.airscreen" to "AirScreen",
            "com.android.deskclock" to "Clock",
            "com.android.gallery3d" to "Gallery",
        )

        /** Dropped when prettifying, because they name a flavour, not an app. */
        private val NOISE = setOf(
            "tv", "android", "app", "apps", "client", "mediaclient", "mobile", "google", "com", "org",
        )

        internal fun deviceFromBanner(banner: String): DeviceInfo {
            val props = banner.removePrefix("device::").split(';')
                .mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
                .associate { it[0].trim() to it[1].trim() }
            val model = props["ro.product.model"] ?: props["ro.product.name"] ?: "Android device"
            return DeviceInfo(vendor = "ADB", model = model, version = "")
        }

        internal fun parseVolume(output: String): VolumeInfo? {
            val level = STREAM_VOLUME.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val max = STREAM_MAX.find(output)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val muted = STREAM_MUTED.find(output)?.groupValues?.get(1) == "true"
            return VolumeInfo(level, max, muted)
        }

        internal fun parseForegroundPackage(output: String): String? =
            FOREGROUND.find(output)?.groupValues?.get(1)

        /**
         * The launchers are dropped: HOME already goes there, and a row called
         * "Htclauncherhighenglishd08" is no use to anyone.
         */
        internal fun parseApps(output: String): List<RemoteApp> {
            val launchable = output.substringBefore(HOME_MARKER)
            val homes = output.substringAfter(HOME_MARKER, "")
                .lineSequence()
                .mapNotNull { COMPONENT.matchEntire(it)?.groupValues?.get(1) }
                .toSet()

            val seen = LinkedHashMap<String, RemoteApp>()
            for (line in launchable.lineSequence()) {
                val match = COMPONENT.matchEntire(line) ?: continue
                val (pkg, activity) = match.destructured
                if (pkg in seen || pkg in homes) continue
                seen[pkg] = RemoteApp(labelFor(pkg), "$pkg/$activity")
            }
            return seen.values.toList()
        }

        internal fun parseVersion(output: String): String =
            VERSION.findAll(output).lastOrNull()?.groupValues?.get(1).orEmpty()

        internal fun labelFor(packageName: String): String {
            KNOWN_LABELS.entries.firstOrNull { packageName.startsWith(it.key) }?.let { return it.value }
            val word = packageName.split('.')
                .lastOrNull { it.lowercase() !in NOISE && it.isNotBlank() }
                ?: packageName
            return word.replaceFirstChar { it.uppercase() }
        }

        /** Safe inside a shell command line, however odd the text is. */
        internal fun singleQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}
