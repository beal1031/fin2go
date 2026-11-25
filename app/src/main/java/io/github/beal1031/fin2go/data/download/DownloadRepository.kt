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
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    fun state(itemId: String): Flow<DownloadState> = combine(
        downloads.map { itemId in it }.distinctUntilChanged(),
        workManager.getWorkInfosForUniqueWorkFlow(workName(itemId)).map { it.firstOrNull() },
    ) { downloaded, work ->
        when {
            downloaded -> DownloadState.Downloaded
            work?.state == WorkInfo.State.RUNNING ->
                DownloadState.Downloading(work.progress.getFloat(DownloadWorker.PROGRESS, -1f).takeIf { it >= 0f })
            work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED -> DownloadState.Waiting
            // Not started, cancelled or failed.
            else -> DownloadState.NotDownloaded
        }
    }.distinctUntilChanged()

    /**
     * Downloads the movie or episode in the background, only over Wi-Fi.
     * Tapping again while it runs changes nothing.
     */
    fun start(itemId: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.ITEM_ID to itemId))
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

    /** Removes a finished download from the phone. */
    suspend fun delete(itemId: String) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> prefs[downloadsKey] = Json.encodeToString(read(prefs) - itemId) }
        folder(context, itemId).deleteRecursively()
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

        /**
         * The folder of one download, in the app's own storage: other apps cannot see it,
         * and it is removed together with the app.
         */
        fun folder(context: Context, itemId: String): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/$itemId")
    }
}
