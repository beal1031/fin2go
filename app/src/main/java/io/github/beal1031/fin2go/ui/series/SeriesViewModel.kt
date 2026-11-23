package io.github.beal1031.fin2go.ui.series

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beal1031.fin2go.data.media.EpisodeDetails
import io.github.beal1031.fin2go.data.media.MediaRepository
import io.github.beal1031.fin2go.data.media.SeriesDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the series page shows, and which season is chosen. */
data class SeriesState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val series: SeriesDetails? = null,
    val selectedSeason: Int? = null,
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

    private val _state = MutableStateFlow(SeriesState())
    val state: StateFlow<SeriesState> = _state.asStateFlow()

    init {
        reload()
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

    private fun SeriesDetails.withEpisode(episode: EpisodeDetails) =
        copy(episodes = episodes.map { if (it.id == episode.id) episode else it })
}
