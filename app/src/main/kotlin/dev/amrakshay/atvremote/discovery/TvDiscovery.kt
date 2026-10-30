package dev.amrakshay.atvremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import dev.amrakshay.atvremote.protocol.TransportKind
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

/** The TV's own remote service. */
private const val REMOTE_SERVICE = "_androidtvremote2._tcp."

/**
 * adbd. Devices with no remote service — plenty of TV boxes and projectors —
 * advertise this instead when debugging over Wi-Fi is on. See issue #1.
 */
private const val ADB_SERVICE = "_adb._tcp."

private const val TAG = "TvDiscovery"

data class DiscoveredTv(
    val serviceName: String,
    val host: String,
    val port: Int,
    val kind: TransportKind = TransportKind.Native,
) {
    /**
     * `adb-t950s4d414137373314232301` is a serial number, not a name anyone
     * would recognise, so an ADB device is shown by its address until it has
     * been connected to and can say what it is.
     */
    val label: String
        get() = if (kind == TransportKind.Adb) ADB_PLACEHOLDER else serviceName

    companion object {
        /** Stands in until the device has been connected to and can say its model. */
        const val ADB_PLACEHOLDER = "Android device"
    }
}

/**
 * Browses the local network for anything this app can drive.
 *
 * Two things here are not optional on real devices:
 *  - a multicast lock, or some handsets silently drop the mDNS responses;
 *  - serialised resolves, because NsdManager fails with FAILURE_ALREADY_ACTIVE
 *    if a second resolve starts while one is in flight. That bug is ancient and
 *    still present.
 */
class TvDiscovery(context: Context) {

    private val appContext = context.applicationContext

    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val multicastLock = wifi.createMulticastLock("atv-remote-discovery").apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        val found = linkedMapOf<String, DiscoveredTv>()
        val resolveLock = Mutex()
        val ourAddresses = localAddresses()

        /**
         * A TV with the remote service may also have Wi-Fi debugging on, and
         * then it advertises both. Listing it twice is confusing and one of the
         * two rows is worse in every way, so the native entry wins.
         */
        fun publish(): Any {
            val nativeHosts = found.values
                .filter { it.kind == TransportKind.Native }
                .map { it.host }
                .toSet()
            return trySend(
                found.values.filter {
                    it.kind == TransportKind.Native || it.host !in nativeHosts
                },
            )
        }

        fun listenerFor(kind: TransportKind) = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "discovery started for $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                launch {
                    val resolved = resolveLock.withLock { resolve(nsd, service, kind) } ?: return@launch
                    // A phone with wireless debugging on advertises _adb._tcp too,
                    // and offering to remote-control itself helps nobody.
                    if (resolved.host in ourAddresses) {
                        Log.d(TAG, "ignoring ourselves at ${resolved.host}")
                        return@launch
                    }
                    found[resolved.serviceName] = resolved
                    publish()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.serviceName)
                publish()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start failed for $serviceType: $errorCode")
                // One of the two failing is not fatal — the other may still find
                // something — so this reports rather than closing the flow.
                trySend(found.values.toList())
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop failed for $serviceType: $errorCode")
            }
        }

        val listeners = listOf(
            REMOTE_SERVICE to listenerFor(TransportKind.Native),
            ADB_SERVICE to listenerFor(TransportKind.Adb),
        )

        trySend(emptyList())
        listeners.forEach { (type, listener) ->
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure { Log.w(TAG, "could not browse $type", it) }
        }

        awaitClose {
            listeners.forEach { (_, listener) ->
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
            runCatching { multicastLock.release() }
        }
    }

    @Suppress("DEPRECATION") // registerServiceInfoCallback is API 34+; this targets API 29.
    private suspend fun resolve(
        nsd: NsdManager,
        service: NsdServiceInfo,
        kind: TransportKind,
    ): DiscoveredTv? = suspendCancellableCoroutine { continuation ->
        nsd.resolveService(
            service,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (continuation.isActive) {
                        continuation.resume(
                            if (host == null) null
                            else DiscoveredTv(info.serviceName, host, info.port, kind),
                        )
                    }
                }
            },
        )
    }

    private fun localAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())
}
