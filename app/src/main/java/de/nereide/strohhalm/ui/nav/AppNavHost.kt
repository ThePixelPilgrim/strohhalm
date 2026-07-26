package de.nereide.strohhalm.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.nereide.strohhalm.ui.add.AddRepoScreen
import de.nereide.strohhalm.ui.detail.RepoDetailScreen
import de.nereide.strohhalm.ui.list.RepoListScreen
import de.nereide.strohhalm.ui.onboarding.OnboardingScreen
import de.nereide.strohhalm.ui.settings.SettingsScreen

/** Route definitions for the app's single-activity navigation graph. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val LIST = "list"
    const val ADD = "add"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings"

    fun detail(id: Long) = "detail/$id"
}

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.LIST,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onDone = {
                    navController.navigate(Routes.LIST) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.LIST) {
            RepoListScreen(
                onOpenRepo = { id -> navController.navigate(Routes.detail(id)) },
                onAddRepo = { navController.navigate(Routes.ADD) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.ADD) {
            AddRepoScreen(onDone = { navController.popBackStack() })
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: return@composable
            RepoDetailScreen(id = id, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
