package io.github.beal1031.fin2go.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.beal1031.fin2go.ui.components.AppTopBar
import io.github.beal1031.fin2go.ui.home.HomeScreen
import io.github.beal1031.fin2go.ui.library.LibraryScreen
import io.github.beal1031.fin2go.ui.library.LibraryType
import kotlinx.serialization.Serializable

/** The home screen, where the app starts after sign-in. */
@Serializable
object HomeRoute

/** The list of all series or all movies. */
@Serializable
data class LibraryRoute(val type: LibraryType)

/**
 * All screens after sign-in and how to get from one to the other.
 * The top bar is drawn once above the screens, so it stays in place when the screen changes.
 * Every screen gets its own ViewModel, which lives as long as the screen is on the back stack.
 */
@Composable
fun AppNavigation(
    userName: String,
    profileImageUrl: String?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    // Changes whenever the shown screen changes, so the bar can show the matching title.
    val currentEntry by navController.currentBackStackEntryAsState()
    val library = currentEntry
        ?.takeIf { it.destination.hasRoute<LibraryRoute>() }
        ?.toRoute<LibraryRoute>()

    Column(modifier = modifier.fillMaxSize()) {
        // On home: "Fin2Go". In a list: back arrow and the title of the list.
        AppTopBar(
            userName = userName,
            profileImageUrl = profileImageUrl,
            onSignOut = onSignOut,
            title = library?.let { stringResource(it.type.title) },
            onBack = library?.let { { navController.popBackStack() } },
        )

        NavHost(navController = navController, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(
                    onShowAllSeries = { navController.navigate(LibraryRoute(LibraryType.SERIES)) },
                    onShowAllMovies = { navController.navigate(LibraryRoute(LibraryType.MOVIES)) },
                )
            }
            composable<LibraryRoute> { entry ->
                LibraryScreen(type = entry.toRoute<LibraryRoute>().type)
            }
        }
    }
}
