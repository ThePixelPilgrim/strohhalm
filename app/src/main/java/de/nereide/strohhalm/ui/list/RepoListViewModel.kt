package de.nereide.strohhalm.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SyncRunner
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RepoListUiState(
    val repos: List<Repo> = emptyList(),
    val loading: Boolean = true,
)

class RepoListViewModel(
    repository: RepoRepository,
    private val syncRunner: SyncRunner,
) : ViewModel() {

    val uiState: StateFlow<RepoListUiState> = repository.observeAll()
        .map { repos -> RepoListUiState(repos = repos, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RepoListUiState())

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /**
     * Runs in the ViewModel rather than a background worker on purpose, for now:
     * a worker would hide failures behind the scheduler, and this build exists to
     * make failures visible on the device.
     */
    fun syncAll() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                syncRunner.syncAll()
            } finally {
                _syncing.value = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                RepoListViewModel(
                    repository = this.appContainer().repoRepository,
                    syncRunner = this.appContainer().syncRunner
                )
            }
        }
    }
}
