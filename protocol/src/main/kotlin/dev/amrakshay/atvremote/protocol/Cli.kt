package dev.amrakshay.atvremote.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 */
fun main(args: Array<String>) {
    runBlocking { run(args) }
    exitProcess(0)
}

private suspend fun run(args: Array<String>) {
    if (args.size < 3) {
        println("usage: pair|keys|text|launch <host> <credsDir> [KEYCODE... | words | appLink]")
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

        else -> {
            println("unknown command: $command")
            exitProcess(2)
        }
    }
}
