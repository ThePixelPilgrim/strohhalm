package de.nereide.strohhalm.domain

import java.io.File

/** What the probe wrote, and where it believes it wrote it. */
data class ProbeReport(
    val nonce: String,
    val documentId: String,
    val derivedPath: String,
    val filePath: String,
    val content: String,
)

/**
 * Writes a small, self-describing marker file into a candidate backup folder so
 * that the derived path can be checked **from outside the app**.
 *
 * [StorageRootResolver.isWritable] proves only that *some* directory was
 * writable. It cannot prove the directory is the one the user picked: if the
 * document-id-to-path derivation silently produced a different real, writable
 * location, that check would still pass and onboarding would accept the wrong
 * folder.
 *
 * This probe closes that hole. The file records the path the app *thinks* it
 * wrote to, alongside a nonce. Finding the file elsewhere on the filesystem —
 * or not at all — contradicts the app's belief directly. It also confirms the
 * third property that matters for backups: that the location is reachable from
 * outside the app at all, which is the entire reason MANAGE_EXTERNAL_STORAGE is
 * requested.
 *
 * The file is deliberately left behind, plainly named and unhidden, so it can be
 * found with a file manager rather than only with adb.
 */
object StorageProbe {

    const val MARKER = "STROHHALM-PATH-PROBE"
    const val FILE_NAME = "strohhalm-path-probe.txt"

    fun render(
        nonce: String,
        documentId: String,
        derivedPath: String,
        writtenAt: String,
    ): String = buildString {
        appendLine(MARKER)
        appendLine("nonce=$nonce")
        appendLine("documentId=$documentId")
        appendLine("derivedPath=$derivedPath")
        appendLine("writtenAt=$writtenAt")
        appendLine()
        appendLine("If you are reading this file, the path above should match where")
        appendLine("you found it. If it does not, Strohhalm derived the wrong folder.")
        appendLine("This file is safe to delete.")
    }

    /**
     * Writes the marker into [dir], creating it if needed, then reads it back.
     *
     * Returns a failure rather than throwing, so onboarding can show the reason
     * instead of crashing on a folder Android declined to hand over.
     */
    fun write(
        dir: File,
        documentId: String,
        nonce: String,
        writtenAt: String,
    ): Result<ProbeReport> = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) {
            error("could not create or open $dir")
        }
        val target = File(dir, FILE_NAME)
        val content = render(nonce, documentId, dir.absolutePath, writtenAt)
        target.writeText(content)

        // Read back rather than trusting the write: a path can accept a write and
        // still not persist it, which is precisely the failure worth catching here.
        val readBack = target.readText()
        check(readBack == content) { "probe file did not read back identical" }

        ProbeReport(
            nonce = nonce,
            documentId = documentId,
            derivedPath = dir.absolutePath,
            filePath = target.absolutePath,
            content = readBack,
        )
    }
}
