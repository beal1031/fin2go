package io.github.beal1031.fin2go.data.playback

import android.content.Context
import io.github.beal1031.fin2go.data.JellyfinClient
import io.github.beal1031.fin2go.data.ServerStatus
import io.github.beal1031.fin2go.data.download.DownloadRepository
import io.github.beal1031.fin2go.data.media.OfflineLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import java.util.UUID

/** What the player shows above the video. */
data class PlaybackInfo(
    /** Movie name, or for an episode the name of its series. */
    val title: String,
    /** Only set for episodes. */
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    /** The episodes before and after this one; null at the first or last episode, and for movies. */
    val previousEpisodeId: String? = null,
    val nextEpisodeId: String? = null,
    /** Where the credits begin, when the server knows it (needs a plugin that finds them, e.g. Intro Skipper). */
    val creditsStartMs: Long? = null,
)

/** Where the video comes from and how the server delivers it. Needed again for every report. */
data class Stream(
    val itemId: UUID,
    val url: String,
    /** True when the original file is sent; otherwise the server converts it into an HLS stream. */
    val directPlay: Boolean,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val audioTracks: List<Track> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** The audio track the server chose, from the user's settings in Jellyfin (or the one asked for). */
    val audioIndex: Int? = null,
    /** The subtitles the server chose the same way; null for none. */
    val subtitleIndex: Int? = null,
)

/** An audio track. [index] is the server's number for it, [title] e.g. "German - AC3 - 5.1". */
data class Track(val index: Int, val title: String, val language: String? = null)

/**
 * A subtitle track. [externalUrl] and [mimeType] are set when the server sends it as a file of its own;
 * otherwise it is inside the video file.
 */
data class SubtitleTrack(
    val index: Int,
    val title: String,
    val externalUrl: String?,
    val mimeType: String?,
    val language: String? = null,
)

/** Everything needed to start one movie or episode. */
data class Playback(val info: PlaybackInfo, val stream: Stream, val startPositionMs: Long)

/** Asks the server how to play a movie or episode, and tells it how far the user is. */
class PlaybackRepository(context: Context) {
    private val context = context.applicationContext
    private val api = JellyfinClient.api(context)
    private val offline = OfflineLibrary(context)
    private val downloads = DownloadRepository(context)
    private val progressSync = ProgressSync(context)

    /** Sent with every request of the player, so the server knows the user. */
    fun authorizationHeader(): String = AuthorizationHeaderBuilder.buildHeader(
        clientName = api.clientInfo.name,
        clientVersion = api.clientInfo.version,
        deviceId = api.deviceInfo.id,
        deviceName = api.deviceInfo.name,
        accessToken = api.accessToken,
    )

    /**
     * Loads the item and the way to play it. It starts at the saved position, or at the beginning
     * when [fromStart] is set or nothing is saved. Without the server, a downloaded item is prepared
     * from what is saved on the phone.
     */
    suspend fun prepare(id: String, fromStart: Boolean, maxBitrate: Int?): Playback =
        serverOrPhone({ prepareFromServer(id, fromStart, maxBitrate) }, { prepareFromPhone(id, fromStart) })

    private suspend fun prepareFromServer(id: String, fromStart: Boolean, maxBitrate: Int?): Playback = coroutineScope {
        val (item, stream) = coroutineScope {
            val item = async { api.libraryApi.getItem(itemId = UUID.fromString(id)).content }
            val stream = async { streamFromServer(id, maxBitrate, audioIndex = null, subtitleIndex = null) }
            item.await() to stream.await()
        }
        val isEpisode = item.type == BaseItemKind.EPISODE
        // As on the episode page: without a season number it counts as season 1, without a number as episode 1.
        val season = if (isEpisode) item.parentIndexNumber ?: 1 else null
        val seriesId = item.seriesId
        val (previous, next) = if (isEpisode && seriesId != null && season != null) {
            neighbours(seriesId, item.id, specials = season == 0)
        } else {
            null to null
        }
        // Only needed when there is a next episode to show.
        playback(item, stream, fromStart, previous, next, creditsStartMs = if (next != null) creditsStart(item.id) else null)
    }

