package de.nereide.strohhalm.domain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Minimal pure-JVM in-memory [DataStore] used to back a real
 * [de.nereide.strohhalm.data.SettingsRepository] in tests without touching the
 * filesystem or Android framework classes.
 */
class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {

    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
        }
}
