package dev.amrakshay.atvremote.data

import android.content.Context
import dev.amrakshay.atvremote.protocol.ClientIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedTv(
    val serviceName: String,
    val host: String,
    val port: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("serviceName", serviceName)
        .put("host", host)
        .put("port", port)

    companion object {
        fun fromJson(o: JSONObject) = SavedTv(
            serviceName = o.getString("serviceName"),
            host = o.getString("host"),
            port = o.getInt("port"),
        )
    }
}

/**
 * Everything that has to survive a restart: the client certificate the TV
 * remembers us by, and the TVs we have paired with.
 *
 * Plain files in the app's private directory. The certificate only grants
 * control of a TV on the local network, so the Android Keystore is a hardening
 * step rather than a requirement — see docs/PLAN.md §4.4.
 */
class TvStore(context: Context) {

    private val dir = File(context.filesDir, "atv").apply { mkdirs() }
    private val certFile = File(dir, "cert.pem")
    private val keyFile = File(dir, "key.pem")
    private val tvsFile = File(dir, "tvs.json")

    /** Loads the stored identity, generating one on first run. */
    suspend fun identity(): ClientIdentity = withContext(Dispatchers.IO) {
        if (certFile.exists() && keyFile.exists()) {
            runCatching { ClientIdentity.fromPem(certFile.readText(), keyFile.readText()) }
                .getOrElse { generateAndStore() }
        } else {
            generateAndStore()
        }
    }

    private fun generateAndStore(): ClientIdentity =
        ClientIdentity.generate().also {
            certFile.writeText(it.certificatePem())
            keyFile.writeText(it.privateKeyPem())
        }

    suspend fun pairedTvs(): List<SavedTv> = withContext(Dispatchers.IO) {
        if (!tvsFile.exists()) return@withContext emptyList()
        runCatching {
            val array = JSONArray(tvsFile.readText())
            (0 until array.length()).map { SavedTv.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    suspend fun remember(tv: SavedTv) = withContext(Dispatchers.IO) {
        // Keyed by service name, so a TV whose DHCP address moved is updated
        // rather than duplicated.
        val updated = pairedTvs().filterNot { it.serviceName == tv.serviceName } + tv
        tvsFile.writeText(JSONArray().apply { updated.forEach { put(it.toJson()) } }.toString())
    }

    suspend fun forget(serviceName: String) = withContext(Dispatchers.IO) {
        val updated = pairedTvs().filterNot { it.serviceName == serviceName }
        tvsFile.writeText(JSONArray().apply { updated.forEach { put(it.toJson()) } }.toString())
    }
}
