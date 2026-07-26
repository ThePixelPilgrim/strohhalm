package de.nereide.strohhalm.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SyncProgress
import de.nereide.strohhalm.domain.SyncRunner
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

    /**
     * Sync state comes from the runner, not this ViewModel: the work outlives
     * the screen, so leaving the list must not stop it or lose its progress.
     */
    val syncing: StateFlow<Boolean> = syncRunner.running
    val progress: StateFlow<SyncProgress?> = syncRunner.progress

    fun syncAll() = syncRunner.launchSyncAll()

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
