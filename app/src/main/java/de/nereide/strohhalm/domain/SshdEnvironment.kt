package de.nereide.strohhalm.domain

import org.apache.sshd.common.util.io.PathUtils
import java.io.File

/**
 * Gives Apache MINA SSHD a home directory. **Must run before any SSHD class is
 * touched**, which in practice means from `Application.onCreate`.
 *
 * SSHD resolves `~` through a chain of lazily-initialised static holders:
 *
 *     ClientBuilder.<clinit>
 *       -> DefaultConfigFileHostEntryResolver.<clinit>
 *         -> HostConfigEntry$LazyDefaultConfigFileHolder.<clinit>
 *           -> PublicKeyEntry$LazyDefaultKeysFolderHolder.<clinit>
 *             -> PathUtils$LazyDefaultUserHomeFolderHolder.<clinit>
 *
 * Android has no `user.home`, so that last holder throws
 * `IllegalArgumentException` during class initialisation. Two consequences make
 * this nastier than an ordinary error:
 *
 * 1. The symptom is unrelated to the cause. The SSH session dies mid-handshake
 *    and JGit reports "remote hung up unexpectedly" — which reads like a network
 *    or server fault.
 * 2. **Class-initialisation failure is permanent for the process.** Once those
 *    holders have failed, every later attempt throws `NoClassDefFoundError`
 *    instead. Setting the resolver afterwards cannot repair them; the app has to
 *    be restarted. Hence installing this before anything else, not lazily.
 *
 * SSHD provides the hook precisely for Android, and says so in the exception
 * message it throws.
 */
object SshdEnvironment {

    @Volatile
    private var home: File? = null

    /**
     * Points SSHD's `~` at a directory inside app-internal storage. Idempotent.
     *
     * Internal storage, not the backup folder: SSHD may write `known_hosts` and
     * similar here, and none of it belongs in a directory the user browses and
     * copies to a computer.
     */
    @Synchronized
    fun install(filesDir: File) {
        if (home != null) return
        val dir = File(filesDir, "sshd-home").apply { mkdirs() }
        File(dir, ".ssh").mkdirs()
        PathUtils.setUserHomeFolderResolver { dir.toPath() }
        home = dir
    }

    /** The installed home directory. */
    fun homeDir(): File =
        home ?: error("SshdEnvironment.install() must be called before using SSH")
}
