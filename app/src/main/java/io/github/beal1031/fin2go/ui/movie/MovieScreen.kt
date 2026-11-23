package io.github.beal1031.fin2go.ui.movie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.media.MovieDetails
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.PreviewImage
import io.github.beal1031.fin2go.ui.WithPreviewImages
import io.github.beal1031.fin2go.ui.withoutTop
import io.github.beal1031.fin2go.ui.components.AppTopBar
import io.github.beal1031.fin2go.ui.components.CenteredBox
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/** Connects the ViewModel to the UI. Used by the app. */
@Composable
fun MovieScreen(
    movieId: String,
    statusBarHeight: Dp,
    modifier: Modifier = Modifier,
    // The ViewModel needs to know which movie to load, so it is created here with the id.
    viewModel: MovieViewModel = viewModel { MovieViewModel(checkNotNull(this[APPLICATION_KEY]), movieId) },
) {
    val state by viewModel.state.collectAsState()
    // Kept when the phone is rotated.
    var overviewExpanded by rememberSaveable { mutableStateOf(false) }

    MovieContent(
        state = state,
        overviewExpanded = overviewExpanded,
        onToggleOverview = { overviewExpanded = !overviewExpanded },
        onRetry = viewModel::reload,
        // Playing comes in a later step.
        onPlay = {},
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

            else -> MovieDetailsView(state.movie, overviewExpanded, onToggleOverview, onPlay, statusBarHeight)
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
    statusBarHeight: Dp,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header(movie, statusBarHeight)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = movie.title, style = MaterialTheme.typography.headlineSmall)

            // e.g. "2012 · 12 min · FSK-12"; missing parts are left out.
            val facts = listOfNotNull(
                movie.year?.toString(),
                movie.runtimeMinutes?.let { runtimeText(it) },
                movie.ageRating,
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
            Button(onClick = onPlay, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Text(text = stringResource(R.string.movie_play), modifier = Modifier.padding(start = 8.dp))
            }
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

/**
 * The wide picture (16:9) over the full width, fading into the background at the bottom.
 * It starts at the very top, behind the status bar and the top bar. It is [statusBarHeight] taller than 16:9,
 * so the part below the status bar still has the full height; the picture is cut a little at the sides for that.
 * Without a backdrop, the poster is shown blurred and darkened instead.
 */
@Composable
private fun Header(movie: MovieDetails, statusBarHeight: Dp) = BoxWithConstraints {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(maxWidth * 9 / 16 + statusBarHeight)
            // Grey area while the picture loads or when there is none.
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (movie.backdropUrl != null) {
            AsyncImage(
                model = movie.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (movie.posterUrl != null) {
            AsyncImage(
                model = movie.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Blur only works from Android 12 on; older phones show the poster sharp, but darkened.
                modifier = Modifier.fillMaxSize().blur(24.dp),
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
        }
        // Top: slightly darkened, so the back arrow of the bar stays readable on bright pictures.
        // Bottom: fades into the page, so the title below does not start at a hard edge.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.5f),
                        0.35f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to background,
                    ),
                ),
        )
    }
}

/** e.g. "2 h 12 min", "2 h" or "12 min". */
@Composable
private fun runtimeText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.runtime_minutes, rest)
        rest == 0 -> stringResource(R.string.runtime_hours, hours)
        else -> stringResource(R.string.runtime_hours_minutes, hours, rest)
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
private fun MoviePreview(state: MovieState, overviewExpanded: Boolean = false) {
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

@Preview(name = "3 Error", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MovieErrorPreview() = MoviePreview(MovieState(loading = false, error = true))
