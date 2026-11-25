package io.github.beal1031.fin2go.ui.series

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.download.DownloadState
import io.github.beal1031.fin2go.data.media.EpisodeDetails
import io.github.beal1031.fin2go.data.media.SeriesDetails
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.PreviewImage
import io.github.beal1031.fin2go.ui.WithPreviewImages
import io.github.beal1031.fin2go.ui.components.OnConnectionChange
import io.github.beal1031.fin2go.ui.components.AppTopBar
import io.github.beal1031.fin2go.ui.components.CenteredBox
import io.github.beal1031.fin2go.ui.components.DetailHeader
import io.github.beal1031.fin2go.ui.components.PlayButtons
import io.github.beal1031.fin2go.ui.components.WatchedButton
import io.github.beal1031.fin2go.ui.components.ConfirmDialog
import io.github.beal1031.fin2go.ui.components.DownloadIconButton
import io.github.beal1031.fin2go.ui.components.DownloadedIcon
import io.github.beal1031.fin2go.ui.components.rememberNotificationPermission
import io.github.beal1031.fin2go.ui.components.episodeLabel
import io.github.beal1031.fin2go.ui.components.runtimeText
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme
import io.github.beal1031.fin2go.ui.withoutTop

/** Connects the ViewModel to the UI. Used by the app. */
@Composable
fun SeriesScreen(
    seriesId: String,
    episodeId: String?,
    onOpenEpisode: (EpisodeDetails) -> Unit,
    onPlay: (EpisodeDetails) -> Unit,
    statusBarHeight: Dp,
    modifier: Modifier = Modifier,
    // The ViewModel needs to know which series (and episode) to load, so it is created here with the ids.
    viewModel: SeriesViewModel = viewModel {
        SeriesViewModel(checkNotNull(this[APPLICATION_KEY]), seriesId, episodeId)
    },
) {
    val state by viewModel.state.collectAsState()
    // Each time the page is shown again, e.g. back from an episode: load the current state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onShown() }
    // Offline the page shows the downloads, online again what the server has.
    OnConnectionChange { viewModel.onShown() }
    // Kept when the phone is rotated.
    var overviewExpanded by rememberSaveable { mutableStateOf(false) }
    // Whether the question "Mark season … as watched?" is open.
    var confirmingSeason by rememberSaveable { mutableStateOf(false) }
    // Whether the question of the season's download button is open.
    var confirmingSeasonDownload by rememberSaveable { mutableStateOf(false) }

    SeriesContent(
        state = state,
        overviewExpanded = overviewExpanded,
        confirmingSeason = confirmingSeason,
        onToggleOverview = { overviewExpanded = !overviewExpanded },
        onRetry = viewModel::reload,
        onSelectSeason = viewModel::selectSeason,
        onPlay = onPlay,
        onShowDetails = onOpenEpisode,
        onToggleWatched = viewModel::toggleWatched,
        onAskMarkSeason = { confirmingSeason = true },
        onDismissMarkSeason = { confirmingSeason = false },
        onMarkSeason = { season, watched ->
            confirmingSeason = false
            viewModel.setSeasonWatched(season, watched)
        },
        confirmingSeasonDownload = confirmingSeasonDownload,
        onAskSeasonDownload = { confirmingSeasonDownload = true },
        onDismissSeasonDownload = { confirmingSeasonDownload = false },
        onDownloadSeason = {
            confirmingSeasonDownload = false
            viewModel.downloadSeason()
        },
        onCancelSeasonDownloads = {
            confirmingSeasonDownload = false
            viewModel.cancelSeasonDownloads()
        },
        onDeleteSeasonDownloads = {
            confirmingSeasonDownload = false
            viewModel.deleteSeasonDownloads()
        },
        statusBarHeight = statusBarHeight,
        modifier = modifier,
    )
}

/**
 * The pure UI: the series with its seasons and episodes, or loading or error.
 * The top bar lies over it, in AppNavigation. As on the movie page, the picture runs behind the status bar.
 */
