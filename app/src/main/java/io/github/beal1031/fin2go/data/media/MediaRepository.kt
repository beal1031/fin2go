package io.github.beal1031.fin2go.data.media

import android.content.Context
import io.github.beal1031.fin2go.data.JellyfinClient
import io.github.beal1031.fin2go.data.ServerStatus
import kotlinx.coroutines.CancellationException
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

/**
 * Reads movies and series of the signed-in user. All calls run off the main thread.
 * While the server cannot be reached, the same answers come from the downloads on the phone
 * (see [OfflineLibrary]), so every page looks the same, only with fewer items.
 */
class MediaRepository(context: Context) {
    private val context = context.applicationContext
    private val api = JellyfinClient.api(context)
    private val offline = OfflineLibrary(context)

    /** Where the pictures come from: the server, or the files saved with the downloads. */
    private fun interface Images {
        fun url(itemId: UUID, type: ImageType, tag: String, maxWidth: Int): String?
    }

    private val serverImages = Images { itemId, type, tag, maxWidth ->
        api.imageApi.getItemImageUrl(itemId = itemId, imageType = type, tag = tag, maxWidth = maxWidth)
    }
    private val offlineImages = Images { itemId, type, _, _ -> offline.image(itemId, type) }

    /**
     * Asks the server with [fromServer] while it is reachable, otherwise takes the downloads with [fromPhone].
     * If the server fails, the downloads are taken too; only when they do not have it either, the error stays.
     */
    private suspend fun <T> serverOrPhone(fromServer: suspend () -> T, fromPhone: suspend () -> T?): T =
        withContext(Dispatchers.IO) {
            if (ServerStatus.reachable(context).value) {
                try {
                    return@withContext fromServer()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Probably just gone: check again, so the cloud shows up soon.
                    ServerStatus.recheck()
                    return@withContext fromPhone() ?: throw e
                }
            }
            fromPhone() ?: error("Not on the phone")
        }

    /**
     * Keeps the watched state of what the server just sent for the downloads on the phone, for offline.
     * A failure there must not break the page.
     */
    private suspend fun remember(items: List<BaseItemDto>) {
        runCatching { offline.remember(items) }
    }

    /**
     * One row for continuing: the started movies and episodes, and the next unwatched episode of series
     * watched in the last 60 days. All together sorted by when they were last watched, newest first;
     * for a next episode that is when the series was last watched. Only one entry per series.
     * Fully watched movies and series are not in it. Shown with a wide image.
     */
    suspend fun continueWatching(limit: Int = 20): List<MediaItem> = serverOrPhone(
        fromServer = {
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
            remember(started + nextUp + recentlyPlayed)
            continueRow(started, nextUp, recentlyPlayed, limit, serverImages)
        },
        fromPhone = {
            val items = offline.items()
            val started = items
                .filter { (it.userData?.playbackPositionTicks ?: 0L) > 0L }
                .sortedByDescending { it.userData?.lastPlayedDate ?: LocalDateTime.MIN }
            // For each series: its first downloaded episode after the last watched one.
            val nextUp = items
                .filter { it.type == BaseItemKind.EPISODE && it.seriesId != null }
                .groupBy { it.seriesId }
                .values
                .mapNotNull { episodes ->
                    val ordered = episodes.sortedWith(compareBy({ it.parentIndexNumber ?: 1 }, { it.indexNumber ?: 1 }))
                    val lastWatched = ordered.indexOfLast { it.userData?.played == true }
                    // Nothing of it watched yet: the series is not "in progress".
                    if (lastWatched < 0) null
                    else ordered.drop(lastWatched + 1).firstOrNull { it.userData?.played != true }
                }
            continueRow(started, nextUp, items, limit, offlineImages)
        },
    )

    /** Puts the started items and the next episodes into one row; see [continueWatching]. */
    private fun continueRow(
        started: List<BaseItemDto>,
        nextUp: List<BaseItemDto>,
        recentlyPlayed: List<BaseItemDto>,
        limit: Int,
        images: Images,
    ): List<MediaItem> {
        // Two started episodes of one series: only the most recently watched one stays.
        // The list has the most recently watched first, and distinctBy keeps the first of each series.
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
        return (startedOnce + nextUp.filter { it.seriesId !in seriesWithStartedEpisode })
            .sortedByDescending(lastPlayed)
            .take(limit)
            .map { it.toMediaItem(wideImageUrl(it, images)) }
    }

    /**
     * Newest series or movies, shown with a poster.
     * Fully watched ones come last; a series is fully watched when all its episodes are.
     */
    suspend fun latest(type: BaseItemKind, limit: Int = 20): List<MediaItem> = serverOrPhone(
        fromServer = {
            api.libraryApi.getLatestMedia(
                includeItemTypes = listOf(type),
                limit = limit,
                enableUserData = true,
            ).content.also { remember(it) }.map { it.toMediaItem(posterUrl(it, serverImages)) }
        },
        fromPhone = {
            // Newest on the server first, as online.
            offlineOfType(type)
                .sortedByDescending { it.dateLastMediaAdded ?: it.dateCreated ?: LocalDateTime.MIN }
                .take(limit)
                .map { it.toMediaItem(posterUrl(it, offlineImages)) }
        },
    )
        // sortedBy keeps the order within each group, so both stay newest first.
        .sortedBy { it.watched }

