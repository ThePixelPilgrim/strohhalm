package de.nereide.strohhalm.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.data.SyncStatus
import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import de.nereide.strohhalm.domain.SyncProgress
import de.nereide.strohhalm.domain.SyncRunner
import de.nereide.strohhalm.domain.archive.ArchiveNames
import de.nereide.strohhalm.domain.archive.ArchiveSpace
import de.nereide.strohhalm.domain.archive.ArchiveStore
import de.nereide.strohhalm.domain.archive.CacheSpace
import de.nereide.strohhalm.domain.archive.RefFingerprint
import de.nereide.strohhalm.domain.archive.reserveOrRefuse
import de.nereide.strohhalm.domain.git.MirrorRepository
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

class RepoDetailViewModel(
    private val id: Long,
    private val repository: RepoRepository,
    private val mirror: GitMirror,
    private val syncRunner: SyncRunner,
    private val archives: ArchiveStore,
    private val cacheSpace: CacheSpace,
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

    private val _shareState = MutableStateFlow<ShareState>(ShareState.Idle)
    val shareState: StateFlow<ShareState> = _shareState.asStateFlow()

    private var packJob: Job? = null

    fun share() {
        val current = repo.value ?: return
        val next = ShareRules.onShareRequested(
            syncing = syncRunner.running.value,
            everSynced = current.lastSyncAt != null,
        )
        _shareState.value = next
        if (next is ShareState.Packing) pack(current)
    }

    /** Archive the mirror as it stands, after a sync failed. */
    fun shareAnyway() {
        val current = repo.value ?: return
        _shareState.value = ShareState.Packing(0, 0)
        pack(current)
    }

    fun retrySync() {
        val current = repo.value ?: return
        _shareState.value = ShareRules.onRetry(everSynced = current.lastSyncAt != null)
        syncRunner.launchSyncOne(id)
    }

    /** Back, or Stop while packing. Stop means stop, not pause. */
    fun cancelShare() {
        packJob?.cancel()
        packJob = null
        _shareState.value = ShareState.Idle
    }

    /** Called once the share sheet has been launched. */
    fun shareConsumed() {
        _shareState.value = ShareState.Idle
    }

    private fun pack(current: Repo) {
        packJob?.cancel()
        packJob = viewModelScope.launch {
            val gitDir = File(current.localPath)
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val repository = MirrorRepository(gitDir)
                    // Before anything else, because every step below is happy
                    // to succeed on a mirror that is not there: `localRefs()`
                    // is total and returns an empty map, its fingerprint is a
                    // well-defined digest of nothing, and the space check reads
                    // the row's stored size rather than the filesystem. The
                    // user would see a share succeed. `ArchiveMaintenance`
                    // guards with the same `exists()` call for the same reason.
                    if (!repository.exists()) {
                        throw ArchiveRefused(
                            SyncError(
                                SyncErrorCode.PERMISSION_LOST,
                                "no mirror at ${gitDir.path} — " +
                                    "the folder is gone or no longer readable",
                            )
                        )
                    }
                    val fingerprint = RefFingerprint.of(repository.localRefs())
                    val slug = ArchiveNames.slugForMirror(gitDir)

                    archives.existing(slug, fingerprint) ?: run {
                        // Reserve, not merely ask: the reported figure includes
                        // other apps' caches, which only become free space once
                        // they are actually claimed.
                        ArchiveSpace.reserveOrRefuse(cacheSpace, current.sizeBytes)
                            ?.let { throw ArchiveRefused(it) }
                        runInterruptible {
                            archives.build(
                                slug = slug,
                                gitDir = gitDir,
                                fingerprint = fingerprint,
                                lastSyncAt = current.lastSyncAt ?: System.currentTimeMillis(),
                            ) { _, completed, total ->
                                _shareState.value = ShareState.Packing(completed, total)
                            }
                        }
                    }
                }
            }
            outcome
                .onSuccess { _shareState.value = ShareState.Ready(it) }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    _shareState.value = ShareState.Blocked(
                        error = (t as? ArchiveRefused)?.error ?: SyncErrors.fromException(t),
                        cancelled = false,
                        canShareAnyway = false,
                    )
                }
        }
    }

    private class ArchiveRefused(val error: SyncError) : Exception(error.detail)

    init {
        loadRefs()
        // Re-read the refs whenever a sync finishes, so the list reflects what
        // was just fetched without the user navigating away and back.
        viewModelScope.launch {
            var wasRunning = syncRunner.running.value
            syncRunner.running.collect { isRunning ->
                if (wasRunning && !isRunning) {
                    loadRefs()
                    val pending = _shareState.value
                    if (pending is ShareState.Waiting) {
                        val current = repository.observe(id).first()
                        val error = current?.lastErrorCode?.let {
                            SyncError(SyncErrorCode.valueOf(it), current.lastErrorDetail)
                        }
                        val next = ShareRules.onSyncFinished(
                            failed = if (current?.lastStatus == SyncStatus.OK) null else error,
                            cancelled = error?.code == SyncErrorCode.CANCELLED,
                            everSynced = current?.lastSyncAt != null,
                        )
                        _shareState.value = next
                        if (next is ShareState.Packing && current != null) pack(current)
                    }
                }
                wasRunning = isRunning
            }
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
                    syncRunner = this.appContainer().syncRunner,
                    archives = this.appContainer().archiveStore,
                    cacheSpace = this.appContainer().cacheSpace,
                )
            }
        }
    }
}
