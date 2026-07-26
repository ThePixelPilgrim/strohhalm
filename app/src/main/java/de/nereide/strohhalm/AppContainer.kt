package de.nereide.strohhalm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import de.nereide.strohhalm.data.SettingsRepository
import de.nereide.strohhalm.data.StrohhalmDatabase
import de.nereide.strohhalm.domain.DefaultRepoRepository
import de.nereide.strohhalm.domain.EncryptedSshKeyStore
import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.JGitMirror
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SshKeyStore
import de.nereide.strohhalm.domain.SyncRunner
import de.nereide.strohhalm.work.SyncForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Service-locator container exposing the app's singletons. No Hilt. */
interface AppContainer {
    val settingsRepository: SettingsRepository
    val repoRepository: RepoRepository
    val sshKeyStore: SshKeyStore
    val gitMirror: GitMirror
    val syncRunner: SyncRunner

    /**
     * Scope living as long as the process — used for fire-and-forget work that
     * must not die with a ViewModel or screen.
     */
    val applicationScope: CoroutineScope
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
        JGitMirror(keyPairProvider = { sshKeyStore.keyPair() })
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
}
