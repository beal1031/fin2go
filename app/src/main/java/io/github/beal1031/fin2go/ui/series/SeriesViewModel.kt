package io.github.beal1031.fin2go.ui.series

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beal1031.fin2go.data.download.DownloadRepository
import io.github.beal1031.fin2go.data.download.DownloadState
import io.github.beal1031.fin2go.data.media.EpisodeDetails
import io.github.beal1031.fin2go.data.media.MediaRepository
import io.github.beal1031.fin2go.data.media.SeriesDetails
import io.github.beal1031.fin2go.data.playback.ProgressSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the series page shows, and which season is chosen. */
data class SeriesState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val series: SeriesDetails? = null,
    val selectedSeason: Int? = null,
    /** The download button of the chosen season; null when there is nothing to download or delete. */
    val seasonDownload: SeasonDownload? = null,
)

/**
 * What the download button of a season shows ([state]: the whole season together) and what it changes.
 */
data class SeasonDownload(
    val state: DownloadState,
    /** The unwatched episodes that are not on the phone yet: downloaded on a tap. */
    val missing: List<EpisodeDetails> = emptyList(),
    /** Episodes waiting or loading right now: cancelled together. */
    val runningIds: List<String> = emptyList(),
    /** Episodes on the phone: deleted together. */
    val downloadedIds: List<String> = emptyList(),
)

/**
 * Loads the series with [seriesId] and keeps the chosen season.
 * At the start, the season of [episodeId] is chosen (e.g. from "Continue watching"),
 * otherwise the season of the episode the user is at.
 */
