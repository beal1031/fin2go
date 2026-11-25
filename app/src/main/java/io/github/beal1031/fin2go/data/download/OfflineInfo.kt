package io.github.beal1031.fin2go.data.download

import android.content.Context
import io.github.beal1031.fin2go.data.JellyfinClient
import io.github.beal1031.fin2go.data.playback.PlaybackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Saves what the pages show about a movie, episode or series, so they can be shown without the server:
 * `item.json` (everything the server knows about it) and its pictures, `primary.jpg` and `backdrop.jpg`.
 */
internal object OfflineInfo {
    private val json = Json { encodeDefaults = false }

    /** The folder with the info of a series, shared by all its downloaded episodes. */
    fun seriesFolder(context: Context, seriesId: String): File =
        File(DownloadRepository.root(context), "series/$seriesId")

    /** Writes [item] and its pictures into [folder]. Missing pictures are skipped. */
    suspend fun save(context: Context, item: BaseItemDto, folder: File) = withContext(Dispatchers.IO) {
        folder.mkdirs()
        File(folder, "item.json").writeText(json.encodeToString(BaseItemDto.serializer(), item))
        val api = JellyfinClient.api(context)
        val primary = item.imageTags?.get(ImageType.PRIMARY)?.let {
            api.imageApi.getItemImageUrl(itemId = item.id, imageType = ImageType.PRIMARY, tag = it, maxWidth = 600)
        }
        // An episode has no backdrop of its own; it uses the one of its series.
        val backdropTag = item.backdropImageTags?.firstOrNull() ?: item.parentBackdropImageTags?.firstOrNull()
        val backdropItem = if (item.backdropImageTags.isNullOrEmpty()) item.parentBackdropItemId else item.id
        val backdrop = if (backdropTag != null && backdropItem != null) {
            api.imageApi.getItemImageUrl(itemId = backdropItem, imageType = ImageType.BACKDROP, tag = backdropTag, maxWidth = 1280)
        } else {
            null
        }
        val authorization = PlaybackRepository(context).authorizationHeader()
        listOf("primary.jpg" to primary, "backdrop.jpg" to backdrop).forEach { (name, url) ->
            if (url != null) runCatching { loadPicture(url, File(folder, name), authorization) }
        }
    }

    /** Saves the info of the series [seriesId], unless it is already on the phone. */
    suspend fun saveSeriesIfMissing(context: Context, seriesId: String) {
        val folder = seriesFolder(context, seriesId)
        if (File(folder, "item.json").exists()) return
        val series = JellyfinClient.api(context).libraryApi.getItem(itemId = UUID.fromString(seriesId)).content
        save(context, series, folder)
    }

    /** Loads a picture into [file]; first into a temporary file, so a broken connection leaves no half picture. */
    private fun loadPicture(url: String, file: File, authorization: String) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", authorization)
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("Server answered ${connection.responseCode}")
            val temporary = File(file.parentFile, "${file.name}.part")
            connection.inputStream.use { input -> temporary.outputStream().use { input.copyTo(it) } }
            if (!temporary.renameTo(file)) throw IOException("Could not rename $temporary")
        } finally {
            connection.disconnect()
        }
    }
}
