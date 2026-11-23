package io.github.beal1031.fin2go.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.media.MediaItem
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.PreviewImage
import io.github.beal1031.fin2go.ui.WithPreviewImages
import io.github.beal1031.fin2go.ui.components.AppTopBar
import io.github.beal1031.fin2go.ui.components.CenteredBox
import io.github.beal1031.fin2go.ui.components.MediaTile
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/** Connects the ViewModel to the UI. Used by the app. */
@Composable
fun LibraryScreen(
    type: LibraryType,
    onOpenMovie: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    // The ViewModel needs to know which list to load, so it is created here with the type.
    viewModel: LibraryViewModel = viewModel { LibraryViewModel(checkNotNull(this[APPLICATION_KEY]), type) },
) {
    val state by viewModel.state.collectAsState()
    // Each time the screen is shown again, e.g. back from a movie or back in the app: load the current state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }

    LibraryContent(
        type = type,
        state = state,
        onRetry = viewModel::reload,
        onOpenMovie = onOpenMovie,
        modifier = modifier,
    )
}

/**
 * The pure UI: the poster grid, or loading, error or empty. The top bar is above it, in AppNavigation.
 * A tap on a movie opens its detail page; series do not react yet.
 */
@Composable
fun LibraryContent(
    type: LibraryType,
    state: LibraryState,
    onRetry: () -> Unit,
    onOpenMovie: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> CenteredBox { CircularProgressIndicator() }

            state.error -> CenteredBox {
                Text(stringResource(R.string.load_failed), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }

            state.items.isEmpty() -> CenteredBox {
                Text(stringResource(type.empty), textAlign = TextAlign.Center)
            }

            // The grid is at most 400dp wide and centered, so posters do not get huge on wide screens.
            else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.widthIn(max = 400.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        MediaTile(
                            item = item,
                            wide = false,
                            onClick = if (type == LibraryType.MOVIES) ({ onOpenMovie(item) }) else null,
                        )
                    }
                }
            }
        }
    }
}

// --- Previews: one per state of the screen ---

private val sampleSeries = LibraryState(
    loading = false,
    items = listOf(
        MediaItem(id = "1", title = "Big Buck Bunny", imageUrl = PreviewImage.POSTER_BIG_BUCK_BUNNY.url),
        MediaItem(id = "2", title = "Caminandes", imageUrl = PreviewImage.POSTER_CAMINANDES.url),
        MediaItem(
            id = "3",
            title = "Cosmos Laundromat: First Cycle",
            imageUrl = PreviewImage.POSTER_COSMOS_LAUNDROMAT.url,
        ),
        MediaItem(
            id = "4",
            title = "Elephants Dream",
            imageUrl = PreviewImage.POSTER_ELEPHANTS_DREAM.url,
            watched = true,
        ),
        MediaItem(id = "5", title = "Sintel", imageUrl = PreviewImage.POSTER_SINTEL.url),
        MediaItem(id = "6", title = "Spring", imageUrl = PreviewImage.POSTER_SPRING.url, watched = true),
        MediaItem(id = "7", title = "Sprite Fright", imageUrl = PreviewImage.POSTER_SPRITE_FRIGHT.url),
    ),
)

private val sampleMovies = LibraryState(
    loading = false,
    items = listOf(
        MediaItem(id = "1", title = "Big Buck Bunny", imageUrl = PreviewImage.POSTER_BIG_BUCK_BUNNY.url),
        MediaItem(
            id = "2",
            title = "Cosmos Laundromat: First Cycle",
            imageUrl = PreviewImage.POSTER_COSMOS_LAUNDROMAT.url,
        ),
        MediaItem(
            id = "3",
            title = "Elephants Dream",
            imageUrl = PreviewImage.POSTER_ELEPHANTS_DREAM.url,
            progress = 0.3f,
        ),
        MediaItem(id = "4", title = "Sintel", imageUrl = PreviewImage.POSTER_SINTEL.url, watched = true),
        MediaItem(id = "5", title = "Tears of Steel", imageUrl = PreviewImage.POSTER_TEARS_OF_STEEL.url),
    ),
)

@Composable
private fun LibraryPreview(type: LibraryType, state: LibraryState) {
    Fin2goTheme {
        WithPreviewImages {
            // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                // In the app the top bar comes from AppNavigation; here it is added so it looks the same.
                Column(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
                    AppTopBar(
                        userName = "sen",
                        profileImageUrl = PreviewImage.PROFILE.url,
                        onSignOut = {},
                        title = stringResource(type.title),
                        onBack = {},
                    )
                    LibraryContent(type = type, state = state, onRetry = {}, onOpenMovie = {})
                }
            }
        }
    }
}

@Preview(name = "1 Loading", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun LibraryLoadingPreview() = LibraryPreview(LibraryType.SERIES, LibraryState())

@Preview(name = "2 All series", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun LibrarySeriesPreview() = LibraryPreview(LibraryType.SERIES, sampleSeries)

@Preview(name = "2b All movies", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun LibraryMoviesPreview() = LibraryPreview(LibraryType.MOVIES, sampleMovies)

@Preview(name = "2c German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "2d French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "2e Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun LibraryLanguagesPreview() = LibraryPreview(LibraryType.SERIES, sampleSeries)

@Preview(name = "3 Error", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun LibraryErrorPreview() = LibraryPreview(LibraryType.SERIES, LibraryState(loading = false, error = true))

@Preview(name = "4 No series yet", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun LibraryEmptySeriesPreview() = LibraryPreview(LibraryType.SERIES, LibraryState(loading = false))

@Preview(name = "4b No movies yet", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun LibraryEmptyMoviesPreview() = LibraryPreview(LibraryType.MOVIES, LibraryState(loading = false))
