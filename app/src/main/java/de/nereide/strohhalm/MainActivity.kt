package de.nereide.strohhalm

import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.rememberNavController
import de.nereide.strohhalm.ui.nav.AppNavHost
import de.nereide.strohhalm.ui.nav.Routes
import de.nereide.strohhalm.ui.theme.StrohhalmTheme
import kotlinx.coroutines.flow.first

/**
 * Single-activity host. Onboarding is the start destination until all-files
 * access has been granted and a mirror folder chosen — without both, every other
 * screen would fail on its first action.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            StrohhalmTheme {
                // Null until the stored settings have been read; rendering a
                // start destination before that would flash onboarding at users
                // who have already completed it.
                var startDestination by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val container = (application as StrohhalmApp).container
                    val root = container.settingsRepository.storageRoot.first()
                    startDestination = if (root != null && hasStorageAccess()) {
                        Routes.SETTINGS
                    } else {
                        Routes.ONBOARDING
                    }
                }

                startDestination?.let { destination ->
                    AppNavHost(
                        navController = rememberNavController(),
                        startDestination = destination
                    )
                }
            }
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
}
