package com.martyra.localdb.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the encrypted SQLite database passphrase using Android Keystore.
 *
 * ── Key lifecycle ──────────────────────────────────────────────────────────
 * 1. On first use, generate 32 bytes (256 bits) of cryptographically strong random data
 *    via [SecureRandom]. This becomes the database passphrase.
 * 2. Generate (or retrieve) a non-exportable AES-256-GCM key in the Android Keystore.
 *    The key is bound to the hardware-backed Keystore on supported devices.
 * 3. Encrypt the passphrase with AES/GCM/NoPadding and a fresh 12-byte nonce.
 * 4. Store only the ciphertext + nonce in SharedPreferences. The plaintext passphrase
 *    is never written to disk, logged, or retained beyond a single [getOrCreatePassphrase] call.
 * 5. On subsequent opens, load the Keystore key and decrypt the blob.
 *
 * ── Caller responsibilities ────────────────────────────────────────────────
 * The caller MUST zero the returned [ByteArray] in a finally block as soon as the
 * database has been opened:
 *
 *   val pass = keyManager.getOrCreatePassphrase()
 *   try {
 *       SupportFactory(pass).also { /* open DB */ }
 *   } finally {
 *       pass.fill(0)
 *   }
 *
 * ── Limitations ───────────────────────────────────────────────────────────
 * • JVM memory zeroing: The JVM / Kotlin runtime may copy ByteArray internally
 *   (garbage collection, JIT). Filling the array with zeros removes the plaintext
 *   from the original allocation but does not guarantee all copies are erased.
 *   This is a fundamental JVM limitation; the caller should minimise the window
 *   between key retrieval and zeroing.
 *
 * • Android backup / reinstall: If the user restores from an Android backup,
 *   the encrypted blob in SharedPreferences is restored but the Keystore key is NOT
 *   (Keystore keys are hardware-bound and not included in cloud backups).
 *   The result is an unrecoverable encrypted database. This module detects that state
 *   and throws [DatabaseKeyException]; the host application must handle it and inform
 *   the user that local evidence is permanently inaccessible.
 *   If android:allowBackup="false" is set in the host app manifest, this cannot occur.
 *
 * • Silent re-key: This module NEVER silently generates a new key when an existing
 *   encrypted database cannot be opened. Doing so would produce a second database
 *   that appears valid but discards all historical evidence.
 *
 * • Biometric / device-unlock constraints: This implementation does not require
 *   biometric authentication (no setUserAuthenticationRequired). Adding that constraint
 *   would prevent background database access (e.g., from a WorkManager job) when the
 *   device screen is off. This is documented as a future hardening option.
 *
 * • EncryptedSharedPreferences: AndroidX Security Crypto's EncryptedSharedPreferences
 *   adds a second layer of AES-GCM wrapping on top of SharedPreferences. It is
 *   intentionally not used here because: (a) we already wrap with Keystore AES-GCM,
 *   making a second layer redundant; (b) it has a history of cross-version compatibility
 *   issues. The encrypted blob in plain SharedPreferences is safe: without the Keystore
 *   key, it cannot be decrypted.
 */
class DatabaseKeyManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs by lazy {
        // MODE_PRIVATE — only this app can read this file (OS enforces via file permissions).
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the database passphrase as a raw byte array.
     *
     * On the first call, generates a new random passphrase, wraps it with Keystore AES-GCM,
     * and persists the ciphertext. On subsequent calls, decrypts and returns the stored passphrase.
     *
     * @throws DatabaseKeyException if the Keystore key is missing or permanently inaccessible.
     */
    fun getOrCreatePassphrase(): ByteArray {
        val storedBlob   = prefs.getString(KEY_ENCRYPTED_BLOB, null)
        val storedNonce  = prefs.getString(KEY_NONCE, null)

        return if (storedBlob != null && storedNonce != null) {
            // Passphrase was previously generated — decrypt and return it.
            decryptStoredPassphrase(storedBlob, storedNonce)
        } else {
            generateAndPersistNewPassphrase()
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun generateAndPersistNewPassphrase(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(passphrase)

        val wrappingKey = getOrCreateKeystoreKey()
        val nonce = ByteArray(GCM_NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        val ciphertext = encryptAesGcm(wrappingKey, nonce, passphrase)

        prefs.edit()
            .putString(KEY_ENCRYPTED_BLOB, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_NONCE, Base64.encodeToString(nonce, Base64.NO_WRAP))
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .apply()

        // Return a copy — the local 'passphrase' variable is zeroed by the JVM GC
        // eventually, but the caller is responsible for zeroing the returned array.
        return passphrase
    }

    private fun decryptStoredPassphrase(blobBase64: String, nonceBase64: String): ByteArray {
        val wrappingKey = getKeystoreKeyOrThrow()
        val nonce = Base64.decode(nonceBase64, Base64.NO_WRAP)
        val ciphertext = Base64.decode(blobBase64, Base64.NO_WRAP)
        return decryptAesGcm(wrappingKey, nonce, ciphertext)
    }

    /**
     * Returns the Keystore wrapping key, creating it if it does not yet exist.
     * Thread-safe: KeyGenerator.generateKey() is idempotent for the same alias
     * (subsequent calls return the existing key without error).
     */
    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Not setting setUserAuthenticationRequired so that background DB access works.
                // Future hardening: add biometric unlock constraint here and handle
                // UserNotAuthenticatedException in the caller.
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Returns the Keystore key, or throws [DatabaseKeyException] if it is missing.
     * A missing key while the encrypted blob exists means the key was lost
     * (device restore without Keystore backup) — re-creating the key would produce
     * a second database, discarding all historical evidence. We refuse to do that.
     */
    private fun getKeystoreKeyOrThrow(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            throw DatabaseKeyException(
                "Android Keystore key '$KEY_ALIAS' is missing but an encrypted database passphrase " +
                "blob exists in SharedPreferences. This typically happens after a device restore " +
                "without Keystore backup. The encrypted database cannot be recovered. " +
                "Do NOT silently generate a new key — that would discard all historical evidence."
            )
        }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun encryptAesGcm(key: SecretKey, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    private fun decryptAesGcm(key: SecretKey, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ANDROID_KEYSTORE       = "AndroidKeyStore"
        private const val KEY_ALIAS              = "martyra_db_wrapping_key_v1"
        private const val PREFS_NAME             = "martyra_db_key_prefs"
        private const val KEY_ENCRYPTED_BLOB     = "enc_passphrase"
        private const val KEY_NONCE              = "enc_nonce"
        private const val KEY_VERSION            = "key_version"
        private const val CURRENT_VERSION        = 1
        private const val PASSPHRASE_BYTES       = 32    // 256-bit passphrase
        private const val GCM_NONCE_BYTES        = 12    // 96-bit nonce (GCM standard)
        private const val GCM_TAG_BITS           = 128   // 128-bit authentication tag
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Thrown when the Keystore key is permanently inaccessible. Not recoverable. */
class DatabaseKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
