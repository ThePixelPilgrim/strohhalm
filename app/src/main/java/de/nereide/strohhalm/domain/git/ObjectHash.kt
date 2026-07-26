package de.nereide.strohhalm.domain.git

import java.security.MessageDigest

/**
 * The object hash a repository uses.
 *
 * Every component that touches an object id takes one of these. Nothing else in
 * the engine may name a length: JGit's inability to read SHA-256 comes down to
 * `OBJECT_ID_LENGTH` being a compile-time constant in 153 places, and this type
 * exists so that mistake cannot be repeated here.
 */
enum class ObjectHash(
    /** The value git writes as `extensions.objectFormat`. */
    val configName: String,
    val rawLength: Int,
    private val digestName: String,
) {
    SHA1("sha1", 20, "SHA-1"),
    SHA256("sha256", 32, "SHA-256");

    val hexLength: Int get() = rawLength * 2

    fun newDigest(): MessageDigest = MessageDigest.getInstance(digestName)

    /**
     * The id of a loose object: the hash of `"<type> <length>\0"` followed by
     * the body. Identical in both formats apart from the digest.
     */
    fun objectId(type: String, content: ByteArray): ByteArray = newDigest().run {
        update("$type ${content.size}".toByteArray(Charsets.US_ASCII))
        update(0)
        digest(content)
    }

    fun toHex(raw: ByteArray): String = buildString(raw.size * 2) {
        raw.forEach { append(HEX[(it.toInt() shr 4) and 0xf]).append(HEX[it.toInt() and 0xf]) }
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length == hexLength) { "expected $hexLength hex chars, got ${hex.length}" }
        return ByteArray(rawLength) { i ->
            ((digit(hex[i * 2]) shl 4) or digit(hex[i * 2 + 1])).toByte()
        }
    }

    private fun digit(c: Char): Int {
        val v = Character.digit(c, 16)
        require(v >= 0) { "not a hex digit: $c" }
        return v
    }

    companion object {
        private const val HEX = "0123456789abcdef"

        fun fromConfigName(name: String): ObjectHash =
            entries.firstOrNull { it.configName == name }
                ?: throw IllegalArgumentException("unsupported object format: $name")
    }
}
