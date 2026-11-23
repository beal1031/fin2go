package io.github.beal1031.fin2go.ui.player

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.beal1031.fin2go.data.playback.PlaybackInfo
import io.github.beal1031.fin2go.data.playback.PlaybackRepository
import io.github.beal1031.fin2go.data.playback.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Everything the player shows besides the video itself. */
data class PlayerState(
    /** The server is asked how to play the item; no video yet. */
    val loading: Boolean = true,
    /** Loading or playing failed. */
    val error: Boolean = false,
    val info: PlaybackInfo? = null,
    /** False while the user has paused. Stays true while the video waits for data. */
    val playWhenReady: Boolean = true,
    /** The video waits for data, e.g. at the start or after a jump. */
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    /** 0 while the length is not known yet. */
    val durationMs: Long = 0L,
    /** The video has played to the end. */
    val ended: Boolean = false,
)

/**
 * Plays the movie or episode with [itemId], from the saved position or, with [fromStart], from the beginning.
 * The player lives here and not in the UI, so it keeps running while the screen is rebuilt.
 * The server is told the position at the start, every 10 seconds, on pause and jumps, and at the end.
 */
class PlayerViewModel(
    application: Application,
    private val itemId: String,
    private val fromStart: Boolean,
) : AndroidViewModel(application) {
    private val playback = PlaybackRepository(application)

    /** Drawn by the UI; all calls to it happen on the main thread. */
    val player: ExoPlayer = createPlayer(application, playback.authorizationHeader())

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var stream: Stream? = null

    /** Set once the stop was reported, so it is not reported a second time. */
    private var stopped = false

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { it.copy(playWhenReady = playWhenReady) }
            reportProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(buffering = playbackState == Player.STATE_BUFFERING, ended = playbackState == Player.STATE_ENDED)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) reportProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = true) }
        }
    }

    init {
        player.addListener(listener)
        load()
        updatePosition()
    }

    /** Asks the server how to play the item and starts it. */
    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            try {
                val prepared = playback.prepare(itemId, fromStart)
                stream = prepared.stream
                _state.update { it.copy(loading = false, info = prepared.info) }
                player.setMediaItem(prepared.stream.toMediaItem(), prepared.startPositionMs)
                player.prepare()
                player.play()
                playback.reportStart(prepared.stream, prepared.startPositionMs, paused = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
            }
        }
    }

    /** After an error: loads again, or, if the video already ran, continues at the same position. */
    fun retry() {
        if (stream == null) {
            load()
        } else {
            _state.update { it.copy(error = false) }
            player.prepare()
            player.play()
        }
    }

    fun playPause() {
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun pause() = player.pause()

    /** Jumps to [positionMs]. The time is shown at once, so the timeline does not spring back for a moment. */
    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    /**
     * Stops the video, tells the server where the user stopped and then calls [onStopped].
     * Waiting for the server makes the page behind it show the new progress. After 3 seconds without
     * an answer, it goes on anyway.
     */
    fun stop(onStopped: () -> Unit) {
        player.pause()
        val current = stream
        if (stopped || current == null) {
            onStopped()
            return
        }
        stopped = true
        val position = player.currentPosition
        viewModelScope.launch {
            withTimeoutOrNull(3.seconds) { playback.reportStopped(current, position) }
            onStopped()
        }
    }

    /** Updates the time twice a second and reports the progress every 10 seconds while the video plays. */
    private fun updatePosition() {
        viewModelScope.launch {
            var ticks = 0
            while (true) {
                delay(500.milliseconds)
                _state.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        durationMs = player.duration.takeIf { duration -> duration != C.TIME_UNSET } ?: 0L,
                    )
                }
                if (player.isPlaying && ++ticks >= 20) {
                    ticks = 0
                    reportProgress()
                }
            }
        }
    }

    private fun reportProgress() {
        val current = stream ?: return
        if (stopped) return
        val position = player.currentPosition
        val paused = !player.playWhenReady
        viewModelScope.launch { playback.reportProgress(current, position, paused) }
    }

    override fun onCleared() {
        // Closed without stop(), e.g. the app was swiped away: report the stop anyway.
        // viewModelScope is already cancelled here, so the report gets a scope of its own.
        val current = stream
        if (!stopped && current != null) {
            val position = player.currentPosition
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { playback.reportStopped(current, position) }
        }
        player.removeListener(listener)
        player.release()
    }
}

/** Direct play needs only the address; a converted stream must be marked as HLS. */
private fun Stream.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(url)
        .apply { if (!directPlay) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .build()

/**
 * A player that sends the sign-in with every request, pauses on a call or when headphones are unplugged,
 * and decodes audio formats like DTS or TrueHD with FFmpeg when the phone itself cannot.
 */
@OptIn(UnstableApi::class)
private fun createPlayer(context: Context, authorization: String): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(mapOf("Authorization" to authorization))
        .setAllowCrossProtocolRedirects(true)
    val renderers = DefaultRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    val audio = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    return ExoPlayer.Builder(context, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
        .setAudioAttributes(audio, true)
        .setHandleAudioBecomingNoisy(true)
        .build()
}
