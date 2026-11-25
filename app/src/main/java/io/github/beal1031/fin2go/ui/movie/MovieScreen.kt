package io.github.beal1031.fin2go.ui.movie

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.download.DownloadState
import io.github.beal1031.fin2go.data.media.MovieDetails
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.PreviewImage
import io.github.beal1031.fin2go.ui.WithPreviewImages
import io.github.beal1031.fin2go.ui.withoutTop
import io.github.beal1031.fin2go.ui.components.OnConnectionChange
import io.github.beal1031.fin2go.ui.components.AppTopBar
import io.github.beal1031.fin2go.ui.components.CenteredBox
import io.github.beal1031.fin2go.ui.components.DetailHeader
import io.github.beal1031.fin2go.ui.components.DownloadButton
import io.github.beal1031.fin2go.ui.components.PlayButtons
import io.github.beal1031.fin2go.ui.components.runtimeText
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/** Connects the ViewModel to the UI. Used by the app. [onPlay] gets the movie id and whether to start from the beginning. */
@Composable
fun MovieScreen(
    movieId: String,
    onPlay: (id: String, fromStart: Boolean) -> Unit,
    statusBarHeight: Dp,
    modifier: Modifier = Modifier,
    // The ViewModel needs to know which movie to load, so it is created here with the id.
    viewModel: MovieViewModel = viewModel { MovieViewModel(checkNotNull(this[APPLICATION_KEY]), movieId) },
) {
    val state by viewModel.state.collectAsState()
    val download by viewModel.download.collectAsState()
    // Each time the page is shown again, e.g. back from the player: load the current progress.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }
    // Offline the page shows the downloads, online again what the server has.
    OnConnectionChange { viewModel.onShown() }
    // Kept when the phone is rotated.
    var overviewExpanded by rememberSaveable { mutableStateOf(false) }

    MovieContent(
        state = state,
        overviewExpanded = overviewExpanded,
        onToggleOverview = { overviewExpanded = !overviewExpanded },
        onRetry = viewModel::reload,
        onPlay = { onPlay(movieId, false) },
        onStartOver = { onPlay(movieId, true) },
        onToggleWatched = viewModel::toggleWatched,
        download = download,
        onDownload = viewModel::startDownload,
        onCancelDownload = viewModel::cancelDownload,
        onDeleteDownload = viewModel::deleteDownload,
        statusBarHeight = statusBarHeight,
        modifier = modifier,
    )
}

/**
 * The pure UI: the details of the movie, or loading or error. The top bar lies over it, in AppNavigation.
 * The page starts behind the status bar; [statusBarHeight] makes the picture that much taller.
 */
@Composable
fun MovieContent(
    state: MovieState,
    overviewExpanded: Boolean,
    onToggleOverview: () -> Unit,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onStartOver: () -> Unit,
    onToggleWatched: () -> Unit,
    download: DownloadState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    statusBarHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> CenteredBox { CircularProgressIndicator() }

            state.error || state.movie == null -> CenteredBox {
                Text(stringResource(R.string.load_failed), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }

            else -> MovieDetailsView(
                state.movie,
                overviewExpanded,
                onToggleOverview,
                onPlay,
                onStartOver,
                onToggleWatched,
                download,
                onDownload,
                onCancelDownload,
                onDeleteDownload,
                statusBarHeight,
            )
        }
    }
}

/** Picture at the top, then title, facts, genres, the play button and the description. The whole page scrolls. */
@Composable
private fun MovieDetailsView(
    movie: MovieDetails,
    overviewExpanded: Boolean,
    onToggleOverview: () -> Unit,
    onPlay: () -> Unit,
    onStartOver: () -> Unit,
    onToggleWatched: () -> Unit,
    download: DownloadState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    statusBarHeight: Dp,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailHeader(movie.backdropUrl, movie.posterUrl, statusBarHeight)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = movie.title, style = MaterialTheme.typography.headlineSmall)

            // e.g. "2012 · 12 min · FSK-12 · Watched"; missing parts are left out.
            val facts = listOfNotNull(
                movie.year?.toString(),
                movie.runtimeMinutes?.let { runtimeText(it) },
                movie.ageRating,
                if (movie.watched) stringResource(R.string.watched) else null,
            )
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (movie.genres.isNotEmpty()) {
                Text(
                    text = movie.genres.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Above the description, so it stays in reach when the description is long or expanded.
            PlayButtons(
                progress = movie.progress,
                remainingMinutes = movie.remainingMinutes,
                watched = movie.watched,
                onPlay = onPlay,
                onStartOver = onStartOver,
                onToggleWatched = onToggleWatched,
                modifier = Modifier.padding(top = 8.dp),
                downloadButton = {
                    DownloadButton(
                        state = download,
                        name = movie.title,
                        onDownload = onDownload,
                        onCancel = onCancelDownload,
                        onDelete = onDeleteDownload,
                    )
                },
            )
            movie.overview?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (overviewExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        // indication = null: no ripple animation on tap.
                        .clickable(interactionSource = null, indication = null, onClick = onToggleOverview),
                )
            }
        }
    }
}

// --- Previews: one per state of the screen ---

