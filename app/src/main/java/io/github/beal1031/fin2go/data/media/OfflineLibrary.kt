package io.github.beal1031.fin2go.data.media

import android.content.Context
import androidx.core.net.toUri
import io.github.beal1031.fin2go.data.JellyfinClient
import io.github.beal1031.fin2go.data.download.DownloadRepository
import io.github.beal1031.fin2go.data.download.OfflineInfo
import io.github.beal1031.fin2go.data.playback.PendingProgress
import io.github.beal1031.fin2go.data.playback.ProgressSync
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * The library as far as it is on the phone: the downloaded movies and episodes and the series they belong to,
 * read from the `item.json` files of the downloads. Used while the server cannot be reached.
 * The progress is the newest the phone knows, also when it was watched without internet.
 */
internal class OfflineLibrary(context: Context) {
    private val context = context.applicationContext
    private val downloads = DownloadRepository(this.context)
    private val progressSync = ProgressSync(this.context)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** All downloaded movies and episodes. */
    suspend fun items(): List<BaseItemDto> {
        val pending = progressSync.pending()
        return downloads.downloads.first().keys.mapNotNull { id ->
            read(DownloadRepository.folder(context, id))?.withLocalProgress(pending[id])
        }
    }

    /** All series with at least one downloaded episode. */
    suspend fun series(): List<BaseItemDto> =
        downloads.downloads.first().values.mapNotNull { it.seriesId }.distinct()
            .mapNotNull { read(OfflineInfo.seriesFolder(context, it)) }

    /** One downloaded movie or episode, or a series of a downloaded episode; null when it is not on the phone. */
    suspend fun item(id: String): BaseItemDto? {
        val download = read(DownloadRepository.folder(context, id))
        if (download != null) return download.withLocalProgress(progressSync.pending()[id])
        return read(OfflineInfo.seriesFolder(context, id))
    }

    /**
     * Puts the watched state and position of [fromServer] into the saved info of those that are on the phone,
     * so the phone knows the newest state when it goes offline. Everything else of the saved info stays.
     */
    suspend fun remember(fromServer: List<BaseItemDto>) {
        val downloaded = downloads.downloads.first()
        val series = downloaded.values.mapNotNull { it.seriesId }.toSet()
        fromServer.forEach { item ->
            val id = item.id.toString()
            val folder = when (id) {
                in downloaded -> DownloadRepository.folder(context, id)
                in series -> OfflineInfo.seriesFolder(context, id)
                else -> return@forEach
            }
            val saved = read(folder) ?: return@forEach
            val userData = item.userData ?: return@forEach
            if (saved.userData != userData) write(folder, saved.copy(userData = userData))
        }
    }

    /** Gets the newest watched state of everything on the phone from the server, e.g. at the start of the app. */
    suspend fun refreshFromServer() {
        val downloaded = downloads.downloads.first()
        val ids = (downloaded.keys + downloaded.values.mapNotNull { it.seriesId }).distinct()
        if (ids.isEmpty()) return
        val items = JellyfinClient.api(context).libraryApi.getItems(
            ids = ids.map(UUID::fromString),
            enableUserData = true,
            enableTotalRecordCount = false,
        ).content.items
        remember(items)
    }

    /** The saved picture of [itemId] (poster or backdrop) as an address for the image loader, or null. */
    fun image(itemId: UUID, type: ImageType): String? {
        val name = if (type == ImageType.BACKDROP) "backdrop.jpg" else "primary.jpg"
        return listOf(DownloadRepository.folder(context, itemId.toString()), OfflineInfo.seriesFolder(context, itemId.toString()))
            .map { File(it, name) }
            .firstOrNull { it.exists() }
            ?.toUri()?.toString()
    }

    private fun read(folder: File): BaseItemDto? = runCatching {
        json.decodeFromString(BaseItemDto.serializer(), File(folder, "item.json").readText())
    }.getOrNull()

    private fun write(folder: File, item: BaseItemDto) {
        File(folder, "item.json").writeText(json.encodeToString(BaseItemDto.serializer(), item))
    }

    /**
     * Puts the position kept on the phone into the item, as the server would after the report:
     * from 90 % on watched, below 5 % not started.
     */
    private fun BaseItemDto.withLocalProgress(pending: PendingProgress?): BaseItemDto {
        val data = userData ?: return this
        if (pending == null) return this
        val positionTicks = pending.positionMs * 10_000L
        val share = runTimeTicks?.takeIf { it > 0 }?.let { positionTicks.toDouble() / it }
        val watched = share != null && share >= 0.9
        val notStarted = share != null && share < 0.05
        return copy(
            userData = data.copy(
                playbackPositionTicks = if (watched || notStarted) 0L else positionTicks,
                playedPercentage = if (watched || notStarted || share == null) null else share * 100,
                played = watched || data.played,
                lastPlayedDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(pending.savedAtMs), ZoneId.systemDefault()),
            ),
        )
    }
}
