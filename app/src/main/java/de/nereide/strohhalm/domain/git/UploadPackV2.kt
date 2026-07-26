package de.nereide.strohhalm.domain.git

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * What the server said it can do, and the hash it uses.
 *
 * [objectHash] is the single most important value in the engine: every id parsed
 * or written afterwards takes its length from here, which is what makes the same
 * code work for both formats.
 */
data class ServerCapabilities(
    val raw: Map<String, String>,
    val objectHash: ObjectHash,
) {
    fun supports(command: String): Boolean = raw.containsKey(command)
}

/** One ref as the server described it. Ids are hex, at the negotiated length. */
data class RemoteRef(
    val name: String,
    val objectId: String,
    val symrefTarget: String? = null,
    val peeled: String? = null,
)

/**
 * Client half of git's protocol v2 over an already-open `git-upload-pack`
 * channel.
 *
 * Only v2 is implemented. Servers older than git 2.18 (2018) are refused with a
 * clear message rather than silently falling back — a backup tool quietly using
 * a weaker protocol is worse than one that says it cannot.
 */
class UploadPackV2(
    private val input: InputStream,
    private val output: OutputStream,
) {

    fun readAdvertisement(): ServerCapabilities {
        val entries = mutableMapOf<String, String>()
        while (true) {
            when (val pkt = PktLine.read(input)) {
                is Pkt.Flush, is Pkt.ResponseEnd -> break
                is Pkt.Delim -> Unit
                is Pkt.Data -> {
                    val line = pkt.text().trim()
                    if (line.isEmpty()) continue
                    // A server refusing outright says so in an ERR packet.
                    // Its own words beat a generic version complaint.
                    if (line.startsWith("ERR ")) {
                        throw IOException("the server said: ${line.removePrefix("ERR ")}")
                    }
                    // The version line is "version 2" (space); capability lines are
                    // "key=value" or a bare "key".
                    val normalised = if (line.startsWith("version ")) {
                        "version=" + line.removePrefix("version ")
                    } else {
                        line
                    }
                    entries[normalised.substringBefore('=')] =
                        normalised.substringAfter('=', "")
                }
            }
        }

        val version = entries["version"]
        if (version != "2") {
            throw IOException(
                "the server offered protocol version ${version ?: "0"}; " +
                    "Strohhalm requires version 2 (git 2.18 or newer)"
            )
        }
        if (!entries.containsKey("fetch")) {
            throw IOException("the server does not advertise the fetch command")
        }

        val format = entries["object-format"]?.takeIf { it.isNotEmpty() } ?: "sha1"
        return ServerCapabilities(entries, ObjectHash.fromConfigName(format))
    }

    /**
     * Every ref under `refs/`, plus `HEAD`.
     *
     * The `refs/` prefix is deliberately broad: a mirror that tracked only head
     * refs is how backups end up quietly incomplete, so branches, tags and notes
     * are all requested in one call. `HEAD` needs its own prefix — ls-refs
     * filters strictly, and without the symref answer local HEAD could only be
     * guessed, breaking `git clone` from any mirror whose default branch is not
     * the guess.
     *
     * `unborn` is deliberately *not* sent: servers before git 2.30 die on the
     * unknown keyword, and an unborn HEAD names no object a mirror could fetch.
     */
    fun lsRefs(caps: ServerCapabilities): List<RemoteRef> {
        writeCommand(
            "ls-refs",
            caps,
            listOf("peel", "symrefs", "ref-prefix HEAD", "ref-prefix refs/"),
        )

        val refs = mutableListOf<RemoteRef>()
        while (true) {
            when (val pkt = PktLine.read(input)) {
                is Pkt.Flush, is Pkt.ResponseEnd -> break
                is Pkt.Delim -> Unit
                is Pkt.Data -> {
                    val line = pkt.text().trim()
                    if (line.isEmpty()) continue
                    val fields = line.split(' ')
                    if (fields.size < 2) continue
                    refs += RemoteRef(
                        objectId = fields[0],
                        name = fields[1],
                        symrefTarget = fields.firstOrNull { it.startsWith("symref-target:") }
                            ?.removePrefix("symref-target:"),
                        peeled = fields.firstOrNull { it.startsWith("peeled:") }
                            ?.removePrefix("peeled:"),
                    )
                }
            }
        }
        return refs
    }

    /**
     * A v2 command: the command line and capabilities, a delimiter, then the
     * arguments, then a flush.
     *
     * `object-format` must be echoed back or a SHA-256 server will refuse the
     * request — the negotiation is two-sided, not an announcement.
     */
    private fun writeCommand(
        command: String,
        caps: ServerCapabilities,
        arguments: List<String>,
    ) {
        PktLine.writeString(output, "command=$command\n")
        PktLine.writeString(output, "object-format=${caps.objectHash.configName}\n")
        PktLine.writeDelim(output)
        arguments.forEach { PktLine.writeString(output, "$it\n") }
        PktLine.writeFlush(output)
        output.flush()
    }
}
