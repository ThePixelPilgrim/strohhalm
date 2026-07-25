package de.nereide.strohhalm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import de.nereide.strohhalm.data.SettingsRepository
import de.nereide.strohhalm.domain.EncryptedSshKeyStore
import de.nereide.strohhalm.domain.SshKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Service-locator container exposing the app's singletons. No Hilt. */
interface AppContainer {
    val settingsRepository: SettingsRepository
    val sshKeyStore: SshKeyStore

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
}
