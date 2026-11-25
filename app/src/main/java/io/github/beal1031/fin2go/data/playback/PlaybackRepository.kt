package io.github.beal1031.fin2go.data.playback

import android.content.Context
import io.github.beal1031.fin2go.data.JellyfinClient
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
import androidx.media3.common.MimeTypes
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
    private val api = JellyfinClient.api(context)

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
     * when [fromStart] is set or nothing is saved.
     */
    suspend fun prepare(id: String, fromStart: Boolean, maxBitrate: Int?): Playback = withContext(Dispatchers.IO) {
        val (item, stream) = coroutineScope {
            val item = async { api.libraryApi.getItem(itemId = UUID.fromString(id)).content }
            val stream = async { stream(id, maxBitrate, audioIndex = null, subtitleIndex = null) }
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
        Playback(
            info = PlaybackInfo(
                title = (if (isEpisode) item.seriesName else null) ?: item.name.orEmpty(),
                seasonNumber = season,
                episodeNumber = if (isEpisode) item.indexNumber ?: 1 else null,
                episodeTitle = if (isEpisode) item.name?.takeIf { it.isNotBlank() } else null,
                previousEpisodeId = previous,
                nextEpisodeId = next,
                // Only needed when there is a next episode to show.
                creditsStartMs = if (next != null) creditsStart(item.id) else null,
            ),
            stream = stream,
            startPositionMs = if (fromStart) 0L else ticksToMs(item.userData?.playbackPositionTicks ?: 0L),
        )
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
     */
    suspend fun stream(id: String, maxBitrate: Int?, audioIndex: Int?, subtitleIndex: Int?): Stream = withContext(Dispatchers.IO) {
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
        val directPlay = source.supportsDirectPlay
        val url = when {
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
        Stream(
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
    suspend fun reportStart(stream: Stream, positionMs: Long, paused: Boolean) = report {
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
    suspend fun reportProgress(stream: Stream, positionMs: Long, paused: Boolean) = report {
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
    suspend fun reportStopped(stream: Stream, positionMs: Long) = report {
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

    /** A failed report must not stop the video, e.g. when the connection drops for a moment. */
    private suspend fun report(block: suspend () -> Unit) {
        withContext(Dispatchers.IO) { runCatching { block() } }
    }

    /** The server counts in ticks of 100 nanoseconds: 10,000 ticks are one millisecond. */
    private fun msToTicks(ms: Long): Long = ms * 10_000L
    private fun ticksToMs(ticks: Long): Long = ticks / 10_000L
}
