package de.nereide.strohhalm.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventDao
import de.nereide.strohhalm.domain.DefaultSyncLog
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * The filter defaults to entries that received data because that is the
 * question the screen exists to answer. The 30-day bound on the query is a
 * second line behind the prune in [DefaultSyncLog], so an entry the prune
 * has not reached yet still stays off the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(
    dao: SyncEventDao,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _receivedOnly = MutableStateFlow(true)
    val receivedOnly: StateFlow<Boolean> = _receivedOnly.asStateFlow()

    val events: StateFlow<List<SyncEvent>> = _receivedOnly
        .flatMapLatest { only ->
            dao.observeRecent(since = clock() - DefaultSyncLog.RETENTION_MILLIS, receivedOnly = only)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setReceivedOnly(only: Boolean) {
        _receivedOnly.value = only
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ActivityViewModel(dao = this.appContainer().syncEventDao) }
        }
    }
}
