package io.github.beal1031.fin2go.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.extensions.systemApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the Jellyfin server can be reached right now. Without it, the app is offline: there is no internet,
 * or the server is off or only reachable at home. It is checked at the start, each time the phone's network
 * changes, and from time to time, because the server can come back without the network changing.
 * The checks only run while something on screen needs the answer.
 */
object ServerStatus {
    private var reachable: StateFlow<Boolean>? = null
    private val recheckRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** True when the server answers. Starts as true, so nothing flashes as offline while the first check runs. */
    @Synchronized
    fun reachable(context: Context): StateFlow<Boolean> =
        reachable ?: watch(context.applicationContext)
            .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.WhileSubscribed(5_000), true)
            .also { reachable = it }

    /** Checks again at once, e.g. after signing in, when the server address is known. */
    fun recheck() {
        recheckRequests.tryEmit(Unit)
    }

    private fun watch(context: Context) = callbackFlow {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                changes.trySend(Unit)
            }

            override fun onLost(network: Network) {
                changes.trySend(Unit)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                changes.trySend(Unit)
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        launch { recheckRequests.collect { changes.trySend(Unit) } }
        launch {
            while (true) {
                val answers = ping(context)
                send(answers)
                // Waits for the next network change, but checks again after a while in any case.
                withTimeoutOrNull(if (answers) ONLINE_RECHECK else OFFLINE_RECHECK) { changes.receive() }
            }
        }
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** Asks the server for a short answer. No answer within a few seconds counts as not reachable. */
    private suspend fun ping(context: Context): Boolean {
        val api = JellyfinClient.api(context)
        // Not signed in yet: there is no server to reach, so nothing is shown as offline.
        if (api.baseUrl == null) return true
        return withTimeoutOrNull(PING_TIMEOUT) { runCatching { api.systemApi.getPingSystem() }.isSuccess } ?: false
    }

    private val PING_TIMEOUT: Duration = 5.seconds
    private val ONLINE_RECHECK: Duration = 60.seconds
    private val OFFLINE_RECHECK: Duration = 15.seconds
}
