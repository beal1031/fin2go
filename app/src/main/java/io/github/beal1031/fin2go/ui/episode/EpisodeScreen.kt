package io.github.beal1031.fin2go.ui.episode

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.media.EpisodeDetails
import io.github.beal1031.fin2go.data.media.EpisodePageDetails
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.PreviewImage
import io.github.beal1031.fin2go.ui.WithPreviewImages
import io.github.beal1031.fin2go.ui.components.AppTopBar
import io.github.beal1031.fin2go.ui.components.CenteredBox
import io.github.beal1031.fin2go.ui.components.DetailHeader
import io.github.beal1031.fin2go.ui.components.PlayButtons
import io.github.beal1031.fin2go.ui.components.episodeLabel
import io.github.beal1031.fin2go.ui.components.runtimeText
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme
import io.github.beal1031.fin2go.ui.withoutTop

/** Connects the ViewModel to the UI. Used by the app. */
@Composable
fun EpisodeScreen(
    episodeId: String,
    statusBarHeight: Dp,
    modifier: Modifier = Modifier,
    // The ViewModel needs to know which episode to load, so it is created here with the id.
    viewModel: EpisodeViewModel = viewModel { EpisodeViewModel(checkNotNull(this[APPLICATION_KEY]), episodeId) },
) {
    val state by viewModel.state.collectAsState()
    // Kept when the phone is rotated.
    var overviewExpanded by rememberSaveable { mutableStateOf(false) }

    EpisodeContent(
        state = state,
        overviewExpanded = overviewExpanded,
        onToggleOverview = { overviewExpanded = !overviewExpanded },
        onRetry = viewModel::reload,
        // Playing comes in a later step.
        onPlay = {},
        onStartOver = {},
        onToggleWatched = viewModel::toggleWatched,
        statusBarHeight = statusBarHeight,
        modifier = modifier,
    )
}

/**
 * The pure UI: the details of the episode, or loading or error. The top bar lies over it, in AppNavigation.
 * As on the movie page, the picture runs behind the status bar; [statusBarHeight] makes it that much taller.
 */
@Composable
fun EpisodeContent(
    state: EpisodeState,
    overviewExpanded: Boolean,
    onToggleOverview: () -> Unit,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onStartOver: () -> Unit,
    onToggleWatched: () -> Unit,
    statusBarHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> CenteredBox { CircularProgressIndicator() }

            state.error || state.details == null -> CenteredBox {
                Text(stringResource(R.string.load_failed), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }

            else -> EpisodeDetailsView(
                state.details,
                overviewExpanded,
                onToggleOverview,
                onPlay,
                onStartOver,
                onToggleWatched,
                statusBarHeight,
            )
        }
    }
}

/**
 * Like the movie page: picture at the top, then the series name, the episode title, the facts,
 * the play buttons and the description. The whole page scrolls.
 */
@Composable
private fun EpisodeDetailsView(
    details: EpisodePageDetails,
    overviewExpanded: Boolean,
    onToggleOverview: () -> Unit,
    onPlay: () -> Unit,
    onStartOver: () -> Unit,
    onToggleWatched: () -> Unit,
    statusBarHeight: Dp,
) {
    val episode = details.episode
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        DetailHeader(details.backdropUrl, details.posterUrl, statusBarHeight)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Plain text: the back arrow already leads to the series page.
            details.seriesTitle?.let { seriesTitle ->
                Text(
                    text = seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(text = episode.title, style = MaterialTheme.typography.headlineSmall)

            // e.g. "S1:E2 · 3 min · Watched"; missing parts are left out.
            val facts = listOfNotNull(
                episodeLabel(episode),
                episode.runtimeMinutes?.let { runtimeText(it) },
                if (episode.watched) stringResource(R.string.watched) else null,
            )
            Text(
                text = facts.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Above the description, so it stays in reach when the description is long or expanded.
            PlayButtons(
                progress = episode.progress,
                remainingMinutes = episode.remainingMinutes,
                watched = episode.watched,
                onPlay = onPlay,
                onStartOver = onStartOver,
                onToggleWatched = onToggleWatched,
                modifier = Modifier.padding(top = 8.dp),
            )
            episode.overview?.let { overview ->
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

private val sampleDetails = EpisodePageDetails(
    episode = EpisodeDetails(
        id = "e2",
        seasonNumber = 1,
        episodeNumber = 2,
        title = "Gran Dillama",
        runtimeMinutes = 3,
        overview = "Koro the llama wants the grass on the other side of the fence. It looks greener there, and he " +
            "tries everything to get to it: jumping, digging, squeezing through. The fence, a stubborn armadillo and " +
            "his own impatience stand in the way, and every new idea ends a little worse than the one before.",
        imageUrl = PreviewImage.EPISODE_CAMINANDES_2.url,
    ),
    seriesTitle = "Caminandes",
    backdropUrl = PreviewImage.EPISODE_CAMINANDES_2.url,
    posterUrl = PreviewImage.POSTER_CAMINANDES.url,
)

@Composable
private fun EpisodePreview(state: EpisodeState, overviewExpanded: Boolean = false) {
    Fin2goTheme {
        WithPreviewImages {
            // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                // As in MainActivity and AppNavigation: the page starts behind the status bar,
                // the top bar lies over it, just below the status bar.
                val statusBarHeight = innerPadding.calculateTopPadding()
                Box(modifier = Modifier.padding(innerPadding.withoutTop()).consumeWindowInsets(innerPadding)) {
                    EpisodeContent(
                        state = state,
                        overviewExpanded = overviewExpanded,
                        onToggleOverview = {},
                        onRetry = {},
                        onPlay = {},
                        onStartOver = {},
                        onToggleWatched = {},
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

private fun loaded(details: EpisodePageDetails) = EpisodeState(loading = false, details = details)

@Preview(name = "1 Loading", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun EpisodeLoadingPreview() = EpisodePreview(EpisodeState())

@Preview(name = "2 Episode", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun EpisodeDetailsPreview() = EpisodePreview(loaded(sampleDetails))

@Preview(name = "2b Description expanded", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun EpisodeExpandedPreview() = EpisodePreview(loaded(sampleDetails), overviewExpanded = true)

@Preview(name = "2c No episode picture: backdrop of the series", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun EpisodeSeriesBackdropPreview() =
    EpisodePreview(loaded(sampleDetails.copy(backdropUrl = PreviewImage.BACKDROP_CAMINANDES.url)))

@Preview(name = "2d No wide picture: blurred poster", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun EpisodeNoBackdropPreview() = EpisodePreview(loaded(sampleDetails.copy(backdropUrl = null)))

@Preview(name = "2e Special, only a title, no picture", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun EpisodeMinimalPreview() = EpisodePreview(
    loaded(EpisodePageDetails(EpisodeDetails(id = "sp1", seasonNumber = 0, episodeNumber = 1, title = "Making of"))),
)

@Preview(name = "2f German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "2g French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "2h Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun EpisodeLanguagesPreview() = EpisodePreview(loaded(sampleDetails))

private val startedDetails =
    sampleDetails.copy(episode = sampleDetails.episode.copy(progress = 0.4f, remainingMinutes = 2))

@Preview(name = "4 Started: resume or start over", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun EpisodeStartedPreview() = EpisodePreview(loaded(startedDetails))

private val watchedDetails = sampleDetails.copy(episode = sampleDetails.episode.copy(watched = true))

@Preview(name = "5 Watched: play again", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun EpisodeWatchedPreview() = EpisodePreview(loaded(watchedDetails))

@Preview(name = "3 Error", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun EpisodeErrorPreview() = EpisodePreview(EpisodeState(loading = false, error = true))
