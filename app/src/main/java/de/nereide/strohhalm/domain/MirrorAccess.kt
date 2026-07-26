package de.nereide.strohhalm.domain

import java.util.concurrent.atomic.AtomicReference

/**
 * Exclusive access to the mirror directories, held by whoever is touching them.
 *
 * Two activities write there and neither tolerates the other: a **fetch**
 * creates a temporary pack inside the mirror's `objects/pack` and then rewrites
 * refs, while an **archive** walks the same tree entry by entry and reads each
 * file once. Overlap them and the good case is a loud `ZipException` — a
 * temp file named `.pack` is stored uncompressed, so its CRC and its length are
 * read at different moments and disagree once it grows between them. The bad
 * case is silent: refs rewritten *after* their directory was zipped produce an
 * archive that checksums clean, records the pre-fetch fingerprint, and points
 * at objects it does not contain. It verifies and does not restore.
 *
 * The lock is **app-scoped rather than per-repository** because the thing on the
 * other side already is: [SyncRunner] is single-flight for the whole app, so a
 * finer lock would buy nothing and would only add ways to hold two of them.
 *
 * A failed acquire is never an error — it means the other side got there first,
 * and the caller waits or retries rather than reporting a fault.
 */
class MirrorAccess {

    enum class Owner { SYNC, ARCHIVE }

    private val holder = AtomicReference<Owner?>(null)

    /**
     * Takes the lock, or reports that someone else holds it.
     *
     * Fails even when [owner] is the current holder: this is not reentrant, and
     * a second archive over the same mirror is no safer than an archive over a
     * fetch.
     */
    fun tryAcquire(owner: Owner): Boolean = holder.compareAndSet(null, owner)

    /**
     * Releases the lock if [owner] holds it, and does nothing otherwise — so a
     * `finally` on a path that never acquired cannot free somebody else's hold.
     */
    fun release(owner: Owner) {
        holder.compareAndSet(owner, null)
    }
}
