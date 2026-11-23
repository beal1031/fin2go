package io.github.beal1031.fin2go.ui.episode

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beal1031.fin2go.data.media.EpisodePageDetails
import io.github.beal1031.fin2go.data.media.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the episode page shows. */
data class EpisodeState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val details: EpisodePageDetails? = null,
)

/** Loads the episode with [episodeId]. */
class EpisodeViewModel(application: Application, private val episodeId: String) : AndroidViewModel(application) {
    private val media = MediaRepository(application)

    private val _state = MutableStateFlow(EpisodeState())
    val state: StateFlow<EpisodeState> = _state.asStateFlow()

    init {
        reload()
    }

    /** Loads the episode again. On failure, the error with a retry button is shown. */
    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            try {
                val details = media.episode(episodeId)
                _state.update { it.copy(loading = false, details = details) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    private var shownBefore = false

    /**
     * Called each time the page is shown. The first time the episode is already loading (see init);
     * later it loads again quietly, e.g. with the new progress after watching. If it fails, the page stays.
     */
    fun onShown() {
        if (shownBefore) refresh()
        shownBefore = true
    }

    private fun refresh() {
        viewModelScope.launch {
            try {
                val details = media.episode(episodeId)
                _state.update { it.copy(details = details) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stays as it is.
            }
        }
    }

    /**
     * Marks the episode as watched, or as not watched when it already is. Watched also removes a started position.
     * The page changes at once; if the server call fails, it goes back to how it was.
     */
    fun toggleWatched() {
        val before = _state.value.details ?: return
        val watched = !before.episode.watched
        val changed = before.episode.copy(watched = watched, progress = null, remainingMinutes = null)
        _state.update { it.copy(details = before.copy(episode = changed)) }
        viewModelScope.launch {
            try {
                media.setWatched(episodeId, watched)
                // What the server saved, e.g. a position it removed.
                val details = media.episode(episodeId)
                _state.update { it.copy(details = details) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(details = before) }
            }
        }
    }
}
