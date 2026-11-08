package com.clipsync.app.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/** The QR is untrusted input (anyone can print one) - this is what a rejected QR looks like to the UI. */
class InvalidQrException(message: String) : Exception(message)

/** What the PC's pairing QR encodes - see PROTOCOL.md §2. */
data class PairingQrPayload(
    val v: Int,
    val name: String,
    /** Primary "ip:port" - the PC's best guess at which LAN address this phone can reach. */
    val addr: String,
    /**
     * Every other private IPv4 the PC has ("ip:port"). Optional on the wire.
     * The PC cannot know which of its adapters is on the phone's network
     * (VPN, Hyper-V/WSL/VirtualBox virtual adapters and a second NIC are all
     * common), so it lists them all and the phone tries each in turn.
     */
    val alt: List<String>,
    val token: String,
    val certHash: String, // base64 SHA-256 of the PC's self-signed TLS cert - see PROTOCOL.md §2a
) {
    /** Primary first, then the fallbacks, without duplicates. */
    fun candidateAddresses(): List<String> = (listOf(addr) + alt).distinct()

    companion object {
        const val SUPPORTED_VERSION = 1
        private const val MAX_ALT = 6
        private val TOKEN_RE = Regex("^[0-9A-Za-z]{4,64}$")

        fun fromJson(json: String): PairingQrPayload {
            val payload = try {
                val o = JSONObject(json)
                val altArray = o.optJSONArray("alt")
                val alt = ArrayList<String>()
                if (altArray != null) {
                    for (i in 0 until minOf(altArray.length(), MAX_ALT)) alt.add(altArray.getString(i))
                }
                PairingQrPayload(
                    v = o.getInt("v"),
                    name = o.getString("name"),
                    addr = o.getString("addr"),
                    alt = alt,
                    token = o.getString("token"),
                    certHash = o.getString("cert_hash"),
                )
            } catch (e: JSONException) {
                throw InvalidQrException("Это не QR-код ClipSync")
            }
            payload.validate()
            return payload
        }

        /** "a.b.c.d:port" with sane octets and port; no hostnames, loopback, multicast or 0.x. */
        fun isPlausibleLanAddress(addr: String): Boolean {
            val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3}):(\\d{1,5})$").matchEntire(addr) ?: return false
            val octets = m.groupValues.subList(1, 5).map { it.toInt() }
            val port = m.groupValues[5].toInt()
            if (octets.any { it > 255 } || port !in 1..65535) return false
            val first = octets[0]
            return first != 0 && first != 127 && first < 224
        }
    }

    private fun validate() {
        if (v != SUPPORTED_VERSION) {
            throw InvalidQrException("QR от другой версии ClipSync (v$v) - обновите приложение на ПК и телефоне")
        }
        if (!TOKEN_RE.matches(token)) throw InvalidQrException("QR-код повреждён (токен)")
        val hashOk = try {
            Base64.getDecoder().decode(certHash).size == 32
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!hashOk) throw InvalidQrException("QR-код повреждён (отпечаток сертификата)")
        for (a in candidateAddresses()) {
            if (!isPlausibleLanAddress(a)) throw InvalidQrException("QR-код содержит недопустимый адрес: $a")
        }
    }
}

data class PairRequest(val token: String, val deviceId: String, val deviceName: String, val publicKey: String) {
    fun toJson(): String = JSONObject()
        .put("token", token)
        .put("device_id", deviceId)
        .put("device_name", deviceName)
        .put("public_key", publicKey)
        .toString()
}

data class PairResponse(val deviceId: String, val deviceName: String, val publicKey: String) {
    companion object {
        fun fromJson(json: String): PairResponse {
            val o = JSONObject(json)
            return PairResponse(o.getString("device_id"), o.getString("device_name"), o.getString("public_key"))
        }
    }
}

/** The plaintext shape sealed inside every /push body - see PROTOCOL.md §3. */
data class PushPayload(
    val entryType: String,
    val contentB64: String,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val createdAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("entry_type", entryType)
        .put("content_b64", contentB64)
        .put("image_width", imageWidth ?: JSONObject.NULL)
        .put("image_height", imageHeight ?: JSONObject.NULL)
        .put("created_at", createdAt)
        .toString()

    companion object {
        fun fromJson(json: String): PushPayload {
            val o = JSONObject(json)
            return PushPayload(
                entryType = o.getString("entry_type"),
                contentB64 = o.getString("content_b64"),
                imageWidth = if (o.isNull("image_width")) null else o.getInt("image_width"),
                imageHeight = if (o.isNull("image_height")) null else o.getInt("image_height"),
                createdAt = o.getLong("created_at"),
            )
        }
    }
}

/** What this build of the phone app speaks - see PROTOCOL.md §3b. */
object ProtocolInfo {
    const val VERSION = 1

    /**
     * Optional capabilities announced in /hello. A side relies on one only after the other
     * side listed it; a PC that never answered hello is an older app and gets plain protocol 1.
     */
    val FEATURES: List<String> = listOf("hello", com.clipsync.app.files.FileProtocol.FEATURE)
}

/** The plaintext sealed inside every /hello body - PROTOCOL.md §3b. */
data class HelloPayload(
    val protocol: Int,
    val features: List<String>,
    /** Port of this phone's receiver (the PC assumes the well-known one when absent). */
    val listenPort: Int,
    /** false = the receiver is switched off (or did not start): the PC stops sending and queuing. */
    val receiver: Boolean,
    /** Why this hello is sent (service_start, network_change, app_open, receiver_off, ...). */
    val reason: String,
    /** When this message was built (ms since the epoch): the PC refuses stale and repeated ones. */
    val createdAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("protocol", protocol)
        .put("features", JSONArray(features))
        .put("listen_port", listenPort)
        .put("receiver", receiver)
        .put("reason", reason)
        .put("created_at", createdAt)
        .toString()
}

/** The PC's answer to /hello. */
data class HelloResponse(
    val protocol: Int,
    val features: List<String>,
    val deviceName: String,
    /** The PC's clock (ms since the epoch), to tell the user when the two clocks differ too much. */
    val serverTime: Long,
) {
    companion object {
        private const val MAX_FEATURES = 32

        fun fromJson(json: String): HelloResponse {
            val o = JSONObject(json)
            val array = o.optJSONArray("features")
            val features = ArrayList<String>()
            if (array != null) {
                for (i in 0 until minOf(array.length(), MAX_FEATURES)) features.add(array.getString(i))
            }
            return HelloResponse(
                protocol = o.optInt("protocol", 1),
                features = features,
                deviceName = o.optString("device_name", ""),
                serverTime = o.optLong("server_time", 0L),
            )
        }
    }
}
