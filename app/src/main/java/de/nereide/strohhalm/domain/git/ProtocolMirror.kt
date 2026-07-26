package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.MirrorOutcome
import de.nereide.strohhalm.domain.MirrorProgress
import de.nereide.strohhalm.domain.ProbeRejectedException
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPair
import java.time.Duration

/**
 * The mirror engine: protocol v2 over SSH, hash-agnostic.
 *
 * Replaces the JGit implementation. The `GitMirror` contract is unchanged, so
 * the scheduler, the foreground service, the notification policy and the UI are
 * untouched by the swap.
 *
 * This package is the only place in `src/main` allowed to import MINA SSHD, and
 * library exceptions are mapped to `SyncError` before they leave it.
 */
class ProtocolMirror(
    private val keyPairProvider: suspend () -> KeyPair,
    private val indexer: PackIndexer = KotlinPackIndexer(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GitMirror {

    override suspend fun sync(
        remoteUrl: String,
        destination: File,
        pinnedFingerprint: String?,
        progress: MirrorProgress?,
    ): MirrorOutcome = withContext(io) {
        // Storage first, before the network and before the keystore. A backup
        // folder on a removable card that is not mounted is the user's problem
        // to fix, and it is cheap to detect — unlike everything downstream of it.
        unusableDestination(destination)?.let { return@withContext MirrorOutcome.Failure(it) }

        val keyPair = keyPairProvider()
        runCatching {
            // runInterruptible, not a plain call: the transfer blocks in socket
            // reads that no coroutine can unwind. Without the thread interrupt,
            // cancelling would clear the UI while bytes kept flowing.
            runInterruptible { mirror(remoteUrl, destination, pinnedFingerprint, keyPair, progress) }
        }.getOrElse { t ->
            // runCatching catches everything, including the CancellationException
            // that stopping the sync depends on.
            if (t is CancellationException) throw t
            MirrorOutcome.Failure(SyncErrors.fromException(t))
        }
    }

    /**
     * Why [destination] cannot be mirrored into, or null when it can.
     *
     * This exists because of a real device failure: a mirror onto an SD card
     * that was not mounted reported `code=UNKNOWN` with a raw
     * `.../yamiro.git/HEAD: open failed: ENOENT`, *after* unlocking the key and
     * opening an SSH session to Codeberg. Every part of that was misleading —
     * HEAD was not the problem, the remote was not the problem, and the one
     * thing the user could have acted on ("your card is not in the phone") was
     * the one thing the message did not say.
     *
     * Creation is attempted rather than merely tested: a first sync into a
     * folder that does not exist yet is legitimate, and must keep working.
     *
     * When creation does fail, the nearest surviving ancestor is reported. On
     * Android an unmounted volume takes its whole subtree with it, so an answer
     * of `/storage` means the card is gone, whereas the backup root still
     * existing means only the folder was removed. That distinction is the
     * difference between "insert your card" and "re-pick your backup folder",
     * and it costs one loop to provide.
     */
    private fun unusableDestination(destination: File): SyncError? {
        val parent = destination.parentFile ?: return null
        if (parent.isDirectory || parent.mkdirs()) return null

        var nearest: File? = parent.parentFile
        while (nearest != null && !nearest.isDirectory) nearest = nearest.parentFile

        return SyncError(
            SyncErrorCode.PERMISSION_LOST,
            "the backup folder $parent is not reachable. The nearest folder that " +
                "does exist is ${nearest?.path ?: "none"}. If the backup is on a " +
                "removable card, check that the card is inserted and mounted.",
        )
    }

    private fun mirror(
        remoteUrl: String,
        destination: File,
        pinnedFingerprint: String?,
        keyPair: KeyPair,
        progress: MirrorProgress?,
    ): MirrorOutcome {
        val mirror = MirrorRepository(destination)

        // A clone that failed part-way leaves a directory with no HEAD. Removing
        // it is safe — without HEAD it was never a usable repository — and
        // without this a single failed attempt wedges the mirror permanently.
        if (destination.exists() && !mirror.exists()) destination.deleteRecursively()
        destination.parentFile?.mkdirs()

        UploadPackChannel(
            remote = GitRemote.parse(remoteUrl),
            keyPair = keyPair,
            pinnedFingerprint = pinnedFingerprint,
            capture = false,
            timeout = TIMEOUT,
        ).use { channel ->
            try {
                channel.open()
            } catch (t: Throwable) {
                // A refused host key surfaces as a bare transport failure —
                // SSHD closes the session without propagating the cause — so
                // the recorded reason must be preferred over whatever the
                // library threw, exactly as the JGit engine did.
                channel.rejection?.let { return MirrorOutcome.Failure(it) }
                throw t
            }

            val protocol = UploadPackV2(channel.input, channel.output)
            val caps = protocol.readAdvertisement()
            if (!mirror.exists()) mirror.initialise(caps.objectHash)

            val refs = protocol.lsRefs(caps)
            if (refs.isEmpty()) {
                // An empty remote is a valid mirror with nothing in it. The
                // layout above was still written, so the folder exists and a
                // later first commit upstream syncs into it as a plain fetch.
                return MirrorOutcome.Success(sizeBytes(destination), 0)
            }

            val haves = mirror.localRefs().values.distinct()
            val wants = refs.map { it.objectId }.distinct()

            // Steady state: nothing moved upstream since the last sync. Skip
            // the fetch entirely — with `done` negotiation a server always
            // sends a pack section, and at the 15-minute floor an unconditional
            // fetch would accumulate tens of thousands of empty packs a year,
            // each one another file for git to open.
            val known = haves.toHashSet()
            if (wants.all { it in known }) {
                mirror.writeRefs(refs)
                return MirrorOutcome.Success(sizeBytes(destination), mirror.refNames().size)
            }

            val pack = protocol.fetch(caps, wants, haves) { line ->
                progress?.update(line, 0, 0)
            }
            indexer.consume(pack, caps.objectHash, mirror.objectsDir(), progress)

            mirror.writeRefs(refs)
            return MirrorOutcome.Success(sizeBytes(destination), mirror.refNames().size)
        }
    }

    /**
     * Completes a handshake and returns the host key fingerprint, running no git
     * operation beyond reading the advertisement.
     *
     * The key is read before authentication, so a fingerprint can be captured
     * even when the repository is unreachable. Reporting success on that basis
     * alone is what once let a broken remote be added as if it worked, so a
     * failure is still surfaced — enriched with whatever the server wrote to
     * stderr, which this engine can read on the same connection.
     */
    override suspend fun probeHostKey(remoteUrl: String): Result<String> = withContext(io) {
        val keyPair = keyPairProvider()
        runCatching {
            UploadPackChannel(
                remote = GitRemote.parse(remoteUrl),
                keyPair = keyPair,
                pinnedFingerprint = null,
                capture = true,
                timeout = TIMEOUT,
            ).use { channel ->
                runCatching {
                    channel.open()
                    UploadPackV2(channel.input, channel.output).readAdvertisement()
                }.onFailure { failure ->
                    val fingerprint = channel.observedHostKey ?: throw failure
                    val message = channel.stderrText()
                    if (message.isBlank()) throw failure
                    throw ProbeRejectedException(fingerprint, message, failure)
                }
                channel.observedHostKey ?: error("the server presented no host key")
            }
        }
    }

    override fun refNames(destination: File): List<String> =
        runCatching { MirrorRepository(destination).refNames() }.getOrDefault(emptyList())

    override fun sizeBytes(destination: File): Long {
        if (!destination.isDirectory) return 0L
        return destination.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private companion object {
        /**
         * Bounds connect, auth and channel open. The read path re-arms per read,
         * so a slow transfer is fine as long as bytes keep flowing — the failure
         * this guards is a silently dropped connection, which would otherwise
         * block a read forever.
         */
        val TIMEOUT: Duration = Duration.ofSeconds(300)
    }
}
