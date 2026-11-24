package io.github.beal1031.fin2go.ui.player

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import io.github.beal1031.fin2go.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.core.net.toUri
import io.github.beal1031.fin2go.data.playback.SubtitleTrack
import io.github.beal1031.fin2go.data.playback.Track
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
    /** 1 is normal speed. */
    val speed: Float = 1f,
    val quality: Quality = Quality.AUTO,
    val audioTracks: List<Track> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** The chosen audio track, by the server's number. */
    val audioIndex: Int? = null,
    /** The chosen subtitles, by the server's number; null for none. */
    val subtitleIndex: Int? = null,
)

/**
 * How big the video may be. [maxBitrate] is the most the server may send, in bits per second;
 * above it, the server converts the video down. AUTO has no limit: the original file when the phone can play it.
 */
enum class Quality(@StringRes val label: Int, val maxBitrate: Int?) {
    AUTO(R.string.player_quality_auto, null),
    P1080(R.string.player_quality_1080, 10_000_000),
    P720(R.string.player_quality_720, 4_000_000),
    P480(R.string.player_quality_480, 1_500_000),
}

/** The speeds to choose from; 1 is normal. */
val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

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

        // The tracks of a new video are known only once it has started loading; then the chosen ones are set.
        override fun onTracksChanged(tracks: Tracks) {
            if (!tracksApplied && !tracks.isEmpty) {
                tracksApplied = true
                applyTracks()
            }
        }
    }

    /** False until the chosen tracks are set on the current video. */
    private var tracksApplied = false

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
                _state.update { it.copy(loading = false, info = prepared.info) }
                useStream(prepared.stream, prepared.startPositionMs)
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

    /** Only for this playback; the next video starts at normal speed again. */
    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    /** Only for this playback. Gets a new stream in [quality] from the server, see [switchStream]. */
    fun setQuality(quality: Quality) {
        if (quality == _state.value.quality) return
        _state.update { it.copy(quality = quality) }
        switchStream()
    }

    /**
     * Plays the audio track with the server's number [index]. The original file has all tracks, so the player
     * switches at once; a converted stream has only one, so the server has to make a new one.
     */
    fun selectAudio(index: Int) {
        if (index == _state.value.audioIndex) return
        _state.update { it.copy(audioIndex = index) }
        if (stream?.directPlay == true) applyTracks() else switchStream()
    }

    /** Shows the subtitles with the server's number [index], or none for null. Switches like [selectAudio]. */
    fun selectSubtitles(index: Int?) {
        if (index == _state.value.subtitleIndex) return
        _state.update { it.copy(subtitleIndex = index) }
        if (stream?.directPlay == true) applyTracks() else switchStream()
    }

    /** Starts [new] at [positionMs]; its tracks are set as soon as the player knows them. */
    private fun useStream(new: Stream, positionMs: Long) {
        stream = new
        tracksApplied = false
        _state.update {
            it.copy(
                audioTracks = new.audioTracks,
                subtitleTracks = new.subtitleTracks,
                audioIndex = new.audioIndex,
                subtitleIndex = new.subtitleIndex,
            )
        }
        player.setMediaItem(new.toMediaItem(), positionMs)
        player.prepare()
    }

    /**
     * Asks the server for a new stream with the chosen quality and tracks and continues at the same position.
     * The old stream is reported as stopped, so the server stops converting it.
     */
    private fun switchStream() {
        val old = stream ?: return
        val position = player.currentPosition
        val chosen = _state.value
        viewModelScope.launch {
            try {
                playback.reportStopped(old, position)
                val new = playback.stream(
                    itemId,
                    maxBitrate = chosen.quality.maxBitrate,
                    audioIndex = chosen.audioIndex,
                    // -1 tells the server: no subtitles.
                    subtitleIndex = chosen.subtitleIndex ?: -1,
                )
                useStream(new, position)
                playback.reportStart(new, position, paused = !player.playWhenReady)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = true) }
            }
        }
    }

    /**
     * Tells the player which of its tracks to play. The server and the player number the tracks differently;
     * inside the file both keep the same order, so the n-th audio track of the server is the n-th of the player.
     * Subtitles in a file of their own carry the server's number in their id (see [toMediaItem]).
     * In a converted stream the server has already put in the chosen audio, and burned in chosen subtitles
     * from inside the file.
     */
    private fun applyTracks() {
        val current = stream ?: return
        val chosen = _state.value
        val groups = player.currentTracks.groups
        val parameters = player.trackSelectionParameters.buildUpon()

        if (current.directPlay) {
            val position = current.audioTracks.indexOfFirst { it.index == chosen.audioIndex }
            groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(position)?.let {
                parameters.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0))
            }
        }

        val subtitle = current.subtitleTracks.find { it.index == chosen.subtitleIndex }
        val textGroups = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val (ownFiles, inFile) = textGroups.partition { it.getTrackFormat(0).id.orEmpty().contains(EXTERNAL_ID) }
        val group = when {
            subtitle == null -> null
            subtitle.externalUrl != null -> ownFiles.find { it.getTrackFormat(0).id.orEmpty().endsWith(externalId(subtitle.index)) }
            current.directPlay -> {
                val position = current.subtitleTracks.filter { it.externalUrl == null }.indexOf(subtitle)
                inFile.getOrNull(position)
            }
            else -> null
        }
        if (group == null) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = parameters.build()
    }

    /** Jumps [byMs] forward, or back when negative, but not before the start or past the end. */
    fun skip(byMs: Long) {
        val target = (player.currentPosition + byMs).coerceAtLeast(0L)
        val duration = player.duration
        seekTo(if (duration == C.TIME_UNSET) target else target.coerceAtMost(duration))
    }

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

/**
 * Direct play needs only the address; a converted stream must be marked as HLS.
 * Subtitles in a file of their own are added, each with an id that holds the server's number.
 */
private fun Stream.toMediaItem(): MediaItem {
    val subtitles = subtitleTracks.mapNotNull { track ->
        val url = track.externalUrl ?: return@mapNotNull null
        MediaItem.SubtitleConfiguration.Builder(url.toUri())
            .setMimeType(track.mimeType)
            .setLabel(track.title)
            .setId(externalId(track.index))
            .build()
    }
    return MediaItem.Builder()
        .setUri(url)
        .apply { if (!directPlay) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setSubtitleConfigurations(subtitles)
        .build()
}

/** Marks the id of a subtitle file of its own, so the player's track can be found again. */
private const val EXTERNAL_ID = "server-subtitle-"

private fun externalId(index: Int) = "$EXTERNAL_ID$index"

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
