package de.nereide.strohhalm.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.git.GitRemote
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddRepoUiState(
    val url: String = "",
    val name: String = "",
    val invalidUrl: Boolean = false,
    /** A save is in flight; the button stays down so a double tap cannot add twice. */
    val saving: Boolean = false,
    /** The save itself failed — e.g. no storage root configured. */
    val saveError: String? = null,
    /** Set once the row exists; navigation to its detail view follows. */
    val savedId: Long? = null,
)

/**
 * Adding saves immediately and touches no network. Only the URL's *shape* is
 * checked here; the server itself is verified afterwards, in the background,
 * from the detail view — so a repository can be added before its server is
 * reachable or its key installed, and the user is never parked behind a
 * "Contacting the server…" spinner.
 */
class AddRepoViewModel(
    private val repository: RepoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddRepoUiState())
    val uiState: StateFlow<AddRepoUiState> = _uiState.asStateFlow()

    fun setUrl(value: String) {
        _uiState.value = _uiState.value.copy(url = value, invalidUrl = false)
    }

    fun setName(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun add() {
        val state = _uiState.value
        if (state.saving) return
        val url = state.url.trim()
        if (url.isEmpty()) return
        if (runCatching { GitRemote.parse(url) }.isFailure) {
            _uiState.value = state.copy(invalidUrl = true)
            return
        }
        _uiState.value = state.copy(saving = true, saveError = null)
        viewModelScope.launch {
            // The save can genuinely fail — no storage root configured, say —
            // and an uncaught throw here would take the whole process down.
            runCatching {
                repository.add(
                    displayName = state.name.trim(),
                    remoteUrl = url,
                    hostKeyFingerprint = null,
                )
            }.onSuccess { id ->
                _uiState.value = _uiState.value.copy(saving = false, savedId = id)
            }.onFailure { t ->
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    saveError = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                AddRepoViewModel(repository = this.appContainer().repoRepository)
            }
        }
    }
}