    /**
     * Prepares a downloaded item without the server. Previous and next only lead to downloaded episodes;
     * where the credits begin is not known. Null when the item is not on the phone.
     */
    private suspend fun prepareFromPhone(id: String, fromStart: Boolean): Playback? {
        val item = offline.item(id) ?: return null
        val stream = streamFromPhone(id) ?: return null
        val season = if (item.type == BaseItemKind.EPISODE) item.parentIndexNumber ?: 1 else null
        val seriesId = item.seriesId
        val (previous, next) = if (seriesId != null && season != null) {
            val specials = season == 0
            val episodes = offline.items()
                .filter { it.seriesId == seriesId && ((it.parentIndexNumber ?: 1) == 0) == specials }
                .sortedWith(compareBy({ it.parentIndexNumber ?: 1 }, { it.indexNumber ?: 1 }))
            val position = episodes.indexOfFirst { it.id == item.id }
            episodes.getOrNull(position - 1)?.id?.toString() to episodes.getOrNull(position + 1)?.id?.toString()
        } else {
            null to null
        }
        return playback(item, stream, fromStart, previous, next, creditsStartMs = null)
    }

    private fun playback(
        item: BaseItemDto,
        stream: Stream,
        fromStart: Boolean,
        previous: String?,
        next: String?,
        creditsStartMs: Long?,
    ): Playback {
        val isEpisode = item.type == BaseItemKind.EPISODE
        return Playback(
            info = PlaybackInfo(
                title = (if (isEpisode) item.seriesName else null) ?: item.name.orEmpty(),
                // As on the episode page: without a season number it counts as season 1, without a number as episode 1.
                seasonNumber = if (isEpisode) item.parentIndexNumber ?: 1 else null,
                episodeNumber = if (isEpisode) item.indexNumber ?: 1 else null,
                episodeTitle = if (isEpisode) item.name?.takeIf { it.isNotBlank() } else null,
                previousEpisodeId = previous,
                nextEpisodeId = next,
                creditsStartMs = creditsStartMs,
            ),
            stream = stream,
            startPositionMs = if (fromStart) 0L else ticksToMs(item.userData?.playbackPositionTicks ?: 0L),
        )
    }

    /**
     * Runs [fromServer] while the server is reachable, otherwise [fromPhone]. If the server fails,
     * [fromPhone] is tried too; only when the item is not on the phone either, the error stays.
     */
    private suspend fun <T> serverOrPhone(fromServer: suspend () -> T, fromPhone: suspend () -> T?): T =
        withContext(Dispatchers.IO) {
            if (ServerStatus.reachable(context).value) {
                try {
                    return@withContext fromServer()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ServerStatus.recheck()
                    return@withContext fromPhone() ?: throw e
                }
            }
            fromPhone() ?: error("Not on the phone")
        }

    /** Where the credits of the item begin; null when the server does not know it or cannot tell. */
    private suspend fun creditsStart(itemId: UUID): Long? = runCatching {
        api.mediaSegmentApi.getItemSegments(itemId, listOf(MediaSegmentType.OUTRO)).content.items
            .minOfOrNull { it.startTicks }
            ?.let(::ticksToMs)
    }.getOrNull()

    /**
     * The episodes before and after [episodeId], over all seasons. The specials (season 0) are left out;
     * for a special, only the specials count. Null at the first or last episode.
     */
    private suspend fun neighbours(seriesId: UUID, episodeId: UUID, specials: Boolean): Pair<String?, String?> {
        val episodes = api.showApi.getEpisodes(seriesId = seriesId, isMissing = false).content.items
            // Without a season number, an episode counts as season 1, as on the series page.
            .filter { ((it.parentIndexNumber ?: 1) == 0) == specials }
            // sortedBy keeps the server's order inside each season.
            .sortedBy { it.parentIndexNumber ?: 1 }
        val position = episodes.indexOfFirst { it.id == episodeId }
        if (position < 0) return null to null
        return episodes.getOrNull(position - 1)?.id?.toString() to episodes.getOrNull(position + 1)?.id?.toString()
    }

