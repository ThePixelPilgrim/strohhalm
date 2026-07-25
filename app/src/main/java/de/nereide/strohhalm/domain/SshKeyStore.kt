package de.nereide.strohhalm.domain

import java.security.KeyPair

/**
 * Owns the app's single SSH identity. Implementations are the only place in the
 * app that touch key material.
 */
interface SshKeyStore {

    /** True when a key already exists, without generating one as a side effect. */
    fun hasKey(): Boolean

    /** Returns the key pair, generating and persisting one on first call. */
    suspend fun keyPair(): KeyPair

    /** The OpenSSH `authorized_keys` line for the public key. */
    suspend fun publicKeyLine(): String

    /** Discards the existing key and generates a fresh one. */
    suspend fun regenerate(): KeyPair
}
