package de.nereide.strohhalm.ui.onboarding

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.SettingsRepository
import de.nereide.strohhalm.domain.ProbeReport
import de.nereide.strohhalm.domain.StorageProbe
import de.nereide.strohhalm.ui.common.PickedFolder
import de.nereide.strohhalm.ui.common.appContainer
import de.nereide.strohhalm.ui.common.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.time.Instant

data class OnboardingUiState(
    val hasStorageAccess: Boolean = false,
    val storageRoot: String? = null,
    val hasNotificationPermission: Boolean = true,
    val probe: ProbeReport? = null,
    val probeError: String? = null,
) {
    val complete: Boolean get() = hasStorageAccess && storageRoot != null
}

class OnboardingViewModel(
    private val settings: SettingsRepository,
    private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Permissions are granted in system screens the app does not control, so
     * state is re-read whenever the screen resumes rather than observed.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                hasStorageAccess = hasStorageAccess(),
                storageRoot = settings.storageRoot.first(),
                hasNotificationPermission = hasNotificationPermission(),
            )
        }
    }

    /**
     * Writes the path probe into the chosen folder and only persists the folder
     * if that succeeds. The probe is what makes the derived path checkable from
     * outside the app — see [StorageProbe].
     */
    fun setStorageRoot(picked: PickedFolder?) {
        viewModelScope.launch {
            if (picked == null) {
                _uiState.value = _uiState.value.copy(
                    probe = null,
                    probeError = "No folder was returned, or its path could not be derived."
                )
                return@launch
            }

            val report = withContext(Dispatchers.IO) {
                StorageProbe.write(
                    dir = picked.dir,
                    documentId = picked.documentId,
                    nonce = newNonce(),
                    writtenAt = Instant.now().toString(),
                )
            }

            report.onSuccess { probe ->
                settings.setStorageRoot(picked.dir)
                _uiState.value = _uiState.value.copy(
                    storageRoot = picked.dir.path,
                    probe = probe,
                    probeError = null,
                )
            }.onFailure { t ->
                _uiState.value = _uiState.value.copy(
                    probe = null,
                    probeError = t.message ?: t.toString(),
                )
            }
        }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    settings = this.appContainer().settingsRepository,
                    context = this.application()
                )
            }
        }
    }
}
