package io.github.beal1031.fin2go.data.download

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.workDataOf
import io.github.beal1031.fin2go.data.JellyfinClient
import io.github.beal1031.fin2go.data.account.AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration

private val Context.downloadDataStore by preferencesDataStore(name = "downloads")

/**
 * Starts, cancels and deletes downloads, and tells how far each one is.
 * The finished downloads are saved as JSON; the running ones are jobs of WorkManager (see [DownloadWorker]).
 */
class DownloadRepository(context: Context) {
    private val context = context.applicationContext
    private val dataStore = this.context.downloadDataStore
    private val workManager = WorkManager.getInstance(this.context)

    /** All finished downloads, by item id. */
    val downloads: Flow<Map<String, Download>> = dataStore.data.map(::read)

    /** How far the download of [itemId] is, updated while it runs. */
    fun state(itemId: String): Flow<DownloadState> =
        states(listOf(itemId)).map { it.getValue(itemId) }.distinctUntilChanged()

    /** How far the download of each of [itemIds] is, updated while they run. */
    fun states(itemIds: List<String>): Flow<Map<String, DownloadState>> {
        if (itemIds.isEmpty()) return flowOf(emptyMap())
        return combine(
            downloads.map { it.keys }.distinctUntilChanged(),
            workManager.getWorkInfosFlow(WorkQuery.fromUniqueWorkNames(itemIds.map(::workName))),
        ) { downloaded, works ->
            itemIds.associateWith { id ->
                // A running or waiting job counts before an old, finished one.
                val work = works.filter { "$ITEM_TAG$id" in it.tags }.minByOrNull { if (it.state.isFinished) 1 else 0 }
                when {
                    id in downloaded -> DownloadState.Downloaded
                    work?.state == WorkInfo.State.RUNNING -> DownloadState.Downloading(
                        work.progress.getFloat(DownloadWorker.PROGRESS, -1f).takeIf { it >= 0f },
                    )
                    work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED ->
                        DownloadState.Waiting
                    // Not started, cancelled or failed.
                    else -> DownloadState.NotDownloaded
                }
            }
        }.distinctUntilChanged()
    }

    /**
     * Downloads the movie or episode in the background, only over Wi-Fi.
     * Tapping again while it runs changes nothing.
     */
    fun start(itemId: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.ITEM_ID to itemId))
            // So the job can be found again by its item, see [states].
            .addTag("$ITEM_TAG$itemId")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            // When the connection drops, it tries again later and continues where it stopped.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        workManager.enqueueUniqueWork(workName(itemId), ExistingWorkPolicy.KEEP, request)
    }

    /** Stops a running or waiting download and removes what was loaded so far. */
    suspend fun cancel(itemId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(itemId))
        folder(context, itemId).deleteRecursively()
    }

    /** Removes a finished download from the phone, and the info of its series when it was the last episode. */
    suspend fun delete(itemId: String) = withContext(Dispatchers.IO) {
        var seriesId: String? = null
        var seriesStillNeeded = false
        dataStore.edit { prefs ->
            val all = read(prefs)
            seriesId = all[itemId]?.seriesId
            val left = all - itemId
            seriesStillNeeded = left.values.any { it.seriesId == seriesId }
            prefs[downloadsKey] = Json.encodeToString(left)
        }
        folder(context, itemId).deleteRecursively()
        seriesId?.takeIf { !seriesStillNeeded }?.let { OfflineInfo.seriesFolder(context, it).deleteRecursively() }
    }

    /**
     * Downloads from before the series info was saved too: fetches it now. Called when the server is reachable;
     * if it fails, it is simply tried again next time.
     */
    suspend fun fillMissingSeries() = withContext(Dispatchers.IO) {
        // Right at the start, the API client may not know the server yet.
        val account = AccountStore(context).account.first() ?: return@withContext
        JellyfinClient.api(context).update(baseUrl = account.serverUrl, accessToken = account.accessToken)
        downloads.first().values.mapNotNull { it.seriesId }.distinct().forEach { seriesId ->
            runCatching { OfflineInfo.saveSeriesIfMissing(context, seriesId) }
        }
    }

    /** The downloaded video file of [itemId], or null when it is not (completely) downloaded. */
    suspend fun localFile(itemId: String): File? {
        val download = downloads.first()[itemId] ?: return null
        return File(folder(context, itemId), download.fileName).takeIf { it.exists() }
    }

    /** Called by the worker when the file is complete. */
    internal suspend fun save(download: Download) {
        dataStore.edit { prefs -> prefs[downloadsKey] = Json.encodeToString(read(prefs) + (download.itemId to download)) }
    }

    private fun read(prefs: Preferences): Map<String, Download> =
        prefs[downloadsKey]?.let { Json.decodeFromString<Map<String, Download>>(it) } ?: emptyMap()

    companion object {
        private val downloadsKey = stringPreferencesKey("downloads")

        private fun workName(itemId: String) = "download-$itemId"
        private const val ITEM_TAG = "download-item:"

        /**
         * The folder of one download, in the app's own storage: other apps cannot see it,
         * and it is removed together with the app.
         */
        fun folder(context: Context, itemId: String): File =
            File(root(context), itemId)

        /** The folder of all downloads. */
        fun root(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
    }
}
