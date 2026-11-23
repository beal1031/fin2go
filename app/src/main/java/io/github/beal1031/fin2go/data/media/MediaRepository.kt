package io.github.beal1031.fin2go.data.media

import android.content.Context
import io.github.beal1031.fin2go.data.JellyfinClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Reads movies and series of the signed-in user from the server. All calls run off the main thread. */
class MediaRepository(context: Context) {
    private val api = JellyfinClient.api(context)

    /**
     * One row for continuing: first the started movies and episodes (most recently watched first),
     * then the next unwatched episode of series watched in the last 60 days.
     * Only one entry per series. Shown with a wide image.
     */
    suspend fun continueWatching(limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        val (started, nextUp) = coroutineScope {
            val started = async {
                api.libraryApi.getResumeItems(
                    limit = limit,
                    mediaTypes = listOf(MediaType.VIDEO),
                    enableUserData = true,
                ).content.items
            }
            val nextUp = async {
                api.showApi.getNextUp(
                    limit = limit,
                    enableUserData = true,
                    // Series not watched for longer than this are left out.
                    nextUpDateCutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(60),
                    // Started episodes already come from the first list.
                    enableResumable = false,
                ).content.items
            }
            started.await() to nextUp.await()
        }
        val seriesWithStartedEpisode = started.mapNotNull { it.seriesId }.toSet()
        (started + nextUp.filter { it.seriesId !in seriesWithStartedEpisode })
            .take(limit)
            .map { it.toMediaItem(wideImageUrl(it)) }
    }

    /**
     * Newest series or movies on the server, shown with a poster.
     * Fully watched ones come last; a series is fully watched when all its episodes are.
     */
    suspend fun latest(type: BaseItemKind, limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        api.libraryApi.getLatestMedia(
            includeItemTypes = listOf(type),
            limit = limit,
            enableUserData = true,
        ).content
            .map { it.toMediaItem(posterUrl(it)) }
            // sortedBy keeps the order within each group, so both stay newest first.
            .sortedBy { it.watched }
    }

    /**
     * All series or all movies of the user's libraries, sorted by name (A–Z), shown with a poster.
     * Everything is loaded at once; the pictures are only loaded when a tile becomes visible.
     */
    suspend fun all(type: BaseItemKind): List<MediaItem> = withContext(Dispatchers.IO) {
        api.libraryApi.getItems(
            includeItemTypes = listOf(type),
            // Also look inside folders, otherwise only the top level of a library is returned.
            recursive = true,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            enableUserData = true,
            // The total count is not needed and costs the server extra work.
            enableTotalRecordCount = false,
        ).content.items
            .map { it.toMediaItem(posterUrl(it)) }
    }

    /** The profile picture of the user. The server answers with an error when there is none. */
    fun profileImageUrl(userId: String): String =
        api.imageApi.getUserImageUrl(userId = UUID.fromString(userId))

    private fun BaseItemDto.toMediaItem(imageUrl: String?): MediaItem {
        val isEpisode = type == BaseItemKind.EPISODE
        // For a series the server does not always set "played", even when all episodes are watched.
        // Then no episode left unwatched, or 100 percent, also counts as watched.
        // But watched before and started again counts as started (bar), not as watched (check mark).
        val startedAgain = (userData?.playbackPositionTicks ?: 0L) > 0L
        val watched = !startedAgain && userData?.let { data ->
            data.played ||
                (type == BaseItemKind.SERIES && data.unplayedItemCount == 0) ||
                (data.playedPercentage ?: 0.0) >= 100.0
        } ?: false
        return MediaItem(
            id = id.toString(),
            title = (if (isEpisode) seriesName else null) ?: name.orEmpty(),
            seasonNumber = if (isEpisode) parentIndexNumber else null,
            episodeNumber = if (isEpisode) indexNumber else null,
            imageUrl = imageUrl,
            // Only started movies and episodes get a bar. A series never does, a watched item shows a check mark.
            progress = if (watched || type == BaseItemKind.SERIES) null
            else userData?.playedPercentage?.takeIf { it > 0 }?.let { (it / 100).toFloat() },
            watched = watched,
        )
    }

    /** Portrait poster. Episodes use the poster of their series. */
    private fun posterUrl(item: BaseItemDto): String? {
        item.imageTags?.get(ImageType.PRIMARY)?.let { return imageUrl(item.id, ImageType.PRIMARY, it, 400) }
        val seriesId = item.seriesId ?: return null
        val seriesTag = item.seriesPrimaryImageTag ?: return null
        return imageUrl(seriesId, ImageType.PRIMARY, seriesTag, 400)
    }

    /** Landscape image: the episode's own picture, else a backdrop, else the poster. */
    private fun wideImageUrl(item: BaseItemDto): String? {
        if (item.type == BaseItemKind.EPISODE) {
            item.imageTags?.get(ImageType.PRIMARY)?.let { return imageUrl(item.id, ImageType.PRIMARY, it, 600) }
        }
        item.backdropImageTags?.firstOrNull()?.let { return imageUrl(item.id, ImageType.BACKDROP, it, 600) }
        val parentId = item.parentBackdropItemId
        val parentTag = item.parentBackdropImageTags?.firstOrNull()
        if (parentId != null && parentTag != null) return imageUrl(parentId, ImageType.BACKDROP, parentTag, 600)
        return posterUrl(item)
    }

    private fun imageUrl(itemId: UUID, type: ImageType, tag: String, maxWidth: Int): String =
        api.imageApi.getItemImageUrl(itemId = itemId, imageType = type, tag = tag, maxWidth = maxWidth)
}
