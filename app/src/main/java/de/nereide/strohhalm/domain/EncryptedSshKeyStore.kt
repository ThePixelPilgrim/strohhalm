package de.nereide.strohhalm.domain

import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.File
import java.security.KeyPair
import java.security.SecureRandom

/**
 * Stores the Ed25519 *seed* — 32 bytes that fully determine the key pair —
 * encrypted with [KeystoreCipher], inside internal storage.
 *
 * Internal storage is deliberate: the mirror directory the user picks is by
 * design browsable, copied off-device and swept up by other backup tools, which
 * makes it the worst possible home for a private key.
 *
 * Only the seed is persisted rather than an OpenSSH private-key container. The
 * seed fully determines the pair and this app is its only consumer, which avoids
 * implementing OpenSSH's bcrypt-KDF format entirely. The trade-off — the key
 * cannot be exported to another device — is accepted.
 */
class EncryptedSshKeyStore(
    filesDir: File,
    private val cipher: KeystoreCipher = KeystoreCipher(KEY_ALIAS),
    private val comment: String = "strohhalm@${Build.MODEL ?: "android"}",
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SshKeyStore {

    private val keyFile = File(File(filesDir, "ssh").apply { mkdirs() }, "id_ed25519.seed.enc")
    private val mutex = Mutex()

    override fun hasKey(): Boolean = keyFile.isFile

    override suspend fun keyPair(): KeyPair = mutex.withLock {
        withContext(io) { loadOrCreate() }
    }

    override suspend fun publicKeyLine(): String {
        val pair = keyPair()
        return OpenSshPublicKey.encode((pair.public as EdDSAPublicKey).abyte, comment)
    }

    override suspend fun regenerate(): KeyPair = mutex.withLock {
        withContext(io) {
            keyFile.delete()
            loadOrCreate()
        }
    }

    private fun loadOrCreate(): KeyPair {
        if (keyFile.isFile) {
            return fromSeed(cipher.decrypt(keyFile.readBytes()))
        }
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val generated = KeyPairGenerator()
            .apply { initialize(spec, SecureRandom()) }
            .generateKeyPair()
        val seed = (generated.private as EdDSAPrivateKey).seed

        // Write via a temp file so an interrupted write cannot leave a truncated
        // seed that would decrypt to a key the server has never seen.
        val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(seed))
        check(tmp.renameTo(keyFile)) { "could not persist the SSH key" }
        return generated
    }

    private fun fromSeed(seed: ByteArray): KeyPair {
        val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
        val privateSpec = EdDSAPrivateKeySpec(seed, spec)
        return KeyPair(
            EdDSAPublicKey(EdDSAPublicKeySpec(privateSpec.a, spec)),
            EdDSAPrivateKey(privateSpec)
        )
    }

    private companion object {
        const val KEY_ALIAS = "strohhalm.sshkey.v1"
    }
}
