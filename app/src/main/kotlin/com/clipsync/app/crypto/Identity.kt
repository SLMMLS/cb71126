package com.clipsync.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This device's long-term X25519 identity, mirroring `pairing.rs` on the PC
 * side: one static keypair reused for every pairing (static-static ECDH
 * already gives a distinct shared secret per peer). No forward secrecy if
 * this key is ever extracted - an honest, documented simplification shared
 * with the PC side, not an Android-specific shortcut.
 *
 * Android Keystore does not reliably support generating X25519 keys
 * directly across devices, so the raw 32-byte private key is generated in
 * software and then wrapped (encrypted) by a hardware-backed AES key that
 * *is* universally supported - the AES key itself never leaves secure
 * hardware on devices that have it.
 */
object Identity {
    private const val WRAPPING_KEY_ALIAS = "clipsync_wrapping_key"
    private const val PREFS_NAME = "clipsync_secure_prefs"
    private const val PREF_KEY = "x25519_private_key_wrapped"
    private const val PREF_LOCAL_KEY = "local_storage_key_wrapped"
    private const val GCM_IV_LEN = 12

    data class KeyPairRaw(val privateKey: ByteArray, val publicKey: ByteArray)

    fun loadOrCreate(context: Context): KeyPairRaw {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_KEY, null)

        val privateKey = if (stored != null) {
            unwrap(Base64.decode(stored, Base64.NO_WRAP))
        } else {
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val wrapped = wrap(fresh)
            prefs.edit().putString(PREF_KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()
            fresh
        }

        return KeyPairRaw(privateKey, publicKeyFor(privateKey))
    }

    /**
     * A separate AES-256 key for encrypting this phone's own local history
     * at rest - deliberately not the same key as the X25519 identity above,
     * mirroring `crypto.rs` (local storage) being a distinct key from
     * `pairing.rs` (transport identity) on the PC side.
     */
    fun loadOrCreateLocalStorageKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_LOCAL_KEY, null)
        if (stored != null) {
            return unwrap(Base64.decode(stored, Base64.NO_WRAP))
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = wrap(fresh)
        prefs.edit().putString(PREF_LOCAL_KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()
        return fresh
    }

    fun publicKeyFor(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    /**
     * Derives the AES-256 key shared with one specific peer. This is the
     * one spot in the Android app most worth checking first if something
     * doesn't compile: BouncyCastle's `RawAgreement.calculateAgreement`
     * writes into a caller-provided buffer rather than returning one
     * (confirmed against BC's own source), but if your resolved BC version
     * turns out to return the secret directly instead, this is a one-line
     * fix - the surrounding HKDF step is unaffected either way.
     */
    fun sharedSecret(myPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val privateParams = X25519PrivateKeyParameters(myPrivateKey, 0)
        val publicParams = X25519PublicKeyParameters(theirPublicKey, 0)
        val agreement = X25519Agreement()
        agreement.init(privateParams)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(publicParams, secret, 0)
        return secret
    }

    // ---- AES-GCM wrapping via a hardware-backed Android Keystore key ----

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            WRAPPING_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, GCM_IV_LEN)
        val ciphertext = blob.copyOfRange(GCM_IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
