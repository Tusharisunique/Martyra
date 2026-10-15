package com.martyra.localdb.security

import android.content.Context

/**
 * Abstraction over "provide the SQLCipher database passphrase".
 *
 * The production implementation ([KeystorePassphraseProvider]) derives the passphrase
 * from the Android Keystore via [DatabaseKeyManager].
 *
 * The test implementation ([FixedPassphraseProvider]) uses a deterministic passphrase
 * so instrumented tests can open and inspect a real encrypted database without any
 * Keystore interaction.
 *
 * ── Caller contract ───────────────────────────────────────────────────────
 * [getPassphrase] returns a freshly allocated ByteArray each time.
 * The caller MUST zero it in a finally block immediately after the database factory
 * has consumed it:
 *
 *   val pass = provider.getPassphrase()
 *   try {
 *       val factory = SupportFactory(pass)
 *       // build Room database with factory
 *   } finally {
 *       pass.fill(0)
 *   }
 */
interface DatabasePassphraseProvider {
    /** Returns the passphrase as a byte array. The caller is responsible for zeroing it. */
    fun getPassphrase(): ByteArray
}

/** Production implementation — retrieves the passphrase via Keystore-backed [DatabaseKeyManager]. */
class KeystorePassphraseProvider(context: Context) : DatabasePassphraseProvider {
    private val keyManager = DatabaseKeyManager(context)

    override fun getPassphrase(): ByteArray = keyManager.getOrCreatePassphrase()
}

/**
 * Test-only implementation — returns a fixed passphrase.
 * NEVER use this in production code; it hardcodes a known passphrase.
 * Visible at the package level so instrumented tests can construct it directly.
 */
class FixedPassphraseProvider(private val passphrase: String) : DatabasePassphraseProvider {
    override fun getPassphrase(): ByteArray = passphrase.toByteArray(Charsets.UTF_8)
}
