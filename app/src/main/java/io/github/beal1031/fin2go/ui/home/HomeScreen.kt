package io.github.beal1031.fin2go.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.media.MediaItem
import io.github.beal1031.fin2go.data.media.withDownloads
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.PreviewImage
import io.github.beal1031.fin2go.ui.WithPreviewImages
import io.github.beal1031.fin2go.ui.components.OnConnectionChange
import io.github.beal1031.fin2go.ui.components.AppTopBar
import io.github.beal1031.fin2go.ui.components.CenteredBox
import io.github.beal1031.fin2go.ui.components.MediaTile
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/** Connects the ViewModel to the UI. Used by the app. */
@Composable
fun HomeScreen(
    onShowAllSeries: () -> Unit,
    onShowAllMovies: () -> Unit,
    onOpenMovie: (MediaItem) -> Unit,
    onOpenSeries: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Each time the screen is shown again, e.g. back from a movie or back in the app: load the current state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }
    // Offline the page shows the downloads, online again what the server has.
    OnConnectionChange { viewModel.onShown() }

    HomeContent(
        state = state,
        onRetry = viewModel::reload,
        onShowAllSeries = onShowAllSeries,
        onShowAllMovies = onShowAllMovies,
        onOpenMovie = onOpenMovie,
        onOpenSeries = onOpenSeries,
        onPlay = onPlay,
        modifier = modifier,
    )
}

/**
 * The pure UI: the rows, or loading, error or empty. The top bar is above it, in AppNavigation.
 * A tap on a movie or series opens its detail page. In "Continue watching" a tap starts playing at once,
 * so there a long press opens the detail page.
 */
