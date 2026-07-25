package de.nereide.strohhalm.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.nereide.strohhalm.ui.onboarding.OnboardingScreen
import de.nereide.strohhalm.ui.settings.SettingsScreen

/**
 * Route definitions for the app's single-activity navigation graph.
 *
 * LIST, ADD and DETAIL are not registered yet — there is no repository list
 * until the mirror engine exists, so onboarding currently hands over to
 * settings, which is where the public key lives.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.SETTINGS,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onDone = {
                    navController.navigate(Routes.SETTINGS) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}