private val sampleMovie = MovieDetails(
    id = "1",
    title = "Tears of Steel",
    year = 2012,
    runtimeMinutes = 12,
    ageRating = "FSK-12",
    genres = listOf("Science Fiction", "Short"),
    overview = "In a future Amsterdam, a group of scientists and soldiers tries to save the world from a robot " +
        "invasion. To do so, they have to reenact a painful moment from the past: the breakup of a young couple " +
        "on a bridge, years before everything went wrong. Whether the old memory can really change the present " +
        "is the question that drives the story to its end.",
    backdropUrl = PreviewImage.BACKDROP_TEARS_OF_STEEL.url,
    posterUrl = PreviewImage.POSTER_TEARS_OF_STEEL.url,
)

@Composable
private fun MoviePreview(
    state: MovieState,
    overviewExpanded: Boolean = false,
    download: DownloadState = DownloadState.NotDownloaded,
) {
    Fin2goTheme {
        WithPreviewImages {
            // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                // As in MainActivity and AppNavigation: the page starts behind the status bar,
                // the top bar lies over it, just below the status bar.
                val statusBarHeight = innerPadding.calculateTopPadding()
                Box(modifier = Modifier.padding(innerPadding.withoutTop()).consumeWindowInsets(innerPadding)) {
                    MovieContent(
                        state = state,
                        overviewExpanded = overviewExpanded,
                        onToggleOverview = {},
                        onRetry = {},
                        onPlay = {},
                        onStartOver = {},
                        onToggleWatched = {},
                        download = download,
                        onDownload = {},
                        onCancelDownload = {},
                        onDeleteDownload = {},
                        statusBarHeight = statusBarHeight,
                    )
                    AppTopBar(
                        userName = "sen",
                        profileImageUrl = PreviewImage.PROFILE.url,
                        onSignOut = {},
                        onBack = {},
                        onPicture = true,
                        modifier = Modifier.padding(top = statusBarHeight),
                    )
                }
            }
        }
    }
}

@Preview(name = "1 Loading", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieLoadingPreview() = MoviePreview(MovieState())

@Preview(name = "2 Movie", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieDetailsPreview() = MoviePreview(MovieState(loading = false, movie = sampleMovie))

@Preview(name = "2b Description expanded", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieExpandedPreview() =
    MoviePreview(MovieState(loading = false, movie = sampleMovie), overviewExpanded = true)

@Preview(name = "2c Without backdrop: blurred poster", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieNoBackdropPreview() = MoviePreview(
    MovieState(
        loading = false,
        movie = MovieDetails(
            id = "2",
            title = "Sintel",
            year = 2010,
            runtimeMinutes = 135,
            genres = listOf("Animation", "Fantasy"),
            overview = "A young woman searches the wild lands for the baby dragon she once rescued and raised.",
            posterUrl = PreviewImage.POSTER_SINTEL.url,
        ),
    ),
)

@Preview(name = "2d Only a title, no picture", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieMinimalPreview() =
    MoviePreview(MovieState(loading = false, movie = MovieDetails(id = "3", title = "Big Buck Bunny")))

@Preview(name = "2e Long title, long running time", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieLongTitlePreview() = MoviePreview(
    MovieState(
        loading = false,
        movie = MovieDetails(
            id = "4",
            title = "Cosmos Laundromat: First Cycle",
            year = 2015,
            runtimeMinutes = 120,
            ageRating = "PG-13",
            genres = listOf("Animation", "Comedy", "Drama", "Fantasy"),
            overview = "A tired sheep on a lonely island meets a strange salesman who offers him a new life.",
            backdropUrl = PreviewImage.BACKDROP_COSMOS_LAUNDROMAT.url,
            posterUrl = PreviewImage.POSTER_COSMOS_LAUNDROMAT.url,
        ),
    ),
)

@Preview(name = "2f German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "2g French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "2h Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun MovieLanguagesPreview() = MoviePreview(MovieState(loading = false, movie = sampleMovie))

private val startedMovie = sampleMovie.copy(runtimeMinutes = 112, progress = 0.6f, remainingMinutes = 45)

@Preview(name = "4 Started: resume or start over", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieStartedPreview() = MoviePreview(MovieState(loading = false, movie = startedMovie))

@Preview(name = "4b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "4c French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "4d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun MovieStartedLanguagesPreview() = MoviePreview(MovieState(loading = false, movie = startedMovie))

private val watchedMovie = sampleMovie.copy(watched = true)

@Preview(name = "5 Watched: play again", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieWatchedPreview() = MoviePreview(MovieState(loading = false, movie = watchedMovie))

@Preview(name = "5b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "5c French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "5d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun MovieWatchedLanguagesPreview() = MoviePreview(MovieState(loading = false, movie = watchedMovie))

@Preview(name = "3 Error", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieErrorPreview() = MoviePreview(MovieState(loading = false, error = true))

@Preview(name = "6 Downloading", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieDownloadingPreview() =
    MoviePreview(MovieState(loading = false, movie = sampleMovie), download = DownloadState.Downloading(0.42f))

@Preview(name = "6b Downloaded", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieDownloadedPreview() =
    MoviePreview(MovieState(loading = false, movie = sampleMovie), download = DownloadState.Downloaded)
