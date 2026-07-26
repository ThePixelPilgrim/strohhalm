package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.MirrorProgress
import java.io.File
import java.io.InputStream

/** What a pack turned into on disk. */
data class PackResult(
    /** `pack-<checksum>`, without an extension. */
    val packName: String,
    val objectCount: Int,
    val bytes: Long,
)

/**
 * Turns a packfile datastream into a `.pack` plus its `.idx`.
 *
 * **This interface is the seam.** [KotlinPackIndexer] is the pure-JVM
 * implementation; a Rust implementation calling gitoxide's
 * `gix_pack::bundle::write::write_to_directory` would drop in here without
 * anything above this interface changing. The engine chose pure JVM so the app
 * ships no native code — see the design's distribution reasoning — and this
 * boundary is what keeps that a reversible decision.
 */
interface PackIndexer {
    fun consume(
        pack: InputStream,
        hash: ObjectHash,
        objectsDir: File,
        progress: MirrorProgress?,
    ): PackResult
}
