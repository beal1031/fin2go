package io.github.beal1031.fin2go.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.beal1031.fin2go.data.media.MediaItem
import io.github.beal1031.fin2go.ui.components.AppTopBar
import io.github.beal1031.fin2go.ui.components.AppTopBarHeight
import io.github.beal1031.fin2go.ui.home.HomeScreen
import io.github.beal1031.fin2go.ui.library.LibraryScreen
import io.github.beal1031.fin2go.ui.library.LibraryType
import io.github.beal1031.fin2go.ui.movie.MovieScreen
import io.github.beal1031.fin2go.ui.series.SeriesScreen
import kotlinx.serialization.Serializable

/** The home screen, where the app starts after sign-in. */
@Serializable
object HomeRoute

/** The list of all series or all movies. */
@Serializable
data class LibraryRoute(val type: LibraryType)

/** The detail page of one movie. */
@Serializable
data class MovieRoute(val id: String)

/** The detail page of one series. With [episodeId], that episode is chosen; otherwise the one the user is at. */
@Serializable
data class SeriesRoute(val id: String, val episodeId: String? = null)

/**
 * All screens after sign-in and how to get from one to the other.
 * The top bar is drawn once over the screens, so it stays in place when the screen changes.
 * The bar has no background of its own: most screens leave the status bar and the top bar free at the top,
 * the movie page lets its picture run behind both. [statusBarHeight] is the height of the status bar.
 * Every screen gets its own ViewModel, which lives as long as the screen is on the back stack.
 */
@Composable
fun AppNavigation(
    userName: String,
    profileImageUrl: String?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    statusBarHeight: Dp = 0.dp,
) {
    val navController = rememberNavController()
    // Changes whenever the shown screen changes, so the bar can show the matching title.
    val currentEntry by navController.currentBackStackEntryAsState()
    val entry = currentEntry
    val onHome = entry == null || entry.destination.hasRoute<HomeRoute>()
    // The detail pages let their picture run behind the bar.
    val onDetailPage = entry != null &&
        (entry.destination.hasRoute<MovieRoute>() || entry.destination.hasRoute<SeriesRoute>())
    // In a list: its name. On a detail page: none, the title is big below the picture.
    val title = entry
        ?.takeIf { it.destination.hasRoute<LibraryRoute>() }
        ?.let { stringResource(it.toRoute<LibraryRoute>().type.title) }
    // launchSingleTop: when the same page is already on top, it is not opened a second time.
    // So a fast double tap opens a page only once, while a tap on another page always works at once.
    val open = { route: Any -> navController.navigate(route) { launchSingleTop = true } }
    val openMovie = { item: MediaItem -> open(MovieRoute(item.id)) }
    // A series opens its page; an episode opens the page of its series with this episode chosen.
    val openSeries = { item: MediaItem ->
        val seriesId = item.seriesId
        open(if (seriesId != null) SeriesRoute(seriesId, episodeId = item.id) else SeriesRoute(item.id))
    }
    // Goes back only from the page the arrow belongs to, so a double tap goes back just one step.
    val back = { from: NavBackStackEntry ->
        if (navController.currentBackStackEntry?.id == from.id) navController.popBackStack()
    }
    // Space for the status bar and the top bar above screens that do not draw behind them.
    val belowBar = Modifier.padding(top = statusBarHeight + AppTopBarHeight)

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(
                    onShowAllSeries = { open(LibraryRoute(LibraryType.SERIES)) },
                    onShowAllMovies = { open(LibraryRoute(LibraryType.MOVIES)) },
                    onOpenMovie = openMovie,
                    onOpenSeries = openSeries,
                    modifier = belowBar,
                )
            }
            composable<LibraryRoute> { entry ->
                LibraryScreen(
                    type = entry.toRoute<LibraryRoute>().type,
                    onOpenMovie = openMovie,
                    onOpenSeries = openSeries,
                    modifier = belowBar,
                )
            }
            composable<MovieRoute> { entry ->
                MovieScreen(movieId = entry.toRoute<MovieRoute>().id, statusBarHeight = statusBarHeight)
            }
            composable<SeriesRoute> { entry ->
                val route = entry.toRoute<SeriesRoute>()
                SeriesScreen(seriesId = route.id, episodeId = route.episodeId, statusBarHeight = statusBarHeight)
            }
        }

        // On home: "Fin2Go". In a list: back arrow and its name. On the movie page: only the back arrow.
        AppTopBar(
            userName = userName,
            profileImageUrl = profileImageUrl,
            onSignOut = onSignOut,
            title = title,
            onBack = if (!onHome && entry != null) ({ back(entry) }) else null,
            onPicture = onDetailPage,
            modifier = Modifier.padding(top = statusBarHeight),
        )

        // The back gesture of Android does exactly what the back arrow does, with the same animation.
        // Without this, NavHost shows its own gesture animation, which follows the finger and looks different.
        // Registered after NavHost, so it is asked first. On home it is off and the gesture leaves the app.
        BackHandler(enabled = !onHome && entry != null) { entry?.let(back) }
    }
}

/** The same padding without its top part, e.g. to leave the status bar space to the screen itself. */
@Composable
fun PaddingValues.withoutTop(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        end = calculateEndPadding(direction),
        bottom = calculateBottomPadding(),
    )
}