@Composable
fun SeriesContent(
    state: SeriesState,
    overviewExpanded: Boolean,
    confirmingSeason: Boolean,
    onToggleOverview: () -> Unit,
    onRetry: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onPlay: (EpisodeDetails) -> Unit,
    onShowDetails: (EpisodeDetails) -> Unit,
    onToggleWatched: (EpisodeDetails) -> Unit,
    onAskMarkSeason: () -> Unit,
    onDismissMarkSeason: () -> Unit,
    onMarkSeason: (season: Int, watched: Boolean) -> Unit,
    confirmingSeasonDownload: Boolean,
    onAskSeasonDownload: () -> Unit,
    onDismissSeasonDownload: () -> Unit,
    onDownloadSeason: () -> Unit,
    onCancelSeasonDownloads: () -> Unit,
    onDeleteSeasonDownloads: () -> Unit,
    statusBarHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> CenteredBox { CircularProgressIndicator() }

            state.error || state.series == null -> CenteredBox {
                Text(stringResource(R.string.load_failed), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }

            else -> SeriesDetailsView(
                series = state.series,
                selectedSeason = state.selectedSeason,
                overviewExpanded = overviewExpanded,
                onToggleOverview = onToggleOverview,
                onSelectSeason = onSelectSeason,
                onPlay = onPlay,
                onShowDetails = onShowDetails,
                onToggleWatched = onToggleWatched,
                onAskMarkSeason = onAskMarkSeason,
                seasonDownload = state.seasonDownload,
                onAskSeasonDownload = onAskSeasonDownload,
                statusBarHeight = statusBarHeight,
            )
        }
    }
    val series = state.series
    val season = state.selectedSeason
    if (confirmingSeason && series != null && season != null) {
        MarkSeasonDialog(series, season, onConfirm = onMarkSeason, onDismiss = onDismissMarkSeason)
    }
    val seasonDownload = state.seasonDownload
    if (confirmingSeasonDownload && seasonDownload != null && season != null) {
        SeasonDownloadDialog(
            download = seasonDownload,
            season = season,
            onDownload = onDownloadSeason,
            onCancel = onCancelSeasonDownloads,
            onDelete = onDeleteSeasonDownloads,
            onDismiss = onDismissSeasonDownload,
        )
    }
}

/**
 * From top to bottom: picture, title, facts, description and play buttons of the series;
 * the season numbers, then one line per episode of the chosen season. The whole page scrolls.
 */
@Composable
private fun SeriesDetailsView(
    series: SeriesDetails,
    selectedSeason: Int?,
    overviewExpanded: Boolean,
    onToggleOverview: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onPlay: (EpisodeDetails) -> Unit,
    onShowDetails: (EpisodeDetails) -> Unit,
    onToggleWatched: (EpisodeDetails) -> Unit,
    onAskMarkSeason: () -> Unit,
    seasonDownload: SeasonDownload?,
    onAskSeasonDownload: () -> Unit,
    statusBarHeight: Dp,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DetailHeader(series.backdropUrl, series.posterUrl, statusBarHeight)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = series.title, style = MaterialTheme.typography.headlineSmall)
            // e.g. "2013–2016 · FSK-6"; missing parts are left out.
            val facts = listOfNotNull(series.years, series.ageRating)
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (series.genres.isNotEmpty()) {
                Text(
                    text = series.genres.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            series.overview?.let { overview ->
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    // Shorter than on the movie page, so the episodes are not pushed too far down.
                    maxLines = if (overviewExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    // indication = null: no ripple animation on tap.
                    modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onToggleOverview),
                )
            }
            SeriesPlayButtons(series, onPlay, modifier = Modifier.padding(top = 8.dp))
        }

        if (selectedSeason != null) {
            SeasonGrid(series, selectedSeason, onSelectSeason)
            val seasonWatched = series.episodesOf(selectedSeason).all { it.watched }
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.series_episodes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // Asks first, see SeasonDownloadDialog.
                seasonDownload?.let { DownloadIconButton(state = it.state, onClick = onAskSeasonDownload) }
                // Asks first, see MarkSeasonDialog.
                TextButton(onClick = onAskMarkSeason) {
                    Text(
                        stringResource(
                            if (seasonWatched) R.string.series_mark_season_unwatched else R.string.series_mark_season_watched,
                        ),
                    )
                }
            }
            // A Column and not a LazyColumn: the whole page scrolls, and a season has few enough episodes.
            Column {
                series.episodesOf(selectedSeason).forEach { episode ->
                    EpisodeLine(
                        episode = episode,
                        onPlay = { onPlay(episode) },
                        onShowDetails = { onShowDetails(episode) },
                        onToggleWatched = { onToggleWatched(episode) },
                    )
                }
            }
        }
    }
}

