package de.nereide.strohhalm.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Persists user settings via DataStore preferences: how often mirrors refresh,
 * where they are stored, and whether failures raise a notification.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /**
     * An absent or unrecognised stored value falls back to the default rather
     * than throwing, so a downgrade that removes an interval cannot brick the
     * settings screen.
     */
    val syncInterval: Flow<SyncInterval> = dataStore.data.map { prefs ->
        prefs[KEY_SYNC_INTERVAL]
            ?.let { name -> SyncInterval.entries.firstOrNull { it.name == name } }
            ?: DEFAULT_SYNC_INTERVAL
    }

    suspend fun setSyncInterval(interval: SyncInterval) {
        dataStore.edit { prefs -> prefs[KEY_SYNC_INTERVAL] = interval.name }
    }

    /** Test seam for writing a value no current enum constant matches. */
    internal suspend fun setSyncIntervalRaw(name: String) {
        dataStore.edit { prefs -> prefs[KEY_SYNC_INTERVAL] = name }
    }

    /** Null until the user has picked a directory during onboarding. */
    val storageRoot: Flow<String?> = dataStore.data.map { prefs -> prefs[KEY_STORAGE_ROOT] }

    suspend fun setStorageRoot(dir: File) {
        dataStore.edit { prefs -> prefs[KEY_STORAGE_ROOT] = dir.path }
    }

    /**
     * The configured root, or an error. Callers that need a path — the worker,
     * the repository — cannot proceed without one, and failing here is clearer
     * than silently mirroring into a default location the user never chose.
     */
    suspend fun requireStorageRoot(): File =
        storageRoot.first()?.let(::File)
            ?: error("no storage root configured")

    val notifyOnFailure: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NOTIFY_ON_FAILURE] ?: DEFAULT_NOTIFY_ON_FAILURE
    }

    suspend fun setNotifyOnFailure(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_NOTIFY_ON_FAILURE] = enabled }
    }

    companion object {
        val DEFAULT_SYNC_INTERVAL: SyncInterval = SyncInterval.H1
        const val DEFAULT_NOTIFY_ON_FAILURE: Boolean = true

        private val KEY_SYNC_INTERVAL = stringPreferencesKey("sync_interval")
        private val KEY_STORAGE_ROOT = stringPreferencesKey("storage_root")
        private val KEY_NOTIFY_ON_FAILURE = booleanPreferencesKey("notify_on_failure")
    }
}
