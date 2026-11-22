package io.github.beal1031.fin2go.ui.login

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.JellyfinClient
import io.github.beal1031.fin2go.data.account.Account
import io.github.beal1031.fin2go.data.account.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import kotlin.time.Duration.Companion.seconds

/** Everything the login screen shows. */
data class LoginState(
    val address: String = "",
    val serverId: String? = null,
    val serverUrl: String? = null,
    val serverName: String? = null,
    val quickConnectEnabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    /** Set while Quick Connect waits for the code to be approved on another device. */
    val quickConnectCode: String? = null,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
)

/**
 * The logic behind the login screen. It survives screen rotation;
 * the UI only reads [state] and calls the functions below.
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {
    private val jellyfin = JellyfinClient.jellyfin(application)
    private val api = JellyfinClient.api(application)
    private val accountStore = AccountStore(application)

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private var quickConnectJob: Job? = null

    fun onAddressChange(value: String) = _state.update { it.copy(address = value, error = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    /** Part 1: find the server. Tries the input with http/https and the usual ports. */
    fun connect() = runInBackground {
        val candidates = jellyfin.discovery.getAddressCandidates(_state.value.address.trim())

        for (url in candidates) {
            try {
                api.update(baseUrl = url, accessToken = null)
                val info = withTimeout(5.seconds) { api.systemApi.getPublicSystemInfo().content }
                val quickConnect = runCatching { api.authenticationApi.getQuickConnectEnabled().content }
                    .getOrDefault(false)
                _state.update {
                    it.copy(
                        serverId = info.id ?: url,
                        serverUrl = url,
                        serverName = info.serverName ?: url,
                        quickConnectEnabled = quickConnect,
                    )
                }
                return@runInBackground
            } catch (e: Exception) {
                // This address did not answer, try the next one.
            }
        }
        _state.update { it.copy(error = R.string.login_server_not_found) }
    }

    /**
     * Part 2a: sign in with username and password and save the account.
     * Once it is saved, the app switches away from the login.
     */
    fun login() = runInBackground {
        val current = _state.value
        try {
            val result = api.authenticationApi
                .authenticateUserByName(username = current.username.trim(), password = current.password)
                .content
            saveAccount(result)
            _state.update { it.copy(password = "") }
        } catch (e: Exception) {
            _state.update { it.copy(error = R.string.login_sign_in_failed) }
        }
    }

    /**
     * Part 2b: sign in with Quick Connect. Shows a code, asks the server every
     * 3 seconds whether it was approved, then signs in and saves the account.
     */
    fun startQuickConnect() {
        quickConnectJob?.cancel()
        quickConnectJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(error = null) }
            try {
                var quickConnect = api.authenticationApi.initiateQuickConnect().content
                _state.update { it.copy(quickConnectCode = quickConnect.code) }

                while (!quickConnect.authenticated) {
                    delay(3.seconds)
                    quickConnect = api.authenticationApi.getQuickConnectState(secret = quickConnect.secret).content
                }

                val result = api.authenticationApi.authenticateWithQuickConnect(secret = quickConnect.secret).content
                saveAccount(result)
            } catch (e: CancellationException) {
                // Cancelled by the user: must not be treated as an error.
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(quickConnectCode = null, error = R.string.login_quick_connect_failed)
                }
            }
        }
    }

    fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        quickConnectJob = null
        _state.update { it.copy(quickConnectCode = null) }
    }

    fun changeServer() {
        cancelQuickConnect()
        _state.update {
            it.copy(serverId = null, serverUrl = null, serverName = null, quickConnectEnabled = false, error = null)
        }
    }

    private suspend fun saveAccount(result: AuthenticationResult) {
        val current = _state.value
        val user = checkNotNull(result.user)
        val serverUrl = checkNotNull(current.serverUrl)
        accountStore.save(
            Account(
                serverId = current.serverId ?: serverUrl,
                serverUrl = serverUrl,
                serverName = current.serverName.orEmpty(),
                userId = user.id.toString(),
                userName = user.name.orEmpty(),
                accessToken = checkNotNull(result.accessToken),
            )
        )
    }

    /** Runs [block] off the main thread and shows the spinner meanwhile. */
    private fun runInBackground(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            withContext(Dispatchers.IO) { block() }
            _state.update { it.copy(busy = false) }
        }
    }
}
