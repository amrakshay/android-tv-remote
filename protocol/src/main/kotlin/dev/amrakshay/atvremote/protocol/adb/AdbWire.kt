package dev.amrakshay.atvremote.protocol.adb

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The ADB wire format: a 24-byte little-endian header, then the payload.
 *
 * This is the same protocol `adb` itself speaks to `adbd` on port 5555, and it
 * is here because a good many Android TV devices ship without the remote
 * service but with adbd listening — see README, "Devices without the TV Remote
 * Service".
 */
internal const val A_CNXN = 0x4e584e43
internal const val A_OPEN = 0x4e45504f
internal const val A_OKAY = 0x59414b4f
internal const val A_CLSE = 0x45534c43
internal const val A_WRTE = 0x45545257
internal const val A_AUTH = 0x48545541

internal const val AUTH_TOKEN = 1
internal const val AUTH_SIGNATURE = 2
internal const val AUTH_RSAPUBLICKEY = 3

/** Protocol version. adbd echoes it back in its own CNXN. */
internal const val ADB_VERSION = 0x01000001
internal const val MAX_PAYLOAD = 256 * 1024

internal class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {
    val name: String get() = commandName(command)

    override fun toString() = "$name(arg0=$arg0, arg1=$arg1, ${payload.size} bytes)"

    companion object {
        val EMPTY = ByteArray(0)

        fun commandName(command: Int): String =
            String(
                byteArrayOf(
                    command.toByte(),
                    (command ushr 8).toByte(),
                    (command ushr 16).toByte(),
                    (command ushr 24).toByte(),
                ),
                Charsets.US_ASCII,
            )
    }
}

internal object AdbWire {

    const val HEADER_SIZE = 24

    fun encode(message: AdbMessage): ByteArray {
        val out = ByteArray(HEADER_SIZE + message.payload.size)
        putInt(out, 0, message.command)
        putInt(out, 4, message.arg0)
        putInt(out, 8, message.arg1)
        putInt(out, 12, message.payload.size)
        putInt(out, 16, checksum(message.payload))
        // The magic is the command with every bit flipped. adbd checks it, and
        // it is the only framing error it will tell us about.
        putInt(out, 20, message.command.inv())
        message.payload.copyInto(out, HEADER_SIZE)
        return out
    }

    fun write(output: OutputStream, message: AdbMessage) {
        output.write(encode(message))
        output.flush()
    }

    fun read(input: InputStream): AdbMessage {
        val stream = input as? DataInputStream ?: DataInputStream(input)
        val header = ByteArray(HEADER_SIZE)
        stream.readFully(header)
        val command = getInt(header, 0)
        val magic = getInt(header, 20)
        if (command != magic.inv()) {
            throw AdbProtocolException(
                "bad header: ${AdbMessage.commandName(command)} magic 0x${magic.toUInt().toString(16)}",
            )
        }
        val length = getInt(header, 12)
        if (length < 0 || length > MAX_PAYLOAD) {
            throw AdbProtocolException("payload of $length bytes is out of range")
        }
        val payload = ByteArray(length)
        if (length > 0) stream.readFully(payload)
        return AdbMessage(command, getInt(header, 4), getInt(header, 8), payload)
    }

    /**
     * The sum of the payload's bytes, unsigned.
     *
     * Current adbd ignores it, but adbd on older images does not, and a wrong
     * value there fails as a dropped connection with no explanation.
     */
    fun checksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xff
        return sum
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun getInt(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)
}

class AdbProtocolException(message: String) : Exception(message)

/** adbd wants the user to tap "Allow debugging?" and has not been told yet. */
class AdbNotAuthorised(message: String) : Exception(message)
