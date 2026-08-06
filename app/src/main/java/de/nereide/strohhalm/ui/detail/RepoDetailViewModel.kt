package de.nereide.strohhalm.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.data.SyncStatus
import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.MirrorAccess
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SshKeyStore
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
import de.nereide.strohhalm.domain.git.GitRemote
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
import kotlinx.coroutines.flow.map
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
    private val access: MirrorAccess,
    private val keys: SshKeyStore,
) : ViewModel() {

    val repo: StateFlow<Repo?> = repository.observe(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The remote's host, for the key-setup card. Derived here rather than in
     * the screen: parsing the URL is a domain concern, and the screen should
     * not reach into the transport package for it.
     */
    val remoteHost: StateFlow<String?> = repository.observe(id)
        .map { current ->
            current?.remoteUrl?.let { url ->
                runCatching { GitRemote.parse(url).host }.getOrNull()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** Owned by the runner: the sync outlives this screen. */
    val syncing: StateFlow<Boolean> = syncRunner.running
    val progress: StateFlow<SyncProgress?> = syncRunner.progress

    /** A probed fingerprint awaiting the user's confirmation. */
    data class PendingHostKey(
        val fingerprint: String,
        /** The server refused auth — likely the public key is not installed yet. */
        val authFailed: Boolean,
        /** No key was pinned before: accepting also starts the first sync. */
        val firstTrust: Boolean,
    )

    private val _pendingHostKey = MutableStateFlow<PendingHostKey?>(null)
    val pendingHostKey: StateFlow<PendingHostKey?> = _pendingHostKey.asStateFlow()

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    private val _probeError = MutableStateFlow<SyncError?>(null)
    val probeError: StateFlow<SyncError?> = _probeError.asStateFlow()

    /** The public key line, for the auth-failure card's copy action. */
    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    /** The edit-remote dialog's contents while it is open; null when closed. */
    private val _editUrl = MutableStateFlow<String?>(null)
    val editUrl: StateFlow<String?> = _editUrl.asStateFlow()

    private val _refs = MutableStateFlow<List<String>>(emptyList())
    val refs: StateFlow<List<String>> = _refs.asStateFlow()

    private val _shareState = MutableStateFlow<ShareState>(ShareState.Idle)
    val shareState: StateFlow<ShareState> = _shareState.asStateFlow()

    private var packJob: Job? = null

    /** See [ShareProgressGate]: cancelling the job does not silence it. */
    private val progressGate = ShareProgressGate()

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
        if (repo.value == null) return
        viewModelScope.launch {
            // Only wait for a sync that exists. The launch is refused while an
            // archive holds the mirror, and moving to Waiting on the strength of
            // the tap alone would leave the card waiting for a completion that
            // never arrives — `running` never rises, so the collector never fires.
            val started = syncRunner.launchSyncOne(id)
            if (!started && !syncRunner.running.value) return@launch
            _shareState.value = ShareRules.onRetry()
        }
    }

    /** Back, or Stop while packing. Stop means stop, not pause. */
    fun cancelShare() {
        // Before the cancel, not after: the packing thread is inside a
        // blocking read that ignores its interrupt, so it gets to report one
        // more entry. Without this the card would clear and immediately come
        // back as "Packing…", stuck there for good behind a dead job.
        progressGate.cancel()
        packJob?.cancel()
        // Deliberately *not* cleared. A cancelled pack holds the mirror lock
        // until its own `finally` has run, and `pack` joins whatever is in here
        // before acquiring. Null it and the next Share loses the acquire to the
        // pack this call just stopped, then waits for a sync that is not coming.
        _shareState.value = ShareState.Idle
    }

    /** Called once the share sheet has been launched. */
    fun shareConsumed() {
        _shareState.value = ShareState.Idle
    }

    private fun pack(current: Repo) {
        val previous = packJob
        // Supersedes the outgoing pack's generation as well as opening this
        // one's, so a straggling report from the job cancelled on the next
        // line cannot repaint the state this one is about to own.
        val token = progressGate.begin()
        previous?.cancel()
        packJob = viewModelScope.launch {
            // A cancelled pack keeps the mirror lock until its own `finally`
            // has run, so a second Share tap would otherwise lose the acquire
            // to the pack it just stopped and sit in Waiting for a sync that
            // is not coming.
            previous?.join()

            // Nothing else stops a fetch from starting mid-archive: the Share
            // path checked `syncing` once, on the tap. A fetch writes a
            // temporary pack inside the mirror and rewrites its refs, and the
            // resulting archive checksums clean while pointing at objects it
            // does not contain. Losing the race is not an error — the sync
            // that won it will finish, and the collector in `init` picks the
            // share back up when it does.
            if (!access.tryAcquire(MirrorAccess.Owner.ARCHIVE)) {
                _shareState.value = ShareState.Waiting(neverSynced = false)
                return@launch
            }
            val gitDir = File(current.localPath)
            try {
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
                                    lastSyncAt = current.lastSyncAt
                                        ?: System.currentTimeMillis(),
                                ) { _, completed, total ->
                                    if (progressGate.accepts(token)) {
                                        _shareState.value = ShareState.Packing(completed, total)
                                    }
                                }
                            }
                        }
                    }
                }
                outcome
                    .onSuccess { _shareState.value = ShareState.Ready(it) }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        // Packing failed, not fetching: the remote was never
                        // touched here. Offering "Retry sync" would answer a
                        // full cache with an SSH connection.
                        _shareState.value = ShareRules.onArchiveFailed(
                            (t as? ArchiveRefused)?.error ?: SyncErrors.fromException(t)
                        )
                    }
            } finally {
                // Covers the Stop button too: `cancelShare` cancels this job,
                // and a lock left held would block every later sync for the
                // lifetime of the process.
                access.release(MirrorAccess.Owner.ARCHIVE)
            }
        }
    }

    private class ArchiveRefused(val error: SyncError) : Exception(error.detail)

    init {
        // A just-added repository has no pinned key; verification is this
        // screen's opening move, not something the user must discover.
        viewModelScope.launch {
            val current = repository.observe(id).first()
            if (current != null && current.hostKeyFingerprint == null) verify()
        }
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
        viewModelScope.launch {
            _publicKey.value = runCatching { keys.publicKeyLine() }.getOrNull()
        }
    }

    fun cancelSync() = syncRunner.cancel()

    fun syncNow() {
        viewModelScope.launch { syncRunner.launchSyncOne(id) }
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
     * Asks the server for its host key in the background. Serves both the
     * first verification of a just-added repository and the deliberate
     * re-check after a mismatch; either way the user sees the fingerprint
     * before anything is pinned.
     */
    fun verify() {
        if (_verifying.value) return
        _verifying.value = true
        _probeError.value = null
        viewModelScope.launch {
            try {
                val current = repository.observe(id).first() ?: return@launch
                val firstTrust = current.hostKeyFingerprint == null
                when (val outcome = VerifyRules.fromProbe(mirror.probeHostKey(current.remoteUrl))) {
                    is VerifyOutcome.Pending -> _pendingHostKey.value = PendingHostKey(
                        fingerprint = outcome.fingerprint,
                        authFailed = outcome.authFailed,
                        firstTrust = firstTrust,
                    )
                    is VerifyOutcome.Failed -> _probeError.value = outcome.error
                }
            } finally {
                _verifying.value = false
            }
        }
    }

    fun confirmHostKey() {
        val pending = _pendingHostKey.value ?: return
        viewModelScope.launch {
            repository.updateHostKey(id, pending.fingerprint)
            _pendingHostKey.value = null
            // The whole point of confirming was to mirror; do not make the
            // user find the sync button next. Refused harmlessly if the key
            // is not on the server yet — the row then shows the auth failure
            // and the key-setup card.
            if (pending.firstTrust) syncRunner.launchSyncOne(id)
        }
    }

    fun dismissHostKey() {
        val pending = _pendingHostKey.value ?: return
        _pendingHostKey.value = null
        // Declining must not erase what the probe learned: an auth refusal
        // keeps driving the key-setup card until a probe or sync succeeds.
        VerifyRules.onDismiss(pending.authFailed)?.let { _probeError.value = it }
    }

    /** Opens the edit-remote dialog, prefilled with the URL in force. */
    fun editUrl() {
        _editUrl.value = repo.value?.remoteUrl ?: return
    }

    fun editUrlChanged(text: String) {
        // Ignored while the dialog is closed: a stray field callback must not
        // reopen it.
        if (_editUrl.value != null) _editUrl.value = text
    }

    fun dismissEditUrl() {
        _editUrl.value = null
    }

    /**
     * Persists the typed remote, if it says anything new. The dialog closes
     * either way — the user confirmed, and a dialog that stays put after a tap
     * reads as a failure.
     */
    fun confirmEditUrl() {
        val typed = _editUrl.value ?: return
        _editUrl.value = null
        val next = EditUrlRules.urlToPersist(repo.value?.remoteUrl, typed) ?: return
        viewModelScope.launch {
            repository.updateRemoteUrl(id, next)
            // The new server is unpinned now, so offer its fingerprint at once
            // rather than making the user find "Verify" for themselves.
            verify()
        }
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
                    access = this.appContainer().mirrorAccess,
                    keys = this.appContainer().sshKeyStore,
                )
            }
        }
    }
}
