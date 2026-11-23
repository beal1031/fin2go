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
}
