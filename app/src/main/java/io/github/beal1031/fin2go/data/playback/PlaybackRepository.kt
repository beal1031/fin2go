package io.github.beal1031.fin2go.data.playback

import android.content.Context
import io.github.beal1031.fin2go.data.JellyfinClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.BaseItemKind
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
)

/** Where the video comes from and how the server delivers it. Needed again for every report. */
data class Stream(
    val itemId: UUID,
    val url: String,
    /** True when the original file is sent; otherwise the server converts it into an HLS stream. */
    val directPlay: Boolean,
    val mediaSourceId: String?,
    val playSessionId: String?,
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
    suspend fun prepare(id: String, fromStart: Boolean): Playback = withContext(Dispatchers.IO) {
        val (item, stream) = coroutineScope {
            val item = async { api.libraryApi.getItem(itemId = UUID.fromString(id)).content }
            val stream = async { stream(id, maxBitrate = null) }
            item.await() to stream.await()
        }
        val isEpisode = item.type == BaseItemKind.EPISODE
        Playback(
            info = PlaybackInfo(
                title = (if (isEpisode) item.seriesName else null) ?: item.name.orEmpty(),
                // As on the episode page: without a season number it counts as season 1, without a number as episode 1.
                seasonNumber = if (isEpisode) item.parentIndexNumber ?: 1 else null,
                episodeNumber = if (isEpisode) item.indexNumber ?: 1 else null,
                episodeTitle = if (isEpisode) item.name?.takeIf { it.isNotBlank() } else null,
            ),
            stream = stream,
            startPositionMs = if (fromStart) 0L else ticksToMs(item.userData?.playbackPositionTicks ?: 0L),
        )
    }

    /**
     * Asks the server for the way to play the item: the original file, or a converted stream.
     * With [maxBitrate] (bits per second) the server converts everything above it down to that size;
     * without, the phone gets the best it can play.
     */
    suspend fun stream(id: String, maxBitrate: Int?): Stream = withContext(Dispatchers.IO) {
        val itemId = UUID.fromString(id)
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                deviceProfile = deviceProfile(),
                maxStreamingBitrate = maxBitrate,
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
            // The server sends this address without its own part at the front.
            transcodingUrl != null -> checkNotNull(api.baseUrl).trimEnd('/') + "/" + transcodingUrl.trimStart('/')
            else -> error("Server offered neither the file nor a converted stream")
        }
        Stream(
            itemId = itemId,
            url = url,
            directPlay = directPlay,
            mediaSourceId = source.id,
            playSessionId = info.playSessionId,
        )
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
