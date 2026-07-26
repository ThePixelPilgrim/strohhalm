package de.nereide.strohhalm.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SyncErrors
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddRepoUiState(
    val url: String = "",
    val name: String = "",
    val probing: Boolean = false,
    val fingerprint: String? = null,
    val errorCode: String? = null,
    val errorDetail: String? = null,
    val errorDiagnostic: String? = null,
    val saved: Boolean = false,
)

/**
 * Adding a repository is a two-phase flow: probe the server for its host key,
 * show the fingerprint for confirmation, and only then create the row. Nothing
 * is persisted until the user has accepted the key.
 */
class AddRepoViewModel(
    private val repository: RepoRepository,
    private val mirror: GitMirror,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddRepoUiState())
    val uiState: StateFlow<AddRepoUiState> = _uiState.asStateFlow()

    fun setUrl(value: String) {
        _uiState.value = _uiState.value.copy(url = value, errorCode = null)
    }

    fun setName(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun probe() {
        val url = _uiState.value.url.trim()
        if (url.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            probing = true,
            errorCode = null,
            errorDetail = null,
            errorDiagnostic = null,
        )
        viewModelScope.launch {
            mirror.probeHostKey(url)
                .onSuccess { fingerprint ->
                    _uiState.value = _uiState.value.copy(probing = false, fingerprint = fingerprint)
                }
                .onFailure { t ->
                    val error = SyncErrors.fromException(t)
                    _uiState.value = _uiState.value.copy(
                        probing = false,
                        errorCode = error.code.name,
                        errorDetail = error.detail,
                        errorDiagnostic = error.diagnostic,
                    )
                }
        }
    }

    fun confirmFingerprint() {
        val state = _uiState.value
        val fingerprint = state.fingerprint ?: return
        viewModelScope.launch {
            repository.add(
                displayName = state.name.trim(),
                remoteUrl = state.url.trim(),
                hostKeyFingerprint = fingerprint
            )
            _uiState.value = state.copy(fingerprint = null, saved = true)
        }
    }

    fun dismissFingerprint() {
        _uiState.value = _uiState.value.copy(fingerprint = null)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                AddRepoViewModel(
                    repository = this.appContainer().repoRepository,
                    mirror = this.appContainer().gitMirror
                )
            }
        }
    }
}
