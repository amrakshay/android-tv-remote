package dev.amrakshay.atvremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

private const val SERVICE_TYPE = "_androidtvremote2._tcp."
private const val TAG = "TvDiscovery"

data class DiscoveredTv(
    val serviceName: String,
    val host: String,
    val port: Int,
)

/**
 * Browses the local network for Android TVs.
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

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                launch {
                    val resolved = resolveLock.withLock { resolve(nsd, service) } ?: return@launch
                    found[resolved.serviceName] = resolved
                    trySend(found.values.toList())
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.serviceName)
                trySend(found.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start failed: $errorCode")
                close(IllegalStateException("could not start discovery (error $errorCode)"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop failed: $errorCode")
            }
        }

        trySend(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { multicastLock.release() }
        }
    }

    @Suppress("DEPRECATION") // registerServiceInfoCallback is API 34+; this targets API 29.
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): DiscoveredTv? =
        suspendCancellableCoroutine { continuation ->
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
                                else DiscoveredTv(info.serviceName, host, info.port),
                            )
                        }
                    }
                },
            )
        }
}
