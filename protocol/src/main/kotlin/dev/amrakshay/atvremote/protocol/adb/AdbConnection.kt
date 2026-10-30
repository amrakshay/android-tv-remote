package dev.amrakshay.atvremote.protocol.adb

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One connection to adbd, carrying as many streams as we care to open.
 *
 * A reader thread demultiplexes everything adbd sends into the stream it
 * belongs to. Nothing here is Android-specific, so the whole transport can be
 * driven from `tvcli` on a desktop.
 */
class AdbConnection(
    private val host: String,
    private val port: Int = ADB_PORT,
    private val key: AdbKey,
) : Closeable {

    companion object {
        const val ADB_PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 6_000
        /** How long to wait for someone to tap "Allow debugging?" on the device. */
        private const val AUTHORISE_TIMEOUT_MS = 120_000
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextStreamId = AtomicInteger(1)
    private val shutDown = AtomicBoolean(false)

    /** The `device::…` string adbd greets us with; it carries the model name. */
    var banner: String = ""
        private set

    private var maxPayload: Int = MAX_PAYLOAD

    /** Called once if the device is asking the user to authorise this key. */
    var onAwaitingAuthorisation: (() -> Unit)? = null

    /** Called when the connection drops for any reason other than [close]. */
    var onLost: ((String) -> Unit)? = null

    /** Blocking. Call it off the main thread. */
    fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.tcpNoDelay = true
        s.soTimeout = CONNECT_TIMEOUT_MS
        socket = s
        input = DataInputStream(BufferedInputStream(s.inputStream))
        output = s.outputStream

        handshake(s)

        // From here the socket must block indefinitely: the reader thread sits
        // on it, and a read timeout would look like a dropped connection every
        // few seconds of an idle remote.
        s.soTimeout = 0
        reader = Thread({ readLoop() }, "adb-$host").apply {
            isDaemon = true
            start()
        }
    }

    private fun handshake(s: Socket) {
        send(AdbMessage(A_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::features=cmd,shell_v2\u0000".toByteArray()))

        var signed = false
        var offeredKey = false
        while (true) {
            val message = AdbWire.read(input!!)
            when (message.command) {
                A_CNXN -> {
                    banner = String(message.payload, Charsets.UTF_8).trimEnd('\u0000')
                    if (message.arg1 in 1..MAX_PAYLOAD) maxPayload = message.arg1
                    return
                }

                A_AUTH -> {
                    if (message.arg0 != AUTH_TOKEN) {
                        throw AdbProtocolException("unexpected AUTH ${message.arg0}")
                    }
                    when {
                        !signed -> {
                            signed = true
                            send(AdbMessage(A_AUTH, AUTH_SIGNATURE, 0, key.sign(message.payload)))
                        }
                        !offeredKey -> {
                            // adbd does not know this key. Offering the public key
                            // is what raises the "Allow debugging?" dialog, and
                            // nothing more happens until someone accepts it.
                            offeredKey = true
                            send(AdbMessage(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload()))
                            s.soTimeout = AUTHORISE_TIMEOUT_MS
                            onAwaitingAuthorisation?.invoke()
                        }
                        else -> throw AdbNotAuthorised(
                            "the device did not accept this key — check for an " +
                                "\"Allow debugging?\" prompt on the screen",
                        )
                    }
                }

                else -> throw AdbProtocolException("unexpected ${message.name} during handshake")
            }
        }
    }

    /**
     * Opens one of adbd's services — `shell:…` to run a command, `tcp:…` to
     * reach a port on the device.
     */
    fun open(service: String, timeoutMs: Long = 10_000): AdbStream {
        check(!shutDown.get()) { "connection is closed" }
        val localId = nextStreamId.getAndIncrement()
        val stream = AdbStream(this, localId)
        streams[localId] = stream
        send(AdbMessage(A_OPEN, localId, 0, service.toByteArray() + 0))
        if (!stream.opened.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            streams.remove(localId)
            throw AdbProtocolException("the device did not open '$service'")
        }
        if (stream.isClosed) {
            streams.remove(localId)
            throw AdbProtocolException("the device refused '$service'")
        }
        return stream
    }

    private fun readLoop() {
        try {
            while (!shutDown.get()) {
                val message = AdbWire.read(input!!)
                val stream = streams[message.arg1] ?: continue
                when (message.command) {
                    A_OKAY -> stream.onOkay(message.arg0)
                    A_WRTE -> {
                        stream.onData(message.payload)
                        // Unacknowledged writes stall the stream after one frame.
                        send(AdbMessage(A_OKAY, message.arg1, message.arg0))
                    }
                    A_CLSE -> {
                        stream.onClosed()
                        streams.remove(message.arg1)
                    }
                }
            }
        } catch (e: Exception) {
            if (!shutDown.get()) {
                streams.values.forEach { it.onClosed() }
                onLost?.invoke(e.message ?: "the connection to adbd dropped")
            }
        }
    }

    internal fun send(message: AdbMessage) {
        val out = output ?: throw AdbProtocolException("not connected")
        val bytes = AdbWire.encode(message)
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
    }

    internal fun writeChunks(stream: AdbStream, data: ByteArray) {
        var at = 0
        while (at < data.size) {
            val size = minOf(maxPayload, data.size - at)
            stream.awaitWriteSlot {
                send(AdbMessage(A_WRTE, stream.localId, stream.remoteId, data.copyOfRange(at, at + size)))
            }
            at += size
        }
    }

    override fun close() {
        if (!shutDown.compareAndSet(false, true)) return
        streams.values.forEach { runCatching { it.close() } }
        streams.clear()
        reader?.interrupt()
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}

/** One multiplexed stream. Reads block; writes wait for adbd's acknowledgement. */
class AdbStream internal constructor(
    private val connection: AdbConnection,
    internal val localId: Int,
) : Closeable {

    internal var remoteId: Int = 0
        private set

    internal val opened = CountDownLatch(1)

    /**
     * adbd sends one OKAY per write it has taken, and will not take another
     * until it has. The permits track exactly that credit.
     */
    private val writeSlots = Semaphore(0)
    private val incoming = LinkedBlockingQueue<ByteArray>()

    @Volatile
    var isClosed: Boolean = false
        private set

    private var pending: ByteArray = ByteArray(0)

    internal fun onOkay(remote: Int) {
        if (opened.count > 0L) {
            remoteId = remote
            opened.countDown()
        } else {
            writeSlots.release()
        }
    }

    internal fun onData(data: ByteArray) {
        incoming.put(data)
    }

    internal fun onClosed() {
        isClosed = true
        opened.countDown()
        // Unblock anything parked on a read or a write.
        incoming.put(ByteArray(0))
        writeSlots.release()
    }

    internal inline fun awaitWriteSlot(write: () -> Unit) {
        write()
        writeSlots.acquire()
    }

    fun write(text: String) = write(text.toByteArray())

    fun write(data: ByteArray) {
        if (isClosed) throw AdbProtocolException("stream $localId is closed")
        connection.writeChunks(this, data)
    }

    /**
     * Collects output until [marker] appears, and keeps whatever followed it for
     * the next call — a shell that is handed two commands can answer both in a
     * single frame, and dropping the tail would lose the second answer.
     */
    fun readUntil(marker: String, timeoutMs: Long = 10_000): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val buffer = StringBuilder(String(pending, Charsets.UTF_8))
        pending = ByteArray(0)
        while (true) {
            val at = buffer.indexOf(marker)
            if (at >= 0) {
                pending = buffer.substring(at + marker.length).toByteArray()
                return buffer.substring(0, at)
            }
            if (isClosed && incoming.isEmpty()) {
                throw AdbProtocolException("the stream closed before '$marker' arrived")
            }
            val waitMs = (deadline - System.nanoTime()) / 1_000_000
            if (waitMs <= 0) throw AdbProtocolException("timed out waiting for '$marker'")
            val chunk = incoming.poll(waitMs, TimeUnit.MILLISECONDS)
                ?: throw AdbProtocolException("timed out waiting for '$marker'")
            buffer.append(String(chunk, Charsets.UTF_8))
        }
    }

    /** Everything the stream produces until the device closes it. */
    fun readToEnd(timeoutMs: Long = 10_000): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val buffer = StringBuilder(String(pending, Charsets.UTF_8))
        pending = ByteArray(0)
        while (!isClosed || incoming.isNotEmpty()) {
            val waitMs = (deadline - System.nanoTime()) / 1_000_000
            if (waitMs <= 0) break
            val chunk = incoming.poll(waitMs, TimeUnit.MILLISECONDS) ?: break
            buffer.append(String(chunk, Charsets.UTF_8))
        }
        return buffer.toString()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { connection.send(AdbMessage(A_CLSE, localId, remoteId)) }
    }
}
