package dev.amrakshay.atvremote.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.amrakshay.atvremote.protocol.adb.AdbKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import remote.Remotemessage.RemoteKeyCode
import java.io.File
import kotlin.system.exitProcess

/**
 * Desktop harness for exercising the protocol against a real TV, so the whole
 * of it can be verified before any of it runs on a phone.
 *
 *   ./gradlew :protocol:tvcli --args="pair 192.168.0.236 creds"
 *   ./gradlew :protocol:tvcli --args="keys 192.168.0.236 creds VOLUME_DOWN VOLUME_UP"
 *
 * The same commands prefixed with `adb-` drive a device over adbd instead, for
 * the TV boxes and projectors that have no remote service (issue #1):
 *
 *   ./gradlew :protocol:tvcli --args="adb-info  192.168.0.138 creds"
 *   ./gradlew :protocol:tvcli --args="adb-keys  192.168.0.138 creds DPAD_DOWN DPAD_UP"
 *   ./gradlew :protocol:tvcli --args="adb-text  192.168.0.138 creds the bear"
 *   ./gradlew :protocol:tvcli --args="adb-launch 192.168.0.138 creds org.videolan.vlc/.StartActivity"
 */
fun main(args: Array<String>) {
    runBlocking { run(args) }
    exitProcess(0)
}

private suspend fun run(args: Array<String>) {
    if (args.size < 3) {
        println(
            "usage: pair|keys|text|search|launch <host> <credsDir> [KEYCODE... | words | appLink]\n" +
                "       adb-info|adb-keys|adb-text|adb-launch <host> <credsDir> [KEYCODE... | words | component]",
        )
        exitProcess(2)
    }
    val (command, host, credsPath) = args
    val creds = File(credsPath)
    val certFile = File(creds, "cert.pem")
    val keyFile = File(creds, "key.pem")

    when (command) {
        "pair" -> {
            val identity = if (certFile.exists() && keyFile.exists()) {
                println("using the existing certificate")
                ClientIdentity.fromPem(certFile.readText(), keyFile.readText())
            } else {
                val started = System.currentTimeMillis()
                val fresh = ClientIdentity.generate()
                creds.mkdirs()
                certFile.writeText(fresh.certificatePem())
                keyFile.writeText(fresh.privateKeyPem())
                println("generated an RSA-2048 certificate in ${System.currentTimeMillis() - started} ms")
                fresh
            }
            PairingSession(host, identity, clientName = "Android Remote (desktop)").use { session ->
                session.start()
                print("Code shown on the TV: ")
                System.out.flush()
                val code = readlnOrNull().orEmpty()
                session.finish(code)
                println("PAIRED")
            }
        }

        "keys" -> {
            val identity = ClientIdentity.fromPem(certFile.readText(), keyFile.readText())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            RemoteSession(host, identity).use { session ->
                session.connect(scope)
                println("CONNECTED (no pairing needed)")
                println("  device   = ${session.device.value}")
                println("  isOn     = ${session.isOn.value}")

                for (name in args.drop(3)) {
                    val key = RemoteKeyCode.valueOf("KEYCODE_$name")
                    println("  >> $name")
                    session.sendKey(key)
                    delay(1200)
                    println("     volume=${session.volume.value} app=${session.currentApp.value}")
                }
            }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        // One session: open search, wait for the TV to announce the IME counters,
        // then type. Counters are per-session, so this cannot be split into two
        // invocations.
        "search" -> {
            val identity = ClientIdentity.fromPem(certFile.readText(), keyFile.readText())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            RemoteSession(host, identity).use { session ->
                session.connect(scope)
                println("CONNECTED")
                println("  >> SEARCH")
                session.sendKey(RemoteKeyCode.KEYCODE_SEARCH)
                var waited = 0
                while (!session.imeReady.value && waited < 8000) {
                    delay(250); waited += 250
                }
                println("  imeReady=${session.imeReady.value} after ${waited}ms")
                println("  field reads: '${session.textField.value}'")
                val phrase = args.drop(3).joinToString(" ")
                println("  >> typing: $phrase")
                session.sendText(phrase)
                delay(2000)
                println("  field now reads: '${session.textField.value}'")
                println("  current app: ${session.currentApp.value}")
            }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        // Types into whatever field the TV has focused. Open a search box first.
        "text" -> {
            val identity = ClientIdentity.fromPem(certFile.readText(), keyFile.readText())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            RemoteSession(host, identity).use { session ->
                session.connect(scope)
                println("CONNECTED — focused field reads: '${session.textField.value}'")
                val phrase = args.drop(3).joinToString(" ")
                println("  >> typing: $phrase")
                session.sendText(phrase)
                delay(1500)
                println("  field now reads: '${session.textField.value}'")
            }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        "launch" -> {
            val identity = ClientIdentity.fromPem(certFile.readText(), keyFile.readText())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            RemoteSession(host, identity).use { session ->
                session.connect(scope)
                val link = args[3]
                println("  >> launching: $link")
                session.launchApp(link)
                delay(2500)
                println("  current app: ${session.currentApp.value}")
            }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }

        "adb-info", "adb-keys", "adb-text", "adb-launch" ->
            adb(command, host, creds, args.drop(3))

        else -> {
            println("unknown command: $command")
            exitProcess(2)
        }
    }
}

/**
 * The ADB transport, driven the same way — the point of keeping `protocol/`
 * free of Android is that both transports can be proven from a desktop.
 */
private suspend fun adb(command: String, host: String, creds: File, rest: List<String>) {
    creds.mkdirs()
    val keyFile = File(creds, "adbkey.pem")
    val key = if (keyFile.exists()) {
        AdbKey.fromPem(keyFile.readText())
    } else {
        AdbKey.generate().also { keyFile.writeText(it.privateKeyPem()) }
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    AdbRemote(host, key).use { remote ->
        scope.launch {
            remote.awaitingAuthorisation.collect {
                if (it) println("  !! accept the \"Allow debugging?\" prompt on the device")
            }
        }
        remote.connect(scope)
        println("CONNECTED over adb")
        println("  device  = ${remote.device.value}")
        delay(1500)
        println("  volume  = ${remote.volume.value}")
        println("  app     = ${remote.currentApp.value}")

        when (command) {
            "adb-info" -> {
                println("  apps:")
                remote.apps.value.forEach { println("    ${it.label.padEnd(14)} ${it.target}") }
            }

            "adb-keys" -> for (name in rest) {
                println("  >> $name")
                remote.sendKey(RemoteKeyCode.valueOf("KEYCODE_$name"))
                delay(900)
                println("     volume=${remote.volume.value} app=${remote.currentApp.value}")
            }

            "adb-text" -> {
                val phrase = rest.joinToString(" ")
                println("  >> typing: $phrase")
                remote.sendText(phrase)
                delay(1500)
            }

            "adb-launch" -> {
                println("  >> launching: ${rest.first()}")
                remote.launchApp(rest.first())
                delay(3000)
                println("  current app: ${remote.currentApp.value}")
            }
        }
    }
    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
}
