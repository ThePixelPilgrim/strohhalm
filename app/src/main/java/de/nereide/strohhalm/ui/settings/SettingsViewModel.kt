package de.nereide.strohhalm.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.SettingsRepository
import de.nereide.strohhalm.domain.ProbeReport
import de.nereide.strohhalm.domain.SshKeyStore
import de.nereide.strohhalm.domain.StorageProbe
import de.nereide.strohhalm.ui.common.PickedFolder
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.time.Instant

data class SettingsUiState(
    val storageRoot: String? = null,
    val notifyOnFailure: Boolean = true,
)

/**
 * Deliberately does not expose the sync interval yet. The interval only has
 * meaning once a worker reads it, and a control that persists a value nothing
 * acts on is worse than an absent one. It lands with the sync worker.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val keyStore: SshKeyStore,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.storageRoot,
        settings.notifyOnFailure
    ) { root, notify ->
        SettingsUiState(storageRoot = root, notifyOnFailure = notify)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()

    private val _probe = MutableStateFlow<ProbeReport?>(null)
    val probe: StateFlow<ProbeReport?> = _probe.asStateFlow()

    init {
        // Generating here means the key exists by the time the user looks for
        // it, rather than on the first sync attempt.
        viewModelScope.launch { _publicKey.value = keyStore.publicKeyLine() }
    }

    fun setStorageRoot(picked: PickedFolder?) {
        if (picked == null) return
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                StorageProbe.write(
                    dir = picked.dir,
                    documentId = picked.documentId,
                    nonce = ByteArray(8)
                        .also { SecureRandom().nextBytes(it) }
                        .joinToString("") { b -> "%02x".format(b) },
                    writtenAt = Instant.now().toString(),
                )
            }
            report.onSuccess {
                settings.setStorageRoot(picked.dir)
                _probe.value = it
            }
        }
    }

    fun setNotifyOnFailure(enabled: Boolean) {
        viewModelScope.launch { settings.setNotifyOnFailure(enabled) }
    }

    fun regenerateKey() {
        viewModelScope.launch {
            keyStore.regenerate()
            _publicKey.value = keyStore.publicKeyLine()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settings = this.appContainer().settingsRepository,
                    keyStore = this.appContainer().sshKeyStore
                )
            }
        }
    }
}
