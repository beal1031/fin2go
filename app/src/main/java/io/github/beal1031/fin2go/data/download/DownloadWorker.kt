package io.github.beal1031.fin2go.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.JellyfinClient
import io.github.beal1031.fin2go.data.account.AccountStore
import io.github.beal1031.fin2go.data.playback.PlaybackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Downloads one movie or episode in its original quality: first what the server knows about it
 * and its pictures, then the video file. While it runs, a notification shows the progress.
 * When the connection drops, it is tried again later and continues where it stopped.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val downloads = DownloadRepository(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val itemId = inputData.getString(ITEM_ID) ?: return@withContext Result.failure()
        // The app may have been closed: then the API client does not know the server yet.
        val account = AccountStore(applicationContext).account.first() ?: return@withContext Result.failure()
        val api = JellyfinClient.api(applicationContext)
        api.update(baseUrl = account.serverUrl, accessToken = account.accessToken)
        val authorization = PlaybackRepository(applicationContext).authorizationHeader()

        try {
            val item = api.libraryApi.getItem(itemId = UUID.fromString(itemId)).content
            val title = item.name.orEmpty()
            setForeground(foregroundInfo(title, progress = null))

            val folder = DownloadRepository.folder(applicationContext, itemId).apply { mkdirs() }
            File(folder, "item.json").writeText(json.encodeToString(BaseItemDto.serializer(), item))
            savePictures(item, folder, authorization)

            // e.g. "mkv"; some servers name several, e.g. "mov,mp4,m4a".
            val container = item.mediaSources?.firstOrNull()?.container?.substringBefore(',') ?: "mkv"
            val fileName = "video.$container"
            val partFile = File(folder, "$fileName.part")
            var lastShown = 0L
            downloadFile(api.libraryApi.getDownloadUrl(item.id), partFile, authorization) { progress ->
                // At most once a second, so the notification and the page are not updated all the time.
                val now = System.currentTimeMillis()
                if (now - lastShown >= 1_000L) {
                    lastShown = now
                    setProgress(workDataOf(PROGRESS to (progress ?: -1f)))
                    setForeground(foregroundInfo(title, progress))
                }
            }
            val videoFile = File(folder, fileName)
            if (!partFile.renameTo(videoFile)) throw IOException("Could not rename $partFile")

            downloads.save(
                Download(
                    itemId = itemId,
                    serverId = account.serverId,
                    userId = account.userId,
                    seriesId = item.seriesId?.toString(),
                    seasonId = item.seasonId?.toString(),
                    fileName = fileName,
                    sizeBytes = videoFile.length(),
                ),
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No connection, server gone for a moment, ...: try again later, but not forever.
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                DownloadRepository.folder(applicationContext, itemId).deleteRecursively()
                Result.failure()
            }
        }
    }

    /** The poster (or the episode's picture) and the wide backdrop. Missing pictures are skipped. */
    private suspend fun savePictures(item: BaseItemDto, folder: File, authorization: String) {
        val api = JellyfinClient.api(applicationContext)
        val primary = item.imageTags?.get(ImageType.PRIMARY)?.let {
            api.imageApi.getItemImageUrl(itemId = item.id, imageType = ImageType.PRIMARY, tag = it, maxWidth = 600)
        }
        val backdropTag = item.backdropImageTags?.firstOrNull() ?: item.parentBackdropImageTags?.firstOrNull()
        val backdropItem = if (item.backdropImageTags.isNullOrEmpty()) item.parentBackdropItemId else item.id
        val backdrop = if (backdropTag != null && backdropItem != null) {
            api.imageApi.getItemImageUrl(itemId = backdropItem, imageType = ImageType.BACKDROP, tag = backdropTag, maxWidth = 1280)
        } else {
            null
        }
        listOf("primary.jpg" to primary, "backdrop.jpg" to backdrop).forEach { (name, url) ->
            if (url != null) runCatching { downloadFile(url, File(folder, name), authorization, resume = false) {} }
        }
    }

    /**
     * Loads [url] into [file]. With [resume], a file that is already partly there is continued
     * instead of loaded again. [onProgress] gets the share loaded so far, from 0 to 1, or null when the size is unknown.
     */
    private suspend fun downloadFile(
        url: String,
        file: File,
        authorization: String,
        resume: Boolean = true,
        onProgress: suspend (Float?) -> Unit,
    ) {
        val already = if (resume && file.exists()) file.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", authorization)
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Server answered $code")
            // 206: the server sends only the rest. 200: it sends everything again.
            val continuing = code == HttpURLConnection.HTTP_PARTIAL
            val start = if (continuing) already else 0L
            val total = connection.contentLengthLong.takeIf { it > 0 }?.plus(start)
            var loaded = start
            connection.inputStream.use { input ->
                FileOutputStream(file, continuing).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // Stops here when the download is cancelled.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        loaded += read
                        onProgress(total?.let { loaded.toFloat() / it })
                    }
                }
            }
            if (total != null && loaded < total) throw IOException("Connection ended early")
        } finally {
            connection.disconnect()
        }
    }

    /** The notification that keeps the download running while the app is closed. */
    private fun foregroundInfo(title: String, progress: Float?): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.download_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(applicationContext.getString(R.string.download_notification_text))
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setOngoing(true)
            .setSilent(true)
            .build()
        // Every download has its own notification.
        val notificationId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val ITEM_ID = "itemId"
        const val PROGRESS = "progress"
        private const val MAX_ATTEMPTS = 10
        private const val CHANNEL_ID = "downloads"
        private val json = Json { encodeDefaults = false }
    }
}
