package io.github.beal1031.fin2go.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beal1031.fin2go.data.account.AccountStore
import io.github.beal1031.fin2go.data.media.MediaItem
import io.github.beal1031.fin2go.data.media.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemKind

/** Everything the home screen shows. */
data class HomeState(
    val userName: String = "",
    val profileImageUrl: String? = null,
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
    private val accountStore = AccountStore(application)
    private val media = MediaRepository(application)

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            accountStore.account.first()?.let { account ->
                _state.update {
                    it.copy(userName = account.userName, profileImageUrl = media.profileImageUrl(account.userId))
                }
            }
        }
        reload()
    }

    /** Loads all rows at the same time. If one fails, the error with a retry button is shown. */
    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            try {
                // coroutineScope: if one call fails, the others are cancelled and the error lands in catch.
                coroutineScope {
                    val continueWatching = async { media.continueWatching() }
                    val latestSeries = async { media.latest(BaseItemKind.SERIES) }
                    val latestMovies = async { media.latest(BaseItemKind.MOVIE) }
                    _state.update {
                        it.copy(
                            loading = false,
                            continueWatching = continueWatching.await(),
                            latestSeries = latestSeries.await(),
                            latestMovies = latestMovies.await(),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }
}
