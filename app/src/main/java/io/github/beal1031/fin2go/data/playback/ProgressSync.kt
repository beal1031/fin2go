package io.github.beal1031.fin2go.data.playback

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.beal1031.fin2go.data.JellyfinClient
import io.github.beal1031.fin2go.data.account.AccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

private val Context.pendingProgressDataStore by preferencesDataStore(name = "pending_progress")

/** A position the server did not get, e.g. because the phone had no internet while watching. */
@Serializable
data class PendingProgress(
    val itemId: String,
    val positionMs: Long,
    /** When it was watched, in milliseconds since 1970 (UTC). */
    val savedAtMs: Long,
    /** The last position the server did get from this playback, before the connection was gone. */
    val serverPositionMs: Long? = null,
    /** The playback on the server that was never ended, because the end could not be reported. */
    val mediaSourceId: String? = null,
    val playSessionId: String? = null,
)

/**
 * Keeps positions the server could not be told about and sends them as soon as there is internet again.
 * If the server got a newer position in the meantime (e.g. watched on the TV), the newer one wins.
 */
class ProgressSync(context: Context) {
    private val context = context.applicationContext
    private val dataStore = this.context.pendingProgressDataStore

    /** Gives a value each time kept positions were sent, so pages can load the new progress. */
    val sent: Flow<Unit> = dataStore.data.map { it[sentAtKey] }.distinctUntilChanged().drop(1).map { }

    /** Keeps [positionMs] of [itemId] (only the latest one per item) and sends it when there is internet. */
    suspend fun remember(
        itemId: String,
        positionMs: Long,
        serverPositionMs: Long?,
        mediaSourceId: String?,
        playSessionId: String?,
    ) {
        dataStore.edit { prefs ->
            val pending = read(prefs)
            val new = PendingProgress(
                itemId = itemId,
                positionMs = positionMs,
                savedAtMs = System.currentTimeMillis(),
                // Stays the same over all reports of the time without internet.
                serverPositionMs = pending[itemId]?.serverPositionMs ?: serverPositionMs,
                mediaSourceId = mediaSourceId,
                playSessionId = playSessionId,
            )
            prefs[pendingKey] = Json.encodeToString(pending + (itemId to new))
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ProgressSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
                .build(),
        )
    }

    /** The server got a newer position of [itemId] directly, so the kept one is no longer needed. */
    suspend fun forget(itemId: String) {
        dataStore.edit { prefs ->
            val pending = read(prefs)
            if (itemId in pending) prefs[pendingKey] = Json.encodeToString(pending - itemId)
        }
    }

    /** The positions not sent yet, by item id: the newest progress on this phone, e.g. while offline. */
    suspend fun pending(): Map<String, PendingProgress> = read(dataStore.data.first())

    /** Sends every kept position. Each one that reached the server, or was older than the server's, is removed. */
    internal suspend fun sendAll() {
        val account = AccountStore(context).account.first() ?: return
        // The app may have been closed: then the API client does not know the server yet.
        val api = JellyfinClient.api(context)
        api.update(baseUrl = account.serverUrl, accessToken = account.accessToken)

        val all = read(dataStore.data.first()).values
        all.forEach { pending ->
            val itemId = UUID.fromString(pending.itemId)
            // The SDK gives and takes times in the phone's time zone.
            val savedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(pending.savedAtMs), ZoneId.systemDefault())
            val server = api.userDataApi.getItemUserData(itemId = itemId).content
            // The server ends a playback by itself after some minutes without news and then saves the last
            // position it got. Then it has a newer date, but still our old position: ours wins anyway.
            val serverHasOnlyOurOldPosition = pending.serverPositionMs != null &&
                server.playbackPositionTicks / 10_000L == pending.serverPositionMs
            val serverIsNewer = server.lastPlayedDate?.isAfter(savedAt) == true
            if (!serverIsNewer || serverHasOnlyOurOldPosition) {
                val positionTicks = pending.positionMs * 10_000L
                // Ends the playback on the server, so it does not end it later by itself with the old position.
                runCatching {
                    api.sessionApi.reportPlaybackStopped(
                        PlaybackStopInfo(
                            itemId = itemId,
                            mediaSourceId = pending.mediaSourceId,
                            playSessionId = pending.playSessionId,
                            positionTicks = positionTicks,
                            failed = false,
                        ),
                    )
                }
                val runtimeTicks = api.libraryApi.getItem(itemId = itemId).content.runTimeTicks
                val share = runtimeTicks?.takeIf { it > 0 }?.let { positionTicks.toDouble() / it }
                // As the server does after watching: from 90 % on it counts as watched, below 5 % as not started.
                val watched = share != null && share >= 0.9
                api.userDataApi.updateItemUserData(
                    itemId = itemId,
                    data = UpdateUserItemDataDto(
                        playbackPositionTicks = if (watched || (share != null && share < 0.05)) 0L else positionTicks,
                        played = if (watched) true else null,
                        lastPlayedDate = savedAt,
                    ),
                )
            }
            dataStore.edit { prefs ->
                val current = read(prefs)
                // Only when no newer position came in while sending.
                if (current[pending.itemId] == pending) prefs[pendingKey] = Json.encodeToString(current - pending.itemId)
            }
        }
        if (all.isNotEmpty()) dataStore.edit { it[sentAtKey] = System.currentTimeMillis() }
    }

    private fun read(prefs: Preferences): Map<String, PendingProgress> =
        prefs[pendingKey]?.let { Json.decodeFromString<Map<String, PendingProgress>>(it) } ?: emptyMap()

    private companion object {
        val pendingKey = stringPreferencesKey("pending")
        val sentAtKey = longPreferencesKey("sentAt")
        const val WORK_NAME = "progress-sync"
    }
}

/** Runs [ProgressSync.sendAll] as soon as the phone has internet; tries again later if the server is not reachable. */
class ProgressSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        ProgressSync(applicationContext).sendAll()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.retry()
    }
}
