package io.github.beal1031.fin2go.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import io.github.beal1031.fin2go.ui.components.LocalServerReachable
import io.github.beal1031.fin2go.ui.episode.EpisodeScreen
import io.github.beal1031.fin2go.ui.home.HomeScreen
import io.github.beal1031.fin2go.ui.library.LibraryScreen
import io.github.beal1031.fin2go.ui.library.LibraryType
import io.github.beal1031.fin2go.ui.movie.MovieScreen
import io.github.beal1031.fin2go.ui.player.PlayerScreen
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

/** The detail page of one episode. */
@Serializable
data class EpisodeRoute(val id: String)

/** The player for one movie or episode. It starts at the saved position, or with [fromStart] at the beginning. */
@Serializable
data class PlayerRoute(val id: String, val fromStart: Boolean = false)

/** The detail page of one series. With [episodeId], that episode is chosen; otherwise the one the user is at. */
@Serializable
data class SeriesRoute(val id: String, val episodeId: String? = null)

/**
 * All screens after sign-in and how to get from one to the other.
 * The top bar is drawn once over the screens, so it stays in place when the screen changes.
 * The bar has no background of its own: most screens leave the status bar and the top bar free at the top,
 * the movie page lets its picture run behind both. [statusBarHeight] is the height of the status bar.
 * [systemBarsPadding] is the space of the system bars and camera cutout; the screens keep clear of it at the sides
 * and the bottom. Only the player uses the whole screen and keeps its controls clear of the cutout itself.
 * Every screen gets its own ViewModel, which lives as long as the screen is on the back stack.
 */
@Composable
fun AppNavigation(
    userName: String,
    profileImageUrl: String?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    systemBarsPadding: PaddingValues = PaddingValues(),
    statusBarHeight: Dp = 0.dp,
    offline: Boolean = false,
) {
    val navController = rememberNavController()
    // Changes whenever the shown screen changes, so the bar can show the matching title.
    val currentEntry by navController.currentBackStackEntryAsState()
    val entry = currentEntry
    val onHome = entry == null || entry.destination.hasRoute<HomeRoute>()
    // The detail pages let their picture run behind the bar.
    val onDetailPage = entry != null &&
        (
            entry.destination.hasRoute<MovieRoute>() ||
                entry.destination.hasRoute<SeriesRoute>() ||
                entry.destination.hasRoute<EpisodeRoute>()
            )
    // The player fills the whole screen, without the bar.
    val onPlayer = entry != null && entry.destination.hasRoute<PlayerRoute>()
    // The pages still on screen. After going back, the player stays on screen for a moment while the page
    // behind it is already the current one; the bar waits until the player is really gone.
    val visibleEntries by navController.visibleEntries.collectAsState()
    val playerOnScreen = onPlayer || visibleEntries.any { it.destination.hasRoute<PlayerRoute>() }
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
    val play = { id: String, fromStart: Boolean -> open(PlayerRoute(id, fromStart)) }
    // Goes back only from the page the arrow belongs to, so a double tap goes back just one step.
    val back = { from: NavBackStackEntry ->
        if (navController.currentBackStackEntry?.id == from.id) navController.popBackStack()
    }
    // Space for the status bar and the top bar above screens that do not draw behind them.
    val belowBar = Modifier.padding(top = statusBarHeight + AppTopBarHeight)

    val sidesAndBottom = systemBarsPadding.withoutTop()
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (playerOnScreen) Modifier
                else Modifier.padding(sidesAndBottom).consumeWindowInsets(systemBarsPadding),
            ),
    ) {
        // Into and out of the player the screen changes at once, without the usual fade:
        // the video surface cannot fade, so it would stay on top of the page behind it while that fades in,
        // and the page behind it would show up still in landscape.
        val enter = { scope: AnimatedContentTransitionScope<NavBackStackEntry> ->
            if (scope.involvesPlayer()) EnterTransition.None else fadeIn(tween(700))
        }
        val exit = { scope: AnimatedContentTransitionScope<NavBackStackEntry> ->
            if (scope.involvesPlayer()) ExitTransition.None else fadeOut(tween(700))
        }
        // All pages can see whether the server is reachable, see OnConnectionChange.
        CompositionLocalProvider(LocalServerReachable provides !offline) {
            NavHost(
                navController = navController,
                startDestination = HomeRoute,
                enterTransition = { enter(this) },
                exitTransition = { exit(this) },
                popEnterTransition = { enter(this) },
                popExitTransition = { exit(this) },
            ) {
                composable<HomeRoute> {
                    HomeScreen(
                        onShowAllSeries = { open(LibraryRoute(LibraryType.SERIES)) },
                        onShowAllMovies = { open(LibraryRoute(LibraryType.MOVIES)) },
                        onOpenMovie = openMovie,
                        onOpenSeries = openSeries,
                        onPlay = { play(it.id, false) },
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
                    MovieScreen(movieId = entry.toRoute<MovieRoute>().id, onPlay = play, statusBarHeight = statusBarHeight)
                }
                composable<SeriesRoute> { entry ->
                    val route = entry.toRoute<SeriesRoute>()
                    SeriesScreen(
                        seriesId = route.id,
                        episodeId = route.episodeId,
                        onOpenEpisode = { open(EpisodeRoute(it.id)) },
                        onPlay = { play(it.id, false) },
                        statusBarHeight = statusBarHeight,
                    )
                }
                composable<EpisodeRoute> { entry ->
                    EpisodeScreen(episodeId = entry.toRoute<EpisodeRoute>().id, onPlay = play, statusBarHeight = statusBarHeight)
                }
                composable<PlayerRoute> { entry ->
                    val route = entry.toRoute<PlayerRoute>()
                    PlayerScreen(itemId = route.id, fromStart = route.fromStart, onClose = { back(entry) })
                }
            }
        }

        // On home: "Fin2Go". In a list: back arrow and its name. On a detail page: only the back arrow.
        if (!playerOnScreen) {
            AppTopBar(
                userName = userName,
                profileImageUrl = profileImageUrl,
                onSignOut = onSignOut,
                title = title,
                onBack = if (!onHome && entry != null) ({ back(entry) }) else null,
                onPicture = onDetailPage,
                offline = offline,
                modifier = Modifier.padding(top = statusBarHeight),
            )
        }

        // The back gesture of Android does exactly what the back arrow does, with the same animation.
        // Without this, NavHost shows its own gesture animation, which follows the finger and looks different.
        // Registered after NavHost, so it is asked first. On home it is off and the gesture leaves the app.
        // The player handles the gesture itself, because it first tells the server where the user stopped.
        BackHandler(enabled = !onHome && !onPlayer && entry != null) { entry?.let(back) }
    }
}

/** True when the player is opened or closed in this change of screen. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.involvesPlayer(): Boolean =
    initialState.destination.hasRoute<PlayerRoute>() || targetState.destination.hasRoute<PlayerRoute>()

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
