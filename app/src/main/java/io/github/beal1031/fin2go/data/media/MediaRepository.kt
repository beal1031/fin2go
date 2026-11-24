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
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
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
     * One row for continuing: the started movies and episodes, and the next unwatched episode of series
     * watched in the last 60 days. All together sorted by when they were last watched, newest first;
     * for a next episode that is when the series was last watched. Only one entry per series.
     * Fully watched movies and series are not in it. Shown with a wide image.
     */
    suspend fun continueWatching(limit: Int = 20): List<MediaItem> = withContext(Dispatchers.IO) {
        val (started, nextUp, recentlyPlayed) = coroutineScope {
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
            // The last watched episodes, to know when each series of the next episodes was last watched.
            val recentlyPlayed = async {
                api.libraryApi.getItems(
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.DATE_PLAYED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    limit = 200,
                    enableUserData = true,
                    enableTotalRecordCount = false,
                ).content.items
            }
            Triple(started.await(), nextUp.await(), recentlyPlayed.await())
        }
        // Two started episodes of one series: only the most recently watched one stays.
        // The server sends the most recently watched first, and distinctBy keeps the first of each series.
        val startedOnce = started.distinctBy { it.seriesId ?: it.id }
        val seriesWithStartedEpisode = startedOnce.mapNotNull { it.seriesId }.toSet()
        // For each series: when one of its episodes was last watched.
        val seriesLastPlayed = recentlyPlayed
            .mapNotNull { episode -> episode.seriesId?.let { id -> episode.userData?.lastPlayedDate?.let { id to it } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, dates) -> dates.max() }
        val lastPlayed = { item: BaseItemDto ->
            item.userData?.lastPlayedDate ?: item.seriesId?.let(seriesLastPlayed::get) ?: LocalDateTime.MIN
        }
        (startedOnce + nextUp.filter { it.seriesId !in seriesWithStartedEpisode })
            .sortedByDescending(lastPlayed)
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

    /** All details of one movie for the detail page. */
    suspend fun movie(id: String): MovieDetails = withContext(Dispatchers.IO) {
        val item = api.libraryApi.getItem(itemId = UUID.fromString(id)).content
        val position = item.userData?.playbackPositionTicks ?: 0L
        // As for the tiles: a saved position means started, even when it was watched before.
        // Null when not started (or the running time is unknown).
        val startedRuntime = item.runTimeTicks?.takeIf { it > 0L && position > 0L }
        MovieDetails(
            id = item.id.toString(),
            title = item.name.orEmpty(),
            year = item.productionYear,
            runtimeMinutes = item.runTimeTicks?.let(::minutes),
            ageRating = item.officialRating?.takeIf { it.isNotBlank() },
            genres = item.genres.orEmpty(),
            overview = item.overview?.takeIf { it.isNotBlank() },
            backdropUrl = item.backdropImageTags?.firstOrNull()?.let { imageUrl(item.id, ImageType.BACKDROP, it, 1280) },
            posterUrl = posterUrl(item),
            progress = startedRuntime?.let { (position.toFloat() / it).coerceIn(0f, 1f) },
            remainingMinutes = startedRuntime?.let { minutes(it - position).coerceAtLeast(1) },
            watched = startedRuntime == null && item.userData?.played == true,
        )
    }

    /**
     * All details of one series with all its episodes, for the detail page.
     * Episodes come in watching order: season 1, 2, … and the specials (season 0) last.
     */
    suspend fun series(id: String): SeriesDetails = withContext(Dispatchers.IO) {
        val seriesId = UUID.fromString(id)
        val (item, episodeItems) = coroutineScope {
            val item = async { api.libraryApi.getItem(itemId = seriesId).content }
            val episodes = async {
                api.showApi.getEpisodes(
                    seriesId = seriesId,
                    // The description of an episode is only sent when asked for.
                    fields = listOf(ItemFields.OVERVIEW),
                    // Episodes the server only knows from the internet, without a file, are left out.
                    isMissing = false,
                    enableUserData = true,
                ).content.items
            }
            item.await() to episodes.await()
        }
        val episodes = episodeItems
            // Without a season number, an episode counts as season 1.
            .groupBy { it.parentIndexNumber ?: 1 }
            // Specials (0) last, the other seasons by number.
            .toSortedMap(compareBy<Int> { it == 0 }.thenBy { it })
            .flatMap { (season, items) ->
                // Without an episode number, the place in the season is used.
                items.mapIndexed { index, episode -> episode.toEpisode(season, episode.indexNumber ?: (index + 1)) }
            }
        val firstYear = item.productionYear
        val lastYear = item.endDate?.year
        // e.g. "2010–2015", "2010–" while it still runs, or "2010" for one year.
        val years = when {
            firstYear == null -> null
            item.status == "Continuing" -> "$firstYear–"
            lastYear != null && lastYear != firstYear -> "$firstYear–$lastYear"
            else -> firstYear.toString()
        }
        SeriesDetails(
            id = item.id.toString(),
            title = item.name.orEmpty(),
            years = years,
            ageRating = item.officialRating?.takeIf { it.isNotBlank() },
            genres = item.genres.orEmpty(),
            overview = item.overview?.takeIf { it.isNotBlank() },
            backdropUrl = item.backdropImageTags?.firstOrNull()?.let { imageUrl(item.id, ImageType.BACKDROP, it, 1280) },
            posterUrl = posterUrl(item),
            episodes = episodes.map { it.details },
            currentEpisodeId = currentEpisode(episodes)?.id,
        )
    }

    /** One episode with the name and pictures of its series, for the episode page. */
    suspend fun episode(id: String): EpisodePageDetails = withContext(Dispatchers.IO) {
        val item = api.libraryApi.getItem(itemId = UUID.fromString(id)).content
        // As on the series page: without a season number it counts as season 1.
        val episode = item.toEpisode(item.parentIndexNumber ?: 1, item.indexNumber ?: 1).details
        val ownImage = item.imageTags?.get(ImageType.PRIMARY)?.let { imageUrl(item.id, ImageType.PRIMARY, it, 1280) }
        val parentId = item.parentBackdropItemId
        val parentTag = item.parentBackdropImageTags?.firstOrNull()
        val seriesBackdrop = if (parentId != null && parentTag != null) {
            imageUrl(parentId, ImageType.BACKDROP, parentTag, 1280)
        } else {
            null
        }
        val seriesId = item.seriesId
        val seriesPosterTag = item.seriesPrimaryImageTag
        EpisodePageDetails(
            episode = episode,
            seriesTitle = item.seriesName?.takeIf { it.isNotBlank() },
            backdropUrl = ownImage ?: seriesBackdrop,
            posterUrl = if (seriesId != null && seriesPosterTag != null) {
                imageUrl(seriesId, ImageType.PRIMARY, seriesPosterTag, 400)
            } else {
                null
            },
        )
    }

    /** An episode and when it was last watched; the time is only needed to find the current episode. */
    private class Episode(val details: EpisodeDetails, val lastPlayed: LocalDateTime?)

    private fun BaseItemDto.toEpisode(season: Int, number: Int): Episode {
        val position = userData?.playbackPositionTicks ?: 0L
        // As for movies: a saved position means started, even when it was watched before.
        val startedRuntime = runTimeTicks?.takeIf { it > 0L && position > 0L }
        val details = EpisodeDetails(
            id = id.toString(),
            seasonNumber = season,
            episodeNumber = number,
            title = name.orEmpty(),
            runtimeMinutes = runTimeTicks?.let(::minutes),
            overview = overview?.takeIf { it.isNotBlank() },
            imageUrl = wideImageUrl(this),
            progress = startedRuntime?.let { (position.toFloat() / it).coerceIn(0f, 1f) },
            remainingMinutes = startedRuntime?.let { minutes(it - position).coerceAtLeast(1) },
            watched = startedRuntime == null && userData?.played == true,
        )
        return Episode(details, userData?.lastPlayedDate)
    }

    /**
     * The episode the user is at: the last watched one if it is only started, else the one after it.
     * Nothing watched yet: the first episode. The very last episode watched to the end: that one stays.
     */
    private fun currentEpisode(episodes: List<Episode>): EpisodeDetails? {
        val lastIndex = episodes.indices
            .filter { episodes[it].lastPlayed != null }
            .maxByOrNull { episodes[it].lastPlayed!! }
            ?: return episodes.firstOrNull()?.details
        val last = episodes[lastIndex].details
        return if (last.watched) episodes.getOrNull(lastIndex + 1)?.details ?: last else last
    }

    /** Marks a movie or episode as fully watched, or as not watched. */
    suspend fun setWatched(id: String, watched: Boolean) {
        withContext(Dispatchers.IO) {
            val itemId = UUID.fromString(id)
            if (watched) api.userDataApi.markPlayedItem(itemId = itemId) else api.userDataApi.markUnplayedItem(itemId = itemId)
        }
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
            seriesId = if (isEpisode) seriesId?.toString() else null,
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

    /** The server counts in ticks of 100 nanoseconds: 600,000,000 ticks are one minute. Rounded. */
    private fun minutes(ticks: Long): Int = ((ticks + 300_000_000L) / 600_000_000L).toInt()

    private fun imageUrl(itemId: UUID, type: ImageType, tag: String, maxWidth: Int): String =
        api.imageApi.getItemImageUrl(itemId = itemId, imageType = type, tag = tag, maxWidth = maxWidth)
}
