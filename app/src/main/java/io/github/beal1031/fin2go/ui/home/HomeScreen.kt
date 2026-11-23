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
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.media.MediaItem
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.components.AppTopBar
import io.github.beal1031.fin2go.ui.components.CenteredBox
import io.github.beal1031.fin2go.ui.components.MediaTile
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/** Connects the ViewModel to the UI. Used by the app. */
@Composable
fun HomeScreen(
    onShowAllSeries: () -> Unit,
    onShowAllMovies: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    HomeContent(
        state = state,
        onRetry = viewModel::reload,
        onShowAllSeries = onShowAllSeries,
        onShowAllMovies = onShowAllMovies,
        modifier = modifier,
    )
}

/** The pure UI: the rows, or loading, error or empty. The top bar is above it, in AppNavigation. */
@Composable
fun HomeContent(
    state: HomeState,
    onRetry: () -> Unit,
    onShowAllSeries: () -> Unit,
    onShowAllMovies: () -> Unit,
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
                    item { MediaRow(stringResource(R.string.home_continue_watching), state.continueWatching, wide = true) }
                }
                if (state.latestSeries.isNotEmpty()) {
                    item {
                        MediaRow(stringResource(R.string.series), state.latestSeries, wide = false, onShowAll = onShowAllSeries)
                    }
                }
                if (state.latestMovies.isNotEmpty()) {
                    item {
                        MediaRow(stringResource(R.string.movies), state.latestMovies, wide = false, onShowAll = onShowAllMovies)
                    }
                }
            }
        }
    }
}

/**
 * A heading and a row of tiles that scrolls sideways.
 * With [onShowAll], the heading gets an arrow and the whole heading can be tapped.
 */
@Composable
private fun MediaRow(title: String, items: List<MediaItem>, wide: Boolean, onShowAll: (() -> Unit)? = null) {
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
                MediaTile(item = item, wide = wide, modifier = Modifier.width(if (wide) 240.dp else 120.dp))
            }
        }
    }
}

// --- Previews: one per state of the screen ---

private val sampleState = HomeState(
    loading = false,
    continueWatching = listOf(
        MediaItem(id = "1", title = "Example Series", seasonNumber = 1, episodeNumber = 3, progress = 0.4f),
        MediaItem(id = "2", title = "Example Movie", progress = 0.75f),
        // Next episode of a series, not started yet: no bar.
        MediaItem(id = "11", title = "Next Up Series", seasonNumber = 1, episodeNumber = 9),
    ),
    latestSeries = listOf(
        MediaItem(id = "3", title = "First Series"),
        MediaItem(id = "4", title = "Second Series"),
        MediaItem(id = "5", title = "Series With A Very Long Name"),
        MediaItem(id = "6", title = "Watched Series", watched = true),
    ),
    latestMovies = listOf(
        MediaItem(id = "7", title = "First Movie"),
        MediaItem(id = "8", title = "Second Movie"),
        MediaItem(id = "9", title = "Third Movie"),
        MediaItem(id = "10", title = "Watched Movie", watched = true),
    ),
)

@Composable
private fun HomePreview(state: HomeState) {
    Fin2goTheme {
        // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            // In the app the top bar comes from AppNavigation; here it is added so it looks the same.
            Column(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
                AppTopBar(userName = "sen", profileImageUrl = null, onSignOut = {})
                HomeContent(
                    state = state,
                    onRetry = {},
                    onShowAllSeries = {},
                    onShowAllMovies = {},
                )
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