/**
 * The same buttons as on the movie page, for the whole series, with the episode they play above them:
 * nothing watched yet: "Play" (first episode); in between: "Resume" (the episode the user is at, with a bar when
 * it is started) and "Start over" (first episode); everything watched: "Play again" (first episode).
 * No watched button here; whole seasons are marked below, at the episodes.
 */
@Composable
private fun SeriesPlayButtons(
    series: SeriesDetails,
    onPlay: (EpisodeDetails) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = series.episodes.firstOrNull { it.seasonNumber != 0 } ?: series.episodes.firstOrNull() ?: return
    val inBetween = series.started && !series.allWatched
    val current = series.currentEpisode.takeIf { inBetween } ?: first
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // e.g. "S1:E3 · Llamigos", so it is clear what the button plays.
        Text(
            text = episodeLabel(current) + " · " + current.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PlayButtons(
            progress = current.progress.takeIf { inBetween },
            remainingMinutes = current.remainingMinutes.takeIf { inBetween },
            watched = series.allWatched,
            onPlay = { onPlay(current) },
            onStartOver = { onPlay(first) },
            onToggleWatched = null,
            canResume = inBetween,
        )
    }
}

/**
 * Asks before a whole season is marked, as that changes many episodes at once and cannot be undone with one tap.
 * When all its episodes are watched, it marks them as not watched; otherwise all as watched.
 */
@Composable
private fun MarkSeasonDialog(
    series: SeriesDetails,
    season: Int,
    onConfirm: (season: Int, watched: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val watched = !series.episodesOf(season).all { it.watched }
    // Only the episodes that really change are counted, e.g. 8 when 6 of 14 are watched already.
    val count = series.changingEpisodes(season, watched).size
    val name = if (season == 0) stringResource(R.string.series_specials)
    else stringResource(R.string.series_season_number, season)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (watched) R.string.series_confirm_watched_title else R.string.series_confirm_unwatched_title,
                    name,
                ),
            )
        },
        text = {
            Text(
                pluralStringResource(
                    if (watched) R.plurals.series_confirm_watched_text else R.plurals.series_confirm_unwatched_text,
                    count,
                    count,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(season, watched) }) { Text(stringResource(R.string.series_confirm_mark)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Heading "Seasons" and one number tile per season, side by side.
 * When a line is full, the tiles go on in the next line below. A season counts as watched when all its episodes are.
 */
@Composable
private fun SeasonGrid(series: SeriesDetails, selectedSeason: Int, onSelectSeason: (Int) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.series_seasons),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            series.seasons.forEach { season ->
                val isSpecials = season == 0
                NumberTile(
                    label = if (isSpecials) stringResource(R.string.series_specials_short) else season.toString(),
                    description = if (isSpecials) stringResource(R.string.series_specials)
                    else stringResource(R.string.series_season_number, season),
                    selected = season == selectedSeason,
                    watched = series.episodesOf(season).all { it.watched },
                    onClick = { onSelectSeason(season) },
                )
            }
        }
    }
}

/**
 * Rounded square with a thick border and only the number in it. Chosen: filled grey. Watched: border and number dimmed.
 * [description] is read out by the screen reader instead of the bare number.
 */
@Composable
private fun NumberTile(
    label: String,
    description: String,
    selected: Boolean,
    watched: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (watched) 0.4f else 1f)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
            .border(width = 2.dp, color = color, shape = shape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = color)
    }
}

/**
 * The question of the season's download button, depending on its state: download the missing unwatched episodes
 * (with their number and size), cancel the running downloads, or delete the downloaded episodes.
 */
