package de.nereide.strohhalm.domain

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader

/**
 * JGit consults `~/.gitconfig`, `/etc/gitconfig` and a JGit-wide config on first
 * use. Android has no user home and no such files, and the default lookups fail
 * or block. This reader delegates everything else to the platform default but
 * serves empty, permanently up-to-date configs instead.
 *
 * [install] must run once before any JGit API is touched.
 */
class AndroidSystemReader(private val delegate: SystemReader) : SystemReader() {

    override fun getHostname(): String = "android"

    override fun getenv(variable: String?): String? = delegate.getenv(variable)

    override fun getProperty(key: String?): String? = delegate.getProperty(key)

    override fun getCurrentTime(): Long = delegate.currentTime

    override fun getTimezone(whenMillis: Long): Int = delegate.getTimezone(whenMillis)

    override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig =
        EmptyConfig(parent, fs)

    override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig =
        EmptyConfig(parent, fs)

    override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig =
        EmptyConfig(parent, fs)

    private class EmptyConfig(parent: Config?, fs: FS?) : FileBasedConfig(parent, null, fs) {
        override fun load() = Unit
        override fun isOutdated(): Boolean = false
        override fun save() = Unit
    }

    companion object {
        @Volatile
        private var installed = false

        /** Idempotent; safe to call from both the worker and the UI process path. */
        @Synchronized
        fun install() {
            if (installed) return
            SystemReader.setInstance(AndroidSystemReader(SystemReader.getInstance()))
            installed = true
        }
    }
}