    /**
     * All series or all movies of the user's libraries, sorted by name (A–Z), shown with a poster.
     * Everything is loaded at once; the pictures are only loaded when a tile becomes visible.
     */
    suspend fun all(type: BaseItemKind): List<MediaItem> = serverOrPhone(
        fromServer = {
            api.libraryApi.getItems(
                includeItemTypes = listOf(type),
                // Also look inside folders, otherwise only the top level of a library is returned.
                recursive = true,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = listOf(SortOrder.ASCENDING),
                enableUserData = true,
                // The total count is not needed and costs the server extra work.
                enableTotalRecordCount = false,
            ).content.items.also { remember(it) }.map { it.toMediaItem(posterUrl(it, serverImages)) }
        },
        fromPhone = {
            offlineOfType(type)
                .sortedBy { (it.sortName ?: it.name.orEmpty()).lowercase() }
                .map { it.toMediaItem(posterUrl(it, offlineImages)) }
        },
    )

    /** The downloaded movies, or the series with downloaded episodes. */
    private suspend fun offlineOfType(type: BaseItemKind): List<BaseItemDto> =
        if (type == BaseItemKind.SERIES) offline.series() else offline.items().filter { it.type == type }

    /** All details of one movie for the detail page. */
    suspend fun movie(id: String): MovieDetails = serverOrPhone(
        fromServer = {
            api.libraryApi.getItem(itemId = UUID.fromString(id)).content.also { remember(listOf(it)) }.toMovieDetails(serverImages)
        },
        fromPhone = { offline.item(id)?.toMovieDetails(offlineImages) },
    )

    private fun BaseItemDto.toMovieDetails(images: Images): MovieDetails {
        val position = userData?.playbackPositionTicks ?: 0L
        // As for the tiles: a saved position means started, even when it was watched before.
        // Null when not started (or the running time is unknown).
        val startedRuntime = runTimeTicks?.takeIf { it > 0L && position > 0L }
        return MovieDetails(
            id = id.toString(),
            title = name.orEmpty(),
            year = productionYear,
            runtimeMinutes = runTimeTicks?.let(::minutes),
            ageRating = officialRating?.takeIf { it.isNotBlank() },
            genres = genres.orEmpty(),
            overview = overview?.takeIf { it.isNotBlank() },
            backdropUrl = backdropImageTags?.firstOrNull()?.let { images.url(id, ImageType.BACKDROP, it, 1280) },
            posterUrl = posterUrl(this, images),
            progress = startedRuntime?.let { (position.toFloat() / it).coerceIn(0f, 1f) },
            remainingMinutes = startedRuntime?.let { minutes(it - position).coerceAtLeast(1) },
            watched = startedRuntime == null && userData?.played == true,
        )
    }

    /**
     * All details of one series with all its episodes, for the detail page.
     * Episodes come in watching order: season 1, 2, … and the specials (season 0) last.
     * Offline, only the downloaded episodes are in it.
     */
    suspend fun series(id: String): SeriesDetails = serverOrPhone(
        fromServer = {
            val seriesId = UUID.fromString(id)
            val (item, episodeItems) = coroutineScope {
                val item = async { api.libraryApi.getItem(itemId = seriesId).content }
                val episodes = async {
                    api.showApi.getEpisodes(
                        seriesId = seriesId,
                        // The description and the file (for its size) are only sent when asked for.
                        fields = listOf(ItemFields.OVERVIEW, ItemFields.MEDIA_SOURCES),
                        // Episodes the server only knows from the internet, without a file, are left out.
                        isMissing = false,
                        enableUserData = true,
                    ).content.items
                }
                item.await() to episodes.await()
            }
            remember(episodeItems + item)
            item.toSeriesDetails(episodeItems, serverImages)
        },
        fromPhone = {
            offline.item(id)?.toSeriesDetails(offline.items().filter { it.seriesId?.toString() == id }, offlineImages)
        },
    )

