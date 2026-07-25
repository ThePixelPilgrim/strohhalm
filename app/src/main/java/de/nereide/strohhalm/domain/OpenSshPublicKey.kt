package de.nereide.strohhalm.domain

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Encodes a raw Ed25519 public key as a single OpenSSH `authorized_keys` line.
 *
 * The wire format is a sequence of length-prefixed strings — a big-endian
 * uint32 length followed by that many bytes — here the algorithm name followed
 * by the 32-byte key. This is the only external format Strohhalm must produce,
 * which is why it is hand-rolled and unit-tested rather than delegated.
 */
object OpenSshPublicKey {

    private const val ALGORITHM = "ssh-ed25519"
    private const val ED25519_KEY_BYTES = 32

    fun encode(rawEd25519: ByteArray, comment: String): String {
        require(rawEd25519.size == ED25519_KEY_BYTES) {
            "an Ed25519 public key is $ED25519_KEY_BYTES bytes, got ${rawEd25519.size}"
        }
        val blob = ByteArrayOutputStream().apply {
            writeSshString(ALGORITHM.toByteArray(Charsets.US_ASCII))
            writeSshString(rawEd25519)
        }.toByteArray()
        return "$ALGORITHM ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    private fun ByteArrayOutputStream.writeSshString(value: ByteArray) {
        write(value.size ushr 24)
        write(value.size ushr 16)
        write(value.size ushr 8)
        write(value.size)
        write(value)
    }
}
