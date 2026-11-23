package io.github.beal1031.fin2go.ui.library

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.media.MediaItem
import io.github.beal1031.fin2go.data.media.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

/** Which list is shown, with what the server calls it and the texts for it. */
enum class LibraryType(val itemKind: BaseItemKind, @StringRes val title: Int, @StringRes val empty: Int) {
    SERIES(BaseItemKind.SERIES, R.string.series, R.string.library_empty_series),
    MOVIES(BaseItemKind.MOVIE, R.string.movies, R.string.library_empty_movies),
}

/** Everything the list shows. */
data class LibraryState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val items: List<MediaItem> = emptyList(),
)

/** Loads all series or all movies, depending on [type]. */
class LibraryViewModel(application: Application, private val type: LibraryType) : AndroidViewModel(application) {
    private val media = MediaRepository(application)

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        reload()
    }


    private var shownBefore = false

    /**
     * Called each time the screen is shown. The first time the data is already loading (see init);
     * later the screen loads again quietly, so watched marks and progress are up to date.
     */
    fun onShown() {
        if (shownBefore) refresh()
        shownBefore = true
    }

    /** Loads the whole list again. On failure, the error with a retry button is shown. */
    fun reload() = load(quiet = false)

    /** Loads again without the loading circle. If it fails, the list already shown stays. */
    private fun refresh() = load(quiet = true)

    private fun load(quiet: Boolean) {
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = false) }
            try {
                val items = media.all(type.itemKind)
                _state.update { it.copy(loading = false, error = false, items = items) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!quiet) _state.update { it.copy(loading = false, error = true) }
            }
        }
    }
}
