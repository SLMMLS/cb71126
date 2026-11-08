package com.clipsync.app.network

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts exactly one certificate: whichever one hashes (SHA-256 of its DER
 * bytes) to [expectedHash] - learned from the pairing QR the first time,
 * and from the stored [com.clipsync.app.data.PairedPc] record every time
 * after that. This is pinning, not "accept anything"; the two look
 * identical from the outside (the connection just succeeds) but only one
 * of them is a real defense against an active attacker on the LAN.
 *
 * ## The one thing worth testing yourself before trusting this class
 * Confirm [checkServerTrusted] actually THROWS on a mismatch rather than
 * merely logging one. A trust manager that returns normally on every code
 * path is indistinguishable from a correct one *until* someone actually
 * presents the wrong certificate - which is exactly what makes this class
 * of bug dangerous: it fails silently, not loudly, and a compiler cannot
 * catch it for you. A concrete way to check it: temporarily flip one byte
 * of the hash you pass in before connecting, confirm you get an
 * SSLHandshakeException instead of a successful connection, then revert.
 */
/**
 * Thrown (wrapped by the TLS stack in an SSLHandshakeException) when the
 * server's certificate is not the pinned one. A dedicated type lets the UI
 * say "re-pair" precisely instead of string-matching an exception message.
 */
class PinMismatchException(message: String) : CertificateException(message)

class PinningTrustManager(private val expectedHash: ByteArray) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("this app never presents a client certificate")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val cert = chain.firstOrNull()
            ?: throw CertificateException("server presented no certificate")
        val actualHash = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        if (!actualHash.contentEquals(expectedHash)) {
            throw PinMismatchException(
                "TLS certificate does not match the hash pinned at pairing time - refusing to " +
                    "connect. If the PC's certificate genuinely changed, re-pair to trust the new one.",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

object PinnedHttp {
    fun socketFactory(certHash: ByteArray): SSLSocketFactory {
        val trustManager = PinningTrustManager(certHash)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), SecureRandom())
        return sslContext.socketFactory
    }

    fun trustManager(certHash: ByteArray): X509TrustManager = PinningTrustManager(certHash)

    /**
     * Skips hostname verification - safe *only* because the trust manager
     * above is what actually establishes trust here, by comparing the
     * certificate's hash directly. Standard hostname checking (matching
     * the cert's CN/SAN against the URL) doesn't apply well to a bare LAN
     * IP that changes between DHCP leases anyway; the pinned hash is the
     * identity check, not the hostname. Do not reuse this verifier for any
     * connection that isn't already pinned.
     */
    val trustAnyHostname = HostnameVerifier { _, _ -> true }
}
