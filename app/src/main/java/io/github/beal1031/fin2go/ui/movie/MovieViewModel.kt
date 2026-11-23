package io.github.beal1031.fin2go.ui.movie

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beal1031.fin2go.data.media.MediaRepository
import io.github.beal1031.fin2go.data.media.MovieDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the detail page shows. */
data class MovieState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val movie: MovieDetails? = null,
)

/** Loads the details of the movie with [movieId]. */
class MovieViewModel(application: Application, private val movieId: String) : AndroidViewModel(application) {
    private val media = MediaRepository(application)

    private val _state = MutableStateFlow(MovieState())
    val state: StateFlow<MovieState> = _state.asStateFlow()

    init {
        reload()
    }

    /** Loads the details again. On failure, the error with a retry button is shown. */
    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            try {
                val movie = media.movie(movieId)
                _state.update { it.copy(loading = false, movie = movie) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    /**
     * Marks the movie as watched, or as not watched when it already is. Watched also removes a started position.
     * The page changes at once; if the server call fails, it goes back to how it was.
     */
    fun toggleWatched() {
        val before = _state.value.movie ?: return
        val watched = !before.watched
        _state.update { it.copy(movie = before.copy(watched = watched, progress = null, remainingMinutes = null)) }
        viewModelScope.launch {
            try {
                media.setWatched(before.id, watched)
                // What the server saved, e.g. a position it removed.
                val movie = media.movie(movieId)
                _state.update { it.copy(movie = movie) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(movie = before) }
            }
        }
    }
}
