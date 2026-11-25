package io.github.beal1031.fin2go.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beal1031.fin2go.data.download.DownloadRepository
import io.github.beal1031.fin2go.data.media.MediaItem
import io.github.beal1031.fin2go.data.media.MediaRepository
import io.github.beal1031.fin2go.data.media.withDownloads
import io.github.beal1031.fin2go.data.playback.ProgressSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

/** Everything the home screen shows. */
data class HomeState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val continueWatching: List<MediaItem> = emptyList(),
    val latestSeries: List<MediaItem> = emptyList(),
    val latestMovies: List<MediaItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = continueWatching.isEmpty() && latestSeries.isEmpty() && latestMovies.isEmpty()
}

/** Loads the three rows of the home screen. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val media = MediaRepository(application)
    private val downloads = DownloadRepository(application)

    private val _state = MutableStateFlow(HomeState())
    /** The loaded rows, with the downloaded movies and episodes marked. */
    val state: StateFlow<HomeState> = combine(_state, downloads.downloads.map { it.keys }) { state, downloaded ->
        state.copy(
            continueWatching = state.continueWatching.withDownloads(downloaded),
            latestMovies = state.latestMovies.withDownloads(downloaded),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _state.value)

    init {
        reload()
        // Progress watched without internet reached the server later: show it.
        viewModelScope.launch { ProgressSync(getApplication()).sent.collect { refresh() } }
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

    /** Loads all rows at the same time. If one fails, the error with a retry button is shown. */
    fun reload() = load(quiet = false)

    /** Loads again without the loading circle. If it fails, the rows already shown stay. */
    private fun refresh() = load(quiet = true)

    private fun load(quiet: Boolean) {
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = false) }
            try {
                // coroutineScope: if one call fails, the others are cancelled and the error lands in catch.
                coroutineScope {
                    val continueWatching = async { media.continueWatching() }
                    val latestSeries = async { media.latest(BaseItemKind.SERIES) }
                    val latestMovies = async { media.latest(BaseItemKind.MOVIE) }
                    _state.update {
                        it.copy(
                            loading = false,
                            error = false,
                            continueWatching = continueWatching.await(),
                            latestSeries = latestSeries.await(),
                            latestMovies = latestMovies.await(),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!quiet) _state.update { it.copy(loading = false, error = true) }
            }
        }
    }
}
