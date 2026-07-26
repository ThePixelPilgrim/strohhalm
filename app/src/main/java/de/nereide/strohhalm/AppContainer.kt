package de.nereide.strohhalm

import android.content.Context
import android.os.storage.StorageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import de.nereide.strohhalm.data.SettingsRepository
import de.nereide.strohhalm.data.StrohhalmDatabase
import de.nereide.strohhalm.domain.DefaultRepoRepository
import de.nereide.strohhalm.domain.EncryptedSshKeyStore
import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.git.ProtocolMirror
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SshKeyStore
import de.nereide.strohhalm.domain.SyncRunner
import de.nereide.strohhalm.domain.archive.ArchiveNames
import de.nereide.strohhalm.domain.archive.ArchiveStore
import de.nereide.strohhalm.work.SyncForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/** Service-locator container exposing the app's singletons. No Hilt. */
interface AppContainer {
    val settingsRepository: SettingsRepository
    val repoRepository: RepoRepository
    val sshKeyStore: SshKeyStore
    val gitMirror: GitMirror
    val syncRunner: SyncRunner
    val archiveStore: ArchiveStore
    val archiveMaintenance: ArchiveMaintenance

    /**
     * Scope living as long as the process — used for fire-and-forget work that
     * must not die with a ViewModel or screen.
     */
    val applicationScope: CoroutineScope

    /**
     * What the system could free for us, not merely what is unused right now.
     * The difference is other apps' reclaimable caches; asking for the smaller
     * figure would refuse builds the device could comfortably do.
     */
    suspend fun allocatableCacheBytes(): Long
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings"
)

class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext = context.applicationContext

    override val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext.settingsDataStore)
    }

    override val sshKeyStore: SshKeyStore by lazy {
        EncryptedSshKeyStore(appContext.filesDir)
    }

    override val gitMirror: GitMirror by lazy {
        ProtocolMirror(keyPairProvider = { sshKeyStore.keyPair() })
    }

    override val repoRepository: RepoRepository by lazy {
        DefaultRepoRepository(
            dao = StrohhalmDatabase.getInstance(appContext).repoDao(),
            storageRoot = { settingsRepository.requireStorageRoot() },
            clock = System::currentTimeMillis
        )
    }

    override val syncRunner: SyncRunner by lazy {
        SyncRunner(
            repos = repoRepository,
            mirror = gitMirror,
            scope = applicationScope,
            foreground = SyncForegroundService.hold(appContext),
        )
    }

    override val archiveStore: ArchiveStore by lazy {
        ArchiveStore(File(appContext.cacheDir, "archives"))
    }

    override val archiveMaintenance: ArchiveMaintenance by lazy {
        ArchiveMaintenance(
            store = archiveStore,
            mirrors = {
                repoRepository.all().map {
                    ArchiveNames.slugForMirror(File(it.localPath)) to File(it.localPath)
                }
            },
            scope = applicationScope,
        )
    }

    /**
     * What the system could free for us, not merely what is unused right now.
     * The difference is other apps' reclaimable caches; asking for the smaller
     * figure would refuse builds the device could comfortably do.
     *
     * Both APIs are API 26, which is minSdk. The fallback covers a device that
     * refuses the query rather than letting a precheck become a crash.
     */
    override suspend fun allocatableCacheBytes(): Long = withContext(Dispatchers.IO) {
        runCatching {
            val storage = appContext.getSystemService(StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(appContext.cacheDir))
        }.getOrElse { appContext.cacheDir.usableSpace }
    }
}
