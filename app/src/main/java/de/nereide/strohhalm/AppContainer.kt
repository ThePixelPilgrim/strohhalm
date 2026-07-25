package de.nereide.strohhalm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Service-locator container exposing the app's singletons. No Hilt. */
interface AppContainer {
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
}