    /**
     * Asks the server for the way to play the item: the original file, or a converted stream.
     * With [maxBitrate] (bits per second) the server converts everything above it down to that size;
     * without, the phone gets the best it can play. [audioIndex] and [subtitleIndex] choose the tracks
     * (-1: no subtitles); null leaves the choice to the server and the user's settings in Jellyfin.
     * A downloaded item is always played from its file on the phone, in its original quality; the server still
     * tells which tracks it has, because it is the same file.
     */
    suspend fun stream(id: String, maxBitrate: Int?, audioIndex: Int?, subtitleIndex: Int?): Stream =
        serverOrPhone(
            fromServer = { streamFromServer(id, maxBitrate, audioIndex, subtitleIndex) },
            fromPhone = { streamFromPhone(id, audioIndex, subtitleIndex) },
        )

    private suspend fun streamFromServer(id: String, maxBitrate: Int?, audioIndex: Int?, subtitleIndex: Int?): Stream {
        val itemId = UUID.fromString(id)
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                deviceProfile = deviceProfile(),
                maxStreamingBitrate = maxBitrate,
                audioStreamIndex = audioIndex,
                subtitleStreamIndex = subtitleIndex,
                enableDirectPlay = true,
                enableDirectStream = true,
                enableTranscoding = true,
                autoOpenLiveStream = true,
            ),
        ).content
        info.errorCode?.let { error("Server refused playback: $it") }
        val source = info.mediaSources.firstOrNull() ?: error("Item has no playable file")
        val transcodingUrl = source.transcodingUrl
        val localFile = downloads.localFile(id)
        val directPlay = localFile != null || source.supportsDirectPlay
        val url = when {
            localFile != null -> localFile.toUri().toString()
            directPlay -> api.videoApi.getVideoStreamUrl(
                itemId = itemId,
                static = true,
                mediaSourceId = source.id,
                playSessionId = info.playSessionId,
                tag = source.eTag,
            )
            transcodingUrl != null -> serverUrl(transcodingUrl)
            else -> error("Server offered neither the file nor a converted stream")
        }
        val streams = source.mediaStreams.orEmpty()
        return Stream(
            itemId = itemId,
            url = url,
            directPlay = directPlay,
            mediaSourceId = source.id,
            playSessionId = info.playSessionId,
            audioTracks = streams
                .filter { it.type == MediaStreamType.AUDIO }
                .map { Track(it.index, it.displayTitle ?: it.language ?: "#${it.index}", it.language) },
            subtitleTracks = streams
                .filter { it.type == MediaStreamType.SUBTITLE }
                .map { subtitle ->
                    val external = subtitle.deliveryUrl
                        ?.takeIf { subtitle.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL }
                        ?.let(::serverUrl)
                    SubtitleTrack(
                        index = subtitle.index,
                        title = subtitle.displayTitle ?: subtitle.language ?: "#${subtitle.index}",
                        externalUrl = external,
                        mimeType = external?.let(::subtitleMimeType),
                        language = subtitle.language,
                    )
                },
            audioIndex = audioIndex ?: source.defaultAudioStreamIndex,
            subtitleIndex = (subtitleIndex ?: source.defaultSubtitleStreamIndex)?.takeIf { it >= 0 },
        )
    }

    /**
     * The downloaded file, with the tracks inside it from the saved info, for playing without the server.
     * Subtitles in files of their own are not downloaded, so only the ones inside the video are there.
     * Null when the item is not on the phone.
     */
    private suspend fun streamFromPhone(id: String, audioIndex: Int? = null, subtitleIndex: Int? = null): Stream? {
        val item = offline.item(id) ?: return null
        val file = downloads.localFile(id) ?: return null
        val source = item.mediaSources?.firstOrNull()
        val streams = (source?.mediaStreams ?: item.mediaStreams).orEmpty().filter { !it.isExternal }
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }
        val subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE }
        return Stream(
            itemId = item.id,
            url = file.toUri().toString(),
            directPlay = true,
            mediaSourceId = source?.id,
            playSessionId = null,
            audioTracks = audio.map { Track(it.index, it.displayTitle ?: it.language ?: "#${it.index}", it.language) },
            subtitleTracks = subtitles.map {
                SubtitleTrack(it.index, it.displayTitle ?: it.language ?: "#${it.index}", null, null, it.language)
            },
            // As the server would choose: its default for this file, else the track marked as default.
            audioIndex = audioIndex ?: source?.defaultAudioStreamIndex ?: audio.firstOrNull { it.isDefault }?.index
                ?: audio.firstOrNull()?.index,
            subtitleIndex = (subtitleIndex ?: source?.defaultSubtitleStreamIndex ?: subtitles.firstOrNull { it.isForced }?.index)
                ?.takeIf { index -> index >= 0 && subtitles.any { it.index == index } },
        )
    }

    /** The server sends some addresses without its own part at the front. */
    private fun serverUrl(path: String): String =
        if (path.startsWith("http")) path else checkNotNull(api.baseUrl).trimEnd('/') + "/" + path.trimStart('/')

    /** The kind of a subtitle file, from the ending of its address, e.g. ".../Stream.srt?…". */
    private fun subtitleMimeType(url: String): String? = when (url.substringBefore('?').substringAfterLast('.')) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> null
    }

    /** Playback has begun. */
    suspend fun reportStart(stream: Stream, positionMs: Long, paused: Boolean) = reportPosition(stream, positionMs) {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                itemId = stream.itemId,
                mediaSourceId = stream.mediaSourceId,
                playSessionId = stream.playSessionId,
                playMethod = stream.playMethod,
                positionTicks = msToTicks(positionMs),
                isPaused = paused,
                isMuted = false,
                canSeek = true,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    /** Where the user is right now. The server saves it as the position to continue from. */
    suspend fun reportProgress(stream: Stream, positionMs: Long, paused: Boolean) = reportPosition(stream, positionMs) {
        api.sessionApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                itemId = stream.itemId,
                mediaSourceId = stream.mediaSourceId,
                playSessionId = stream.playSessionId,
                playMethod = stream.playMethod,
                positionTicks = msToTicks(positionMs),
                isPaused = paused,
                isMuted = false,
                canSeek = true,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    /**
     * Playback has ended. The server saves the position, stops converting the file and
     * marks the item as watched when the user got near the end.
     */
    suspend fun reportStopped(stream: Stream, positionMs: Long) = reportPosition(stream, positionMs) {
        api.sessionApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = stream.itemId,
                mediaSourceId = stream.mediaSourceId,
                playSessionId = stream.playSessionId,
                positionTicks = msToTicks(positionMs),
                failed = false,
            ),
        )
    }

    private val Stream.playMethod get() = if (directPlay) PlayMethod.DIRECT_PLAY else PlayMethod.TRANSCODE

    /** The last position the server got for each item, in this playback. */
    private val lastSentMs = mutableMapOf<String, Long>()

    /**
     * Sends a report. A failed report must not stop the video, e.g. when the connection drops for a moment.
     * When the server cannot be reached, e.g. while watching a download without internet, the position is kept
     * and sent later (see [ProgressSync]).
     */
    private suspend fun reportPosition(stream: Stream, positionMs: Long, block: suspend () -> Unit) {
        withContext(Dispatchers.IO) {
            val id = stream.itemId.toString()
            // Known offline: no need to wait for the server, e.g. when closing the player.
            val sent = ServerStatus.reachable(context).value && runCatching { block() }.isSuccess
            if (sent) {
                lastSentMs[id] = positionMs
                progressSync.forget(id)
            } else {
                progressSync.remember(
                    itemId = id,
                    positionMs = positionMs,
                    serverPositionMs = lastSentMs[id],
                    mediaSourceId = stream.mediaSourceId,
                    playSessionId = stream.playSessionId,
                )
            }
        }
    }

    /** The server counts in ticks of 100 nanoseconds: 10,000 ticks are one millisecond. */
    private fun msToTicks(ms: Long): Long = ms * 10_000L
    private fun ticksToMs(ticks: Long): Long = ticks / 10_000L
}
