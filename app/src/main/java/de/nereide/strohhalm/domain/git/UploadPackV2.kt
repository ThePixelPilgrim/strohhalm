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
}
