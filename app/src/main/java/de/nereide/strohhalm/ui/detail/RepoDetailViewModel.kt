package de.nereide.strohhalm.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SyncProgress
import de.nereide.strohhalm.domain.SyncRunner
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RepoDetailViewModel(
    private val id: Long,
    private val repository: RepoRepository,
    private val mirror: GitMirror,
    private val syncRunner: SyncRunner,
) : ViewModel() {

    val repo: StateFlow<Repo?> = repository.observe(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** Owned by the runner: the sync outlives this screen. */
    val syncing: StateFlow<Boolean> = syncRunner.running
    val progress: StateFlow<SyncProgress?> = syncRunner.progress

    /** A freshly probed fingerprint awaiting the user's confirmation. */
    private val _pendingHostKey = MutableStateFlow<String?>(null)
    val pendingHostKey: StateFlow<String?> = _pendingHostKey.asStateFlow()

    private val _refs = MutableStateFlow<List<String>>(emptyList())
    val refs: StateFlow<List<String>> = _refs.asStateFlow()

    init {
        loadRefs()
        // Re-read the refs whenever a sync finishes, so the list reflects what
        // was just fetched without the user navigating away and back.
        viewModelScope.launch {
            syncRunner.running.collect { running -> if (!running) loadRefs() }
        }
    }

    fun cancelSync() = syncRunner.cancel()

    fun syncNow() {
        syncRunner.launchSyncOne(id)
        // Refs are re-read when the runner goes idle again, below.
    }

    /**
     * Reads the refs straight from the mirror rather than a stored count. Seeing
     * branches and tags that were never checked out is the visible proof that
     * `--mirror` captured everything, which a number alone does not give.
     */
    private fun loadRefs() {
        viewModelScope.launch {
            val current = repository.observe(id).first() ?: return@launch
            _refs.value = withContext(Dispatchers.IO) {
                mirror.refNames(File(current.localPath))
            }
        }
    }

    /**
     * Asks the server for its host key again. Used when the pinned key no longer
     * matches — a server rebuild is legitimate, so there has to be a way forward
     * that is not "delete and re-add", but it still requires the user to look at
     * the new fingerprint and accept it.
     */
    fun recheckHostKey() {
        viewModelScope.launch {
            val current = repository.observe(id).first() ?: return@launch
            mirror.probeHostKey(current.remoteUrl)
                .onSuccess { _pendingHostKey.value = it }
        }
    }

    fun confirmNewHostKey() {
        val fingerprint = _pendingHostKey.value ?: return
        viewModelScope.launch {
            repository.updateHostKey(id, fingerprint)
            _pendingHostKey.value = null
        }
    }

    fun dismissNewHostKey() {
        _pendingHostKey.value = null
    }

    fun delete(alsoDeleteFiles: Boolean) {
        viewModelScope.launch {
            val current = repository.observe(id).first()
            repository.delete(id)
            if (alsoDeleteFiles && current != null) {
                withContext(Dispatchers.IO) {
                    runCatching { File(current.localPath).deleteRecursively() }
                }
            }
            _deleted.value = true
        }
    }

    companion object {
        fun factory(id: Long) = viewModelFactory {
            initializer {
                RepoDetailViewModel(
                    id = id,
                    repository = this.appContainer().repoRepository,
                    mirror = this.appContainer().gitMirror,
                    syncRunner = this.appContainer().syncRunner
                )
            }
        }
    }
}