@Composable
fun HomeContent(
    state: HomeState,
    onRetry: () -> Unit,
    onShowAllSeries: () -> Unit,
    onShowAllMovies: () -> Unit,
    onOpenMovie: (MediaItem) -> Unit,
    onOpenSeries: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> CenteredBox { CircularProgressIndicator() }

            state.error -> CenteredBox {
                Text(stringResource(R.string.load_failed), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }

            state.isEmpty -> CenteredBox {
                Text(stringResource(R.string.home_empty), textAlign = TextAlign.Center)
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // A row without items is not shown at all.
                if (state.continueWatching.isNotEmpty()) {
                    item {
                        MediaRow(
                            stringResource(R.string.home_continue_watching),
                            state.continueWatching,
                            wide = true,
                            onItemClick = onPlay,
                            // An episode opens the page of its series, with this episode chosen.
                            onItemLongClick = { item -> if (item.seriesId != null) onOpenSeries(item) else onOpenMovie(item) },
                        )
                    }
                }
                if (state.latestSeries.isNotEmpty()) {
                    item {
                        MediaRow(
                            stringResource(R.string.series),
                            state.latestSeries,
                            wide = false,
                            onShowAll = onShowAllSeries,
                            onItemClick = onOpenSeries,
                        )
                    }
                }
                if (state.latestMovies.isNotEmpty()) {
                    item {
                        MediaRow(
                            stringResource(R.string.movies),
                            state.latestMovies,
                            wide = false,
                            onShowAll = onShowAllMovies,
                            onItemClick = onOpenMovie,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A heading and a row of tiles that scrolls sideways.
 * With [onShowAll], the heading gets an arrow and the whole heading can be tapped.
 * [onItemClick] and [onItemLongClick] are called with the touched tile's item.
 */
@Composable
private fun MediaRow(
    title: String,
    items: List<MediaItem>,
    wide: Boolean,
    onShowAll: (() -> Unit)? = null,
    onItemClick: ((MediaItem) -> Unit)? = null,
    onItemLongClick: ((MediaItem) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .then(
                    // indication = null: no ripple animation on tap.
                    if (onShowAll != null) Modifier.clickable(interactionSource = null, indication = null, onClick = onShowAll)
                    else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (onShowAll != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.home_show_all),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.id }) { item ->
                MediaTile(
                    item = item,
                    wide = wide,
                    modifier = Modifier.width(if (wide) 240.dp else 120.dp),
                    onClick = onItemClick?.let { { it(item) } },
                    onLongClick = onItemLongClick?.let { { it(item) } },
                )
            }
        }
    }
}

// --- Previews: one per state of the screen ---

private val sampleState = HomeState(
    loading = false,
    continueWatching = listOf(
        MediaItem(
            id = "1",
            title = "Caminandes",
            seasonNumber = 1,
            episodeNumber = 2,
            imageUrl = PreviewImage.EPISODE_CAMINANDES_2.url,
            progress = 0.4f,
        ),
        MediaItem(
            id = "2",
            title = "Tears of Steel",
            imageUrl = PreviewImage.BACKDROP_TEARS_OF_STEEL.url,
            progress = 0.75f,
        ),
        // Next episode of a series, not started yet: no bar.
        MediaItem(
            id = "11",
            title = "Caminandes",
            seasonNumber = 1,
            episodeNumber = 3,
            imageUrl = PreviewImage.EPISODE_CAMINANDES_3.url,
        ),
    ),
    latestSeries = listOf(
        MediaItem(id = "3", title = "Caminandes", imageUrl = PreviewImage.POSTER_CAMINANDES.url),
        MediaItem(id = "4", title = "Sprite Fright", imageUrl = PreviewImage.POSTER_SPRITE_FRIGHT.url),
        MediaItem(
            id = "5",
            title = "Cosmos Laundromat: First Cycle",
            imageUrl = PreviewImage.POSTER_COSMOS_LAUNDROMAT.url,
        ),
        MediaItem(id = "6", title = "Spring", imageUrl = PreviewImage.POSTER_SPRING.url, watched = true),
    ),
    latestMovies = listOf(
        MediaItem(id = "7", title = "Tears of Steel", imageUrl = PreviewImage.POSTER_TEARS_OF_STEEL.url),
        MediaItem(id = "8", title = "Sintel", imageUrl = PreviewImage.POSTER_SINTEL.url),
        MediaItem(id = "9", title = "Big Buck Bunny", imageUrl = PreviewImage.POSTER_BIG_BUCK_BUNNY.url),
        MediaItem(
            id = "10",
            title = "Elephants Dream",
            imageUrl = PreviewImage.POSTER_ELEPHANTS_DREAM.url,
            watched = true,
        ),
    ),
)

@Composable
private fun HomePreview(state: HomeState) {
    Fin2goTheme {
        WithPreviewImages {
            // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                // In the app the top bar comes from AppNavigation; here it is added so it looks the same.
                Column(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
                    AppTopBar(userName = "sen", profileImageUrl = PreviewImage.PROFILE.url, onSignOut = {})
                    HomeContent(
                        state = state,
                        onRetry = {},
                        onShowAllSeries = {},
                        onShowAllMovies = {},
                        onOpenMovie = {},
                        onOpenSeries = {},
                        onPlay = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "1 Loading", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun HomeLoadingPreview() = HomePreview(HomeState())

@Preview(name = "2 Rows", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun HomeRowsPreview() = HomePreview(sampleState)

@Preview(name = "2b Without continue watching", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun HomeNoContinueWatchingPreview() = HomePreview(sampleState.copy(continueWatching = emptyList()))

@Preview(name = "2c German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "2d French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "2e Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun HomeLanguagesPreview() = HomePreview(sampleState)

@Preview(name = "3 Error", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun HomeErrorPreview() = HomePreview(HomeState(loading = false, error = true))

@Preview(name = "4 Nothing on the server yet", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun HomeEmptyPreview() = HomePreview(HomeState(loading = false))

private val downloadedIds = setOf("1", "2", "8")

@Preview(name = "5 Downloads marked", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun HomeDownloadedPreview() = HomePreview(
    sampleState.copy(
        continueWatching = sampleState.continueWatching.withDownloads(downloadedIds),
        latestMovies = sampleState.latestMovies.withDownloads(downloadedIds),
    ),
)