@Composable
private fun SeasonDownloadDialog(
    download: SeasonDownload,
    season: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = if (season == 0) stringResource(R.string.series_specials)
    else stringResource(R.string.series_season_number, season)
    when (download.state) {
        DownloadState.NotDownloaded -> {
            val count = download.missing.size
            // Only when the server knows the size of every episode, so the total is not too small.
            val size = download.missing.map { it.sizeBytes }.takeIf { sizes -> sizes.all { it != null } }
                ?.sumOf { it ?: 0L }
                ?.let { Formatter.formatShortFileSize(LocalContext.current, it) }
            ConfirmDialog(
                title = pluralStringResource(R.plurals.download_season_title, count, count),
                text = if (size != null) stringResource(R.string.download_season_text_size, name, size)
                else stringResource(R.string.download_season_text, name),
                confirm = stringResource(R.string.download_start),
                dismiss = stringResource(R.string.cancel),
                // From Android 13 on, the first download asks whether the app may show its progress.
                onConfirm = rememberNotificationPermission(onDownload),
                onDismiss = onDismiss,
            )
        }

        DownloadState.Waiting, is DownloadState.Downloading -> ConfirmDialog(
            title = stringResource(R.string.download_season_cancel_title, name),
            text = stringResource(R.string.download_season_cancel_text),
            confirm = stringResource(R.string.download_season_cancel_confirm),
            dismiss = stringResource(R.string.download_cancel_keep),
            onConfirm = onCancel,
            onDismiss = onDismiss,
        )

        DownloadState.Downloaded -> {
            val count = download.downloadedIds.size
            ConfirmDialog(
                title = stringResource(R.string.download_season_delete_title, name),
                text = pluralStringResource(R.plurals.download_season_delete_text, count, count),
                confirm = stringResource(R.string.download_delete_confirm),
                dismiss = stringResource(R.string.cancel),
                onConfirm = onDelete,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * One episode: picture (with a bar when started), number, name and running time.
 * On the right, one above the other: the watched button and the "i" for the episode page.
 * A tap on the line itself plays the episode.
 */
@Composable
private fun EpisodeLine(
    episode: EpisodeDetails,
    onPlay: () -> Unit,
    onShowDetails: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 160 wide makes the picture 90 high, just enough for the two buttons on the right.
        Box(
            modifier = Modifier
                .width(160.dp)
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                // Grey area while the picture loads or when there is none.
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = episode.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Same bar as on the tiles of the home screen.
            episode.progress?.let { progress ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = episodeLabel(episode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (episode.downloaded) DownloadedIcon()
            }
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // e.g. "22 min · Watched", or "22 min · 14 min left" when started.
            val facts = listOfNotNull(
                episode.runtimeMinutes?.let { runtimeText(it) },
                episode.remainingMinutes?.let { stringResource(R.string.movie_time_left, runtimeText(it)) },
                if (episode.watched) stringResource(R.string.watched) else null,
            )
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Two 40 dp buttons and a small gap fit the 90 dp of the picture.
        // Each still reacts to touch on 48 dp, as Android recommends; the two areas only touch in the middle.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WatchedButton(watched = episode.watched, onClick = onToggleWatched, modifier = Modifier.size(40.dp))
            // Centered under the check mark and clearly smaller, so it is not pressed by mistake.
            IconButton(
                onClick = onShowDetails,
                modifier = Modifier.size(40.dp),
            ) {
                // White circle outline and white "i", transparent inside.
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(R.string.series_episode_details),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// --- Previews: one per state of the screen ---

private val episodeImages = listOf(
    PreviewImage.EPISODE_CAMINANDES_1.url,
    PreviewImage.EPISODE_CAMINANDES_2.url,
    PreviewImage.EPISODE_CAMINANDES_3.url,
)

/** Season 1 with the three real Caminandes episodes, a made-up long season 2 and two specials. */
private val sampleEpisodes = listOf(
    EpisodeDetails(
        id = "e1", seasonNumber = 1, episodeNumber = 1, title = "Llama Drama", runtimeMinutes = 2,
        imageUrl = episodeImages[0], watched = true,
    ),
    EpisodeDetails(
        id = "e2", seasonNumber = 1, episodeNumber = 2, title = "Gran Dillama", runtimeMinutes = 3,
        imageUrl = episodeImages[1], progress = 0.4f, remainingMinutes = 2,
    ),
    EpisodeDetails(
        id = "e3", seasonNumber = 1, episodeNumber = 3, title = "Llamigos", runtimeMinutes = 3,
        imageUrl = episodeImages[2],
    ),
) + (1..14).map { number ->
    EpisodeDetails(
        id = "s2e$number", seasonNumber = 2, episodeNumber = number, runtimeMinutes = 22,
        // One long name, to show it is cut after two lines.
        title = if (number == 2) "A very long episode name that does not fit into two lines at all" else "Episode $number",
        imageUrl = episodeImages[number % 3], watched = number <= 6,
    )
} + listOf(
    EpisodeDetails(id = "sp1", seasonNumber = 0, episodeNumber = 1, title = "Making of", runtimeMinutes = 12),
    EpisodeDetails(id = "sp2", seasonNumber = 0, episodeNumber = 2, title = "Behind the scenes", runtimeMinutes = 8),
)

private val sampleSeries = SeriesDetails(
    id = "1",
    title = "Caminandes",
    years = "2013–2016",
    ageRating = "FSK-0",
    genres = listOf("Animation", "Comedy"),
    overview = "Koro the llama lives in Patagonia and always wants what lies just out of reach: the grass behind the " +
        "fence, the berries on the other side of the ice. Every attempt ends a little differently than planned, " +
        "and over time he even finds a friend.",
    backdropUrl = PreviewImage.BACKDROP_CAMINANDES.url,
    posterUrl = PreviewImage.POSTER_CAMINANDES.url,
    episodes = sampleEpisodes,
    currentEpisodeId = "e2",
)

private fun season(number: Int, series: SeriesDetails = sampleSeries) =
    SeriesState(loading = false, series = series, selectedSeason = number)

@Composable
private fun SeriesPreview(
    state: SeriesState,
    overviewExpanded: Boolean = false,
    confirmingSeason: Boolean = false,
    confirmingSeasonDownload: Boolean = false,
) {
    Fin2goTheme {
        WithPreviewImages {
            // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                // As in MainActivity and AppNavigation: the page starts behind the status bar,
                // the top bar lies over it, just below the status bar.
                val statusBarHeight = innerPadding.calculateTopPadding()
                Box(modifier = Modifier.padding(innerPadding.withoutTop()).consumeWindowInsets(innerPadding)) {
                    SeriesContent(
                        state = state,
                        overviewExpanded = overviewExpanded,
                        confirmingSeason = confirmingSeason,
                        onToggleOverview = {},
                        onRetry = {},
                        onSelectSeason = {},
                        onPlay = {},
                        onShowDetails = {},
                        onToggleWatched = {},
                        onAskMarkSeason = {},
                        onDismissMarkSeason = {},
                        onMarkSeason = { _, _ -> },
                        confirmingSeasonDownload = confirmingSeasonDownload,
                        onAskSeasonDownload = {},
                        onDismissSeasonDownload = {},
                        onDownloadSeason = {},
                        onCancelSeasonDownloads = {},
                        onDeleteSeasonDownloads = {},
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
private fun SeriesLoadingPreview() = SeriesPreview(SeriesState())

private val newSeries = sampleSeries.copy(
    episodes = sampleEpisodes.map { it.copy(watched = false, progress = null, remainingMinutes = null) },
    currentEpisodeId = "e1",
)

@Preview(name = "1b Nothing watched yet: play", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesNewPreview() = SeriesPreview(season(1, newSeries))

private val nextNotStartedSeries = sampleSeries.copy(
    episodes = sampleEpisodes.map { if (it.id == "e2") it.copy(watched = true, progress = null, remainingMinutes = null) else it },
    currentEpisodeId = "e3",
)

@Preview(name = "1c Next episode not started: resume without bar", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesNextNotStartedPreview() = SeriesPreview(season(1, nextNotStartedSeries))

private val watchedSeries = sampleSeries.copy(
    episodes = sampleEpisodes.map {
        // The specials stay unwatched: they do not count for "everything watched".
        if (it.seasonNumber == 0) it else it.copy(watched = true, progress = null, remainingMinutes = null)
    },
)

@Preview(name = "1d Everything watched: play again", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesWatchedPreview() = SeriesPreview(season(1, watchedSeries))

@Preview(name = "8 Question: mark season as watched", showSystemUi = true, device = PREVIEW_PHONE)
@Preview(name = "8b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "8c French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "8d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun SeriesConfirmWatchedPreview() = SeriesPreview(season(2), confirmingSeason = true)

@Preview(name = "9 Question: mark watched season as not watched", showSystemUi = true, device = PREVIEW_PHONE)
@Preview(name = "9b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Composable
private fun SeriesConfirmUnwatchedPreview() = SeriesPreview(season(1, watchedSeries), confirmingSeason = true)

@Preview(name = "2 Season 1: watched, started, new; resume with bar", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesSeasonPreview() = SeriesPreview(season(1))

@Preview(name = "2b Description expanded", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesExpandedPreview() = SeriesPreview(season(1), overviewExpanded = true)

@Preview(name = "2c German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "2d French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "2e Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun SeriesLanguagesPreview() = SeriesPreview(season(1))

@Preview(name = "3 Long season, long name", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesLongSeasonPreview() = SeriesPreview(season(2))

@Preview(name = "4 Specials, without pictures", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesSpecialsPreview() = SeriesPreview(season(0))

@Preview(name = "5 Without backdrop: blurred poster, one season", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesNoBackdropPreview() = SeriesPreview(
    season(1, sampleSeries.copy(backdropUrl = null, episodes = sampleEpisodes.filter { it.seasonNumber == 1 })),
)

@Preview(name = "6 No episodes on the server", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesNoEpisodesPreview() = SeriesPreview(
    SeriesState(loading = false, series = sampleSeries.copy(episodes = emptyList(), currentEpisodeId = null)),
)

@Preview(name = "7 Error", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesErrorPreview() = SeriesPreview(SeriesState(loading = false, error = true))

private val downloadedSeries = sampleSeries.copy(
    episodes = sampleEpisodes.map { it.copy(downloaded = it.id == "e2" || it.id == "e3") },
)

/** Season 1 with its download button in [download]; e2 and e3 are on the phone in the episode list. */
private fun seasonWithDownload(download: SeasonDownload) = season(1, downloadedSeries).copy(seasonDownload = download)

private val missingEpisodes = sampleEpisodes.filter { it.seasonNumber == 1 && !it.watched }
    .map { it.copy(sizeBytes = 1_450_000_000L) }

@Preview(name = "10 Downloaded episodes marked, season button: download", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesDownloadedPreview() =
    SeriesPreview(seasonWithDownload(SeasonDownload(DownloadState.NotDownloaded, missing = missingEpisodes)))

@Preview(name = "10b Season button: loading", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesSeasonLoadingPreview() =
    SeriesPreview(seasonWithDownload(SeasonDownload(DownloadState.Downloading(0.6f), runningIds = listOf("e3"))))

@Preview(name = "10c Season button: all unwatched downloaded", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun SeriesSeasonDownloadedPreview() =
    SeriesPreview(seasonWithDownload(SeasonDownload(DownloadState.Downloaded, downloadedIds = listOf("e2", "e3"))))

@Preview(name = "11 Question: download season", showSystemUi = true, device = PREVIEW_PHONE)
@Preview(name = "11b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "11c French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "11d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun SeriesSeasonDownloadQuestionPreview() = SeriesPreview(
    seasonWithDownload(SeasonDownload(DownloadState.NotDownloaded, missing = missingEpisodes)),
    confirmingSeasonDownload = true,
)

@Preview(name = "12 Question: cancel season downloads", showSystemUi = true, device = PREVIEW_PHONE)
@Preview(name = "12b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "12c French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "12d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun SeriesSeasonCancelQuestionPreview() = SeriesPreview(
    seasonWithDownload(SeasonDownload(DownloadState.Downloading(0.6f), runningIds = listOf("e3"))),
    confirmingSeasonDownload = true,
)

@Preview(name = "13 Question: delete season downloads", showSystemUi = true, device = PREVIEW_PHONE)
@Preview(name = "13b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "13c French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "13d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun SeriesSeasonDeleteQuestionPreview() = SeriesPreview(
    seasonWithDownload(SeasonDownload(DownloadState.Downloaded, downloadedIds = listOf("e2", "e3"))),
    confirmingSeasonDownload = true,
)