    private fun BaseItemDto.toSeriesDetails(episodeItems: List<BaseItemDto>, images: Images): SeriesDetails {
        val episodes = episodeItems
            // Without a season number, an episode counts as season 1.
            .groupBy { it.parentIndexNumber ?: 1 }
            // Specials (0) last, the other seasons by number.
            .toSortedMap(compareBy<Int> { it == 0 }.thenBy { it })
            .flatMap { (season, items) ->
                // The server sends a season in order; the downloads come in any order.
                items.sortedBy { it.indexNumber ?: Int.MAX_VALUE }
                    // Without an episode number, the place in the season is used.
                    .mapIndexed { index, episode -> episode.toEpisode(season, episode.indexNumber ?: (index + 1), images) }
            }
        val firstYear = productionYear
        val lastYear = endDate?.year
        // e.g. "2010–2015", "2010–" while it still runs, or "2010" for one year.
        val years = when {
            firstYear == null -> null
            status == "Continuing" -> "$firstYear–"
            lastYear != null && lastYear != firstYear -> "$firstYear–$lastYear"
            else -> firstYear.toString()
        }
        return SeriesDetails(
            id = id.toString(),
            title = name.orEmpty(),
            years = years,
            ageRating = officialRating?.takeIf { it.isNotBlank() },
            genres = genres.orEmpty(),
            overview = overview?.takeIf { it.isNotBlank() },
            backdropUrl = backdropImageTags?.firstOrNull()?.let { images.url(id, ImageType.BACKDROP, it, 1280) },
            posterUrl = posterUrl(this, images),
            episodes = episodes.map { it.details },
            currentEpisodeId = currentEpisode(episodes)?.id,
        )
    }

    /** One episode with the name and pictures of its series, for the episode page. */
    suspend fun episode(id: String): EpisodePageDetails = serverOrPhone(
        fromServer = {
            api.libraryApi.getItem(itemId = UUID.fromString(id)).content.also { remember(listOf(it)) }.toEpisodePage(serverImages)
        },
        fromPhone = { offline.item(id)?.toEpisodePage(offlineImages) },
    )

    private fun BaseItemDto.toEpisodePage(images: Images): EpisodePageDetails {
        // As on the series page: without a season number it counts as season 1.
        val episode = toEpisode(parentIndexNumber ?: 1, indexNumber ?: 1, images).details
        val ownImage = imageTags?.get(ImageType.PRIMARY)?.let { images.url(id, ImageType.PRIMARY, it, 1280) }
        val parentId = parentBackdropItemId
        val parentTag = parentBackdropImageTags?.firstOrNull()
        val seriesBackdrop = if (parentId != null && parentTag != null) {
            images.url(parentId, ImageType.BACKDROP, parentTag, 1280)
        } else {
            null
        }
        val seriesId = seriesId
        val seriesPosterTag = seriesPrimaryImageTag
        return EpisodePageDetails(
            episode = episode,
            seriesTitle = seriesName?.takeIf { it.isNotBlank() },
            backdropUrl = ownImage ?: seriesBackdrop,
            posterUrl = if (seriesId != null && seriesPosterTag != null) {
                images.url(seriesId, ImageType.PRIMARY, seriesPosterTag, 400)
            } else {
                null
            },
        )
    }

    /** An episode and when it was last watched; the time is only needed to find the current episode. */
    private class Episode(val details: EpisodeDetails, val lastPlayed: LocalDateTime?)

    private fun BaseItemDto.toEpisode(season: Int, number: Int, images: Images): Episode {
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
            imageUrl = wideImageUrl(this, images),
            progress = startedRuntime?.let { (position.toFloat() / it).coerceIn(0f, 1f) },
            remainingMinutes = startedRuntime?.let { minutes(it - position).coerceAtLeast(1) },
            watched = startedRuntime == null && userData?.played == true,
            sizeBytes = mediaSources?.firstOrNull()?.size,
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

    /** Portrait poster. Episodes use the poster of their series. Offline, a picture that was not saved is skipped. */
    private fun posterUrl(item: BaseItemDto, images: Images): String? {
        item.imageTags?.get(ImageType.PRIMARY)?.let { tag -> images.url(item.id, ImageType.PRIMARY, tag, 400)?.let { return it } }
        val seriesId = item.seriesId ?: return null
        val seriesTag = item.seriesPrimaryImageTag ?: return null
        return images.url(seriesId, ImageType.PRIMARY, seriesTag, 400)
    }

    /** Landscape image: the episode's own picture, else a backdrop, else the poster. */
    private fun wideImageUrl(item: BaseItemDto, images: Images): String? {
        if (item.type == BaseItemKind.EPISODE) {
            item.imageTags?.get(ImageType.PRIMARY)?.let { tag -> images.url(item.id, ImageType.PRIMARY, tag, 600)?.let { return it } }
        }
        item.backdropImageTags?.firstOrNull()?.let { tag -> images.url(item.id, ImageType.BACKDROP, tag, 600)?.let { return it } }
        val parentId = item.parentBackdropItemId
        val parentTag = item.parentBackdropImageTags?.firstOrNull()
        if (parentId != null && parentTag != null) images.url(parentId, ImageType.BACKDROP, parentTag, 600)?.let { return it }
        return posterUrl(item, images)
    }

    /** The server counts in ticks of 100 nanoseconds: 600,000,000 ticks are one minute. Rounded. */
    private fun minutes(ticks: Long): Int = ((ticks + 300_000_000L) / 600_000_000L).toInt()
}