class SeriesViewModel(
    application: Application,
    private val seriesId: String,
    private val episodeId: String?,
) : AndroidViewModel(application) {
    private val media = MediaRepository(application)
    private val downloads = DownloadRepository(application)

    private val _state = MutableStateFlow(SeriesState())
    /** How far the download of each episode is, updated while they run. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val episodeDownloads = _state
        .map { state -> state.series?.episodes?.map { it.id }.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { downloads.states(it) }

    /** The loaded series, with the downloaded episodes marked and the download button of the chosen season. */
    val state: StateFlow<SeriesState> = combine(_state, episodeDownloads) { state, downloadStates ->
        val series = state.series?.let { series ->
            series.copy(
                episodes = series.episodes.map { it.copy(downloaded = downloadStates[it.id] == DownloadState.Downloaded) },
            )
        }
        val season = state.selectedSeason
        state.copy(
            series = series,
            seasonDownload = if (series != null && season != null) {
                seasonDownload(series.episodesOf(season), downloadStates)
            } else {
                null
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _state.value)

    init {
        reload()
        // Progress watched without internet reached the server later: show it.
        viewModelScope.launch { ProgressSync(getApplication()).sent.collect { refresh() } }
    }

    /** Loads the series again. On failure, the error with a retry button is shown. */
    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            try {
                val series = media.series(seriesId)
                val start = series.episodes.firstOrNull { it.id == episodeId }
                    ?: series.episodes.firstOrNull { it.id == series.currentEpisodeId }
                _state.update { it.copy(loading = false, series = series, selectedSeason = start?.seasonNumber) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    private var shownBefore = false

    /**
     * Called each time the page is shown. The first time the series is already loading (see init);
     * later it loads again quietly, e.g. after an episode was marked on the episode page.
     * The chosen season stays. If it fails, the page already shown stays.
     */
    fun onShown() {
        if (shownBefore) refresh()
        shownBefore = true
    }

    private fun refresh() {
        viewModelScope.launch {
            try {
                val series = media.series(seriesId)
                _state.update { it.copy(series = series) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stays as it is.
            }
        }
    }

    fun selectSeason(season: Int) {
        _state.update { it.copy(selectedSeason = season) }
    }

    /**
     * Marks [episode] as watched, or as not watched when it already is. Watched also removes a started position.
     * The page changes at once; if the server call fails, it goes back to how it was.
     */
    fun toggleWatched(episode: EpisodeDetails) {
        val before = _state.value.series ?: return
        val watched = !episode.watched
        val changed = episode.copy(watched = watched, progress = null, remainingMinutes = null)
        _state.update { it.copy(series = before.withEpisode(changed)) }
        viewModelScope.launch {
            try {
                media.setWatched(episode.id, watched)
                // What the server saved, e.g. a position it removed.
                val series = media.series(seriesId)
                _state.update { it.copy(series = series) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(series = before) }
            }
        }
    }

    /**
     * Marks all episodes of [season] as watched, or all as not watched. Only the episodes that change are sent.
     * As for one episode: the page changes at once and goes back if the server calls fail.
     */
    fun setSeasonWatched(season: Int, watched: Boolean) {
        val before = _state.value.series ?: return
        val changing = before.changingEpisodes(season, watched)
        if (changing.isEmpty()) return
        val ids = changing.map { it.id }.toSet()
        _state.update {
            it.copy(
                series = before.copy(
                    episodes = before.episodes.map { episode ->
                        if (episode.id in ids) episode.copy(watched = watched, progress = null, remainingMinutes = null)
                        else episode
                    },
                ),
            )
        }
        viewModelScope.launch {
            try {
                // All at the same time; if one fails, the others are cancelled and the error lands in catch.
                coroutineScope {
                    changing.map { async { media.setWatched(it.id, watched) } }.awaitAll()
                }
                _state.update { it.copy(series = media.series(seriesId)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Some episodes may already be changed on the server, so load what it really has.
                try {
                    _state.update { it.copy(series = media.series(seriesId)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(series = before) }
                }
            }
        }
    }

    /** Downloads all unwatched episodes of the chosen season that are not on the phone yet. */
    fun downloadSeason() {
        state.value.seasonDownload?.missing?.forEach { downloads.start(it.id) }
    }

    /** Stops all downloads of the chosen season that are still waiting or loading. Finished ones stay. */
    fun cancelSeasonDownloads() {
        val ids = state.value.seasonDownload?.runningIds ?: return
        viewModelScope.launch { ids.forEach { downloads.cancel(it) } }
    }

    /** Removes all downloaded episodes of the chosen season from the phone. */
    fun deleteSeasonDownloads() {
        val ids = state.value.seasonDownload?.downloadedIds ?: return
        viewModelScope.launch { ids.forEach { downloads.delete(it) } }
    }

    private fun SeriesDetails.withEpisode(episode: EpisodeDetails) =
        copy(episodes = episodes.map { if (it.id == episode.id) episode else it })
}

/**
 * The download button of a season, from the episodes of that season and how far each download is.
 * Loading while any episode waits or loads, with the share of the whole batch; the arrow while unwatched episodes
 * are missing; done when all unwatched episodes are on the phone. Null when there is nothing to download or delete.
 */
private fun seasonDownload(episodes: List<EpisodeDetails>, states: Map<String, DownloadState>): SeasonDownload? {
    fun stateOf(episode: EpisodeDetails) = states[episode.id] ?: DownloadState.NotDownloaded
    val running = episodes.filter { stateOf(it) is DownloadState.Downloading || stateOf(it) == DownloadState.Waiting }
    val downloadedIds = episodes.filter { stateOf(it) == DownloadState.Downloaded }.map { it.id }
    val missing = episodes.filter { !it.watched && stateOf(it) == DownloadState.NotDownloaded }
    val runningIds = running.map { it.id }
    val state = when {
        running.isNotEmpty() -> {
            val progress = running.mapNotNull { (stateOf(it) as? DownloadState.Downloading)?.progress }
            if (progress.isEmpty() && downloadedIds.isEmpty()) {
                DownloadState.Waiting
            } else {
                // Finished episodes count as done, waiting ones as not started.
                DownloadState.Downloading((downloadedIds.size + progress.sum()) / (downloadedIds.size + running.size))
            }
        }
        missing.isNotEmpty() -> DownloadState.NotDownloaded
        downloadedIds.isNotEmpty() -> DownloadState.Downloaded
        else -> return null
    }
    return SeasonDownload(state, missing, runningIds, downloadedIds)
}
