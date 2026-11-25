package io.github.beal1031.fin2go

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beal1031.fin2go.data.JellyfinClient
import io.github.beal1031.fin2go.data.ServerStatus
import io.github.beal1031.fin2go.data.account.Account
import io.github.beal1031.fin2go.data.account.AccountStore
import io.github.beal1031.fin2go.data.download.DownloadRepository
import io.github.beal1031.fin2go.data.media.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.sessionApi

/** What the app shows at the top level. */
sealed interface AppState {
    /** The saved account is still being read. */
    data object Loading : AppState

    data object SignedOut : AppState

    /** [profileImageUrl] is the address of the profile picture; the server answers with an error when there is none. */
    data class SignedIn(val account: Account, val profileImageUrl: String) : AppState
}

/**
 * Decides between login and the rest of the app, based on the saved account,
 * and points the API client at the account's server and token.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val api = JellyfinClient.api(application)
    private val accountStore = AccountStore(application)
    private val media = MediaRepository(application)

    /** False while the server cannot be reached: then the app is offline. */
    val serverReachable: StateFlow<Boolean> = ServerStatus.reachable(application)

    init {
        // At each start: fetch series info that older downloads are still missing. Offline it fails quietly
        // and is tried again at the next start.
        viewModelScope.launch { DownloadRepository(application).fillMissingSeries() }
    }

    val state: StateFlow<AppState> = accountStore.account
        .onEach { account ->
            api.update(baseUrl = account?.serverUrl, accessToken = account?.accessToken)
            ServerStatus.recheck()
        }
        .map { account ->
            if (account == null) AppState.SignedOut
            else AppState.SignedIn(account, profileImageUrl = media.profileImageUrl(account.userId))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState.Loading)

    /** Ends the session on the server (the token becomes invalid) and deletes the saved account. */
    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) {
            // If the server is unreachable, sign out locally anyway.
            runCatching { api.sessionApi.reportSessionEnded() }
            accountStore.clear()
        }
    }
}
