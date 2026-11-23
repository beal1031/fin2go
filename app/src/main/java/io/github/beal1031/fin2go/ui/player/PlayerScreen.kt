package io.github.beal1031.fin2go.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import coil3.compose.AsyncImage
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.playback.PlaybackInfo
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE_LANDSCAPE
import io.github.beal1031.fin2go.ui.PreviewImage
import io.github.beal1031.fin2go.ui.WithPreviewImages
import io.github.beal1031.fin2go.ui.components.episodeLabel
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import java.util.Locale

/**
 * Connects the ViewModel to the UI. Used by the app.
 * The player always fills the screen in landscape, without status and navigation bar.
 * [onClose] is called after the stop was reported to the server: on the back arrow, the back gesture
 * and at the end of the video.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    itemId: String,
    fromStart: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // The ViewModel needs to know what to play, so it is created here with the id.
    viewModel: PlayerViewModel = viewModel {
        PlayerViewModel(checkNotNull(this[APPLICATION_KEY]), itemId, fromStart)
    },
) {
    val state by viewModel.state.collectAsState()
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    // Counts every use of the controls, so they stay a little longer after each one.
    var interactions by remember { mutableIntStateOf(0) }
    val activity = LocalActivity.current
    // The phone turns back and the status bar returns in the same moment the page behind appears, not one step later.
    val close = {
        viewModel.stop {
            activity?.let(::restoreWindow)
            onClose()
        }
    }

    PlayerWindow()
    KeepScreenOn(state.playWhenReady && !state.error)
    BackHandler(onBack = close)
    // Leaving the app pauses the video. Not when the screen is only rebuilt, e.g. when the app language changes.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) viewModel.pause()
    }
    LaunchedEffect(state.ended) { if (state.ended) close() }
    // While the video plays, the controls disappear 3 seconds after they were last used. Paused, they stay.
    LaunchedEffect(controlsVisible, state.playWhenReady, interactions) {
        if (controlsVisible && state.playWhenReady) {
            delay(3.seconds)
            controlsVisible = false
        }
    }

    // Jumps in a row in the same direction add up, e.g. two times forward shows "+60 s".
    // The sum is shown for a moment; 1.5 seconds after the last jump a new row begins.
    var skipped by remember { mutableStateOf<Skipped?>(null) }
    LaunchedEffect(skipped) {
        if (skipped != null) {
            delay(1.5.seconds)
            skipped = null
        }
    }
    val skip = { forward: Boolean ->
        val seconds = if (forward) FORWARD_SECONDS else BACK_SECONDS
        val row = skipped?.takeIf { it.forward == forward }
        skipped = Skipped(forward, (row?.seconds ?: 0) + seconds)
        viewModel.skip(if (forward) seconds * 1_000L else -seconds * 1_000L)
    }

    PlayerContent(
        state = state,
        controlsVisible = controlsVisible,
        skipped = skipped,
        onTap = { controlsVisible = !controlsVisible },
        // Each double tap jumps exactly once: left half back, right half forward.
        onDoubleTap = { leftHalf -> skip(!leftHalf) },
        onSkip = { forward ->
            interactions++
            skip(forward)
        },
        onPlayPause = {
            interactions++
            viewModel.playPause()
        },
        onSeeking = { interactions++ },
        onSeek = {
            interactions++
            viewModel.seekTo(it)
        },
        onBack = close,
        onRetry = viewModel::retry,
        modifier = modifier,
    ) {
        // Draws the video in its own aspect ratio, with black bars where it does not fill the screen.
        ContentFrame(player = viewModel.player, modifier = Modifier.fillMaxSize())
    }
}

/** How far the last jumps in a row went together, e.g. 60 seconds forward. */
data class Skipped(val forward: Boolean, val seconds: Int)

const val BACK_SECONDS = 10
const val FORWARD_SECONDS = 30

/**
 * The pure UI over the [video]: loading, error, or the controls.
 * Top: back arrow and title (for an episode with season and number).
 * Middle: 10 s back, play/pause, 30 s forward. Bottom: timeline with the time played and the time left.
 * [onTap] shows or hides the controls; [onDoubleTap] gets whether the left half of the screen was touched.
 * [skipped] shows the sum of the last jumps on the side they went to.
 * [onSeeking] is called while the timeline is dragged, [onSeek] with the new position when it is let go.
 */
@Composable
fun PlayerContent(
    state: PlayerState,
    controlsVisible: Boolean,
    skipped: Skipped?,
    onTap: () -> Unit,
    onDoubleTap: (leftHalf: Boolean) -> Unit,
    onSkip: (forward: Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onSeeking: () -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    video: @Composable () -> Unit,
) {
    // The touch handling below is set up once; through these it always calls the newest callbacks.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnTap() },
                    onDoubleTap = { currentOnDoubleTap(it.x < size.width / 2) },
                )
            },
    ) {
        if (!state.loading) video()

        // Loading and error always show the back arrow, so there is always a way out.
        val showControls = controlsVisible || state.loading || state.error
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Darkens the video a little, so the white controls and the error stay readable on bright pictures.
                if (!state.loading) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(16.dp),
                ) {
                    TopRow(info = state.info, onBack = onBack)
                    Spacer(modifier = Modifier.weight(1f))
                    if (!state.loading && !state.error) {
                        Timeline(
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            onSeeking = onSeeking,
                            onSeek = onSeek,
                        )
                    }
                }
                if (!state.loading && !state.error) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(48.dp),
                    ) {
                        SkipButton(forward = false, onClick = { onSkip(false) })
                        if (state.buffering) {
                            // The loading circle below takes this place.
                            Spacer(modifier = Modifier.size(72.dp))
                        } else {
                            PlayPauseButton(playing = state.playWhenReady, onClick = onPlayPause)
                        }
                        SkipButton(forward = true, onClick = { onSkip(true) })
                    }
                }
            }
        }

        // In the middle, over the darkened video. The loading circle also shows with the controls hidden,
        // so a waiting video never looks frozen; with the controls shown it takes the place of play/pause.
        when {
            state.error -> ErrorView(onRetry = onRetry, modifier = Modifier.align(Alignment.Center))
            state.loading || state.buffering ->
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        // The sum of the last jumps, in the middle of the half that was tapped.
        skipped?.let {
            SkippedBadge(
                skipped = it,
                modifier = Modifier
                    .align(if (it.forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 96.dp),
            )
        }
    }
}

/** Round arrow with the seconds inside: turning back for [BACK_SECONDS], forward for [FORWARD_SECONDS]. */
@Composable
private fun SkipButton(forward: Boolean, onClick: () -> Unit) {
    val seconds = if (forward) FORWARD_SECONDS else BACK_SECONDS
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_replay),
                contentDescription = stringResource(
                    if (forward) R.string.player_skip_forward else R.string.player_skip_back,
                    seconds,
                ),
                // The same arrow, mirrored, turns forward.
                modifier = Modifier.size(44.dp).graphicsLayer { scaleX = if (forward) -1f else 1f },
            )
            Text(
                text = seconds.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** e.g. "+60 s" or "−10 s" on a dark round background. */
@Composable
private fun SkippedBadge(skipped: Skipped, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            if (skipped.forward) R.string.player_skipped_forward else R.string.player_skipped_back,
            skipped.seconds,
        ),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** Back arrow, then the title. For an episode a second line, e.g. "S1:E2 · Gran Dillama". */
@Composable
private fun TopRow(info: PlaybackInfo?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconButton(
            onClick = onBack,
            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
        ) {
            Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
        }
        if (info != null) {
            Column {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val season = info.seasonNumber
                val number = info.episodeNumber
                if (season != null && number != null) {
                    Text(
                        text = listOfNotNull(episodeLabel(season, number), info.episodeTitle).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The big round button in the middle: pause while it plays, play while it is paused. */
@Composable
private fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(72.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
    ) {
        Icon(
            painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = stringResource(if (playing) R.string.player_pause else R.string.movie_play),
            modifier = Modifier.size(56.dp),
        )
    }
}

/**
 * Time played, the bar and the time left, e.g. "12:34 ——●—— -48:10".
 * While the bar is dragged, both times follow the finger; the video only jumps when it is let go.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Timeline(positionMs: Long, durationMs: Long, onSeeking: () -> Unit, onSeek: (Long) -> Unit) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMs > 0L) positionMs.toFloat() / durationMs else 0f
    val shownPosition = dragFraction?.let { (it * durationMs).toLong() } ?: positionMs

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = formatTime(shownPosition), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = {
                dragFraction = it
                onSeeking()
            },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
            // As long as the length is unknown, there is nothing to jump to.
            enabled = durationMs > 0L,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            // A round dot instead of the tall bar, on a thin line without a gap around the dot.
            thumb = {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
        Text(
            text = "-" + formatTime(durationMs - shownPosition),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun ErrorView(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.player_failed), color = Color.White, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

/** e.g. "4:05", or "1:02:03" from one hour on. */
private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/**
 * While the player is shown: landscape, turned either way, and the status bar (time, battery) hidden.
 * A swipe from the top shows it for a moment. The navigation bar stays, see-through over the video.
 * When the player closes, everything goes back to normal, see [restoreWindow].
 */
@Composable
private fun PlayerWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            if (!activity.isChangingConfigurations) restoreWindow(activity)
        }
    }
}

/** The phone may turn freely again and the status bar is shown again. */
private fun restoreWindow(activity: Activity) {
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        .show(WindowInsetsCompat.Type.statusBars())
}

/** Keeps the screen from turning off while [on] is true. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

// --- Previews: one per state of the screen ---

private val movieInfo = PlaybackInfo(title = "Tears of Steel")

private val episodeInfo = PlaybackInfo(
    title = "Caminandes",
    seasonNumber = 1,
    episodeNumber = 2,
    episodeTitle = "Gran Dillama",
)

private val playingState = PlayerState(
    loading = false,
    info = movieInfo,
    positionMs = 4 * 60_000L + 5_000L,
    durationMs = 12 * 60_000L + 14_000L,
)

// The padding of the Scaffold is left unused on purpose: as in the app, the player fills the whole screen.
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
private fun PlayerPreview(
    state: PlayerState,
    controlsVisible: Boolean = true,
    picture: PreviewImage = PreviewImage.BACKDROP_TEARS_OF_STEEL,
    skipped: Skipped? = null,
) {
    Fin2goTheme {
        WithPreviewImages {
            // Same Scaffold as in MainActivity. As in the app, the video runs behind the system bars
            // and only the controls keep clear of them.
            Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                PlayerContent(
                    state = state,
                    controlsVisible = controlsVisible,
                    skipped = skipped,
                    onTap = {},
                    onDoubleTap = {},
                    onSkip = {},
                    onPlayPause = {},
                    onSeeking = {},
                    onSeek = {},
                    onBack = {},
                    onRetry = {},
                ) {
                    // A still picture in place of the video.
                    AsyncImage(
                        model = picture.url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Preview(name = "1 Loading", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerLoadingPreview() = PlayerPreview(PlayerState())

@Preview(name = "2 Playing, controls shown", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerPlayingPreview() = PlayerPreview(playingState)

@Preview(name = "2b Playing, controls hidden", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerHiddenPreview() = PlayerPreview(playingState, controlsVisible = false)

@Preview(name = "2c Paused", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerPausedPreview() = PlayerPreview(playingState.copy(playWhenReady = false))

@Preview(name = "2c2 Jumped forward twice (double tap)", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerSkippedForwardPreview() =
    PlayerPreview(playingState, controlsVisible = false, skipped = Skipped(forward = true, seconds = 60))

@Preview(name = "2c3 Jumped back, controls shown", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerSkippedBackPreview() =
    PlayerPreview(playingState, skipped = Skipped(forward = false, seconds = 10))

@Preview(name = "2d Waiting for data", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerBufferingPreview() = PlayerPreview(playingState.copy(buffering = true), controlsVisible = false)

@Preview(name = "2d2 Waiting for data, controls shown", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerBufferingControlsPreview() = PlayerPreview(playingState.copy(buffering = true))

@Preview(name = "2d3 Length not known yet", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerNoDurationPreview() =
    PlayerPreview(playingState.copy(buffering = true, positionMs = 0L, durationMs = 0L))

@Preview(name = "2e Episode, over one hour", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerEpisodePreview() = PlayerPreview(
    playingState.copy(info = episodeInfo, positionMs = 62 * 60_000L + 3_000L, durationMs = 95 * 60_000L),
    picture = PreviewImage.EPISODE_CAMINANDES_2,
)

@Preview(name = "2f Special with a long title", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerSpecialPreview() = PlayerPreview(
    playingState.copy(
        info = PlaybackInfo(
            title = "Caminandes",
            seasonNumber = 0,
            episodeNumber = 1,
            episodeTitle = "Behind the scenes of all three episodes, with the whole team and many early sketches",
        ),
    ),
    picture = PreviewImage.EPISODE_CAMINANDES_3,
)

@Preview(name = "2g German", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE, locale = "de")
@Preview(name = "2h French", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE, locale = "fr")
@Preview(name = "2i Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE, locale = "lb")
@Composable
private fun PlayerSpecialLanguagesPreview() = PlayerSpecialPreview()

@Preview(name = "3 Error", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE)
@Composable
private fun PlayerErrorPreview() = PlayerPreview(PlayerState(loading = false, error = true))

@Preview(name = "3b German", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE, locale = "de")
@Preview(name = "3c French", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE, locale = "fr")
@Preview(name = "3d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE_LANDSCAPE, locale = "lb")
@Composable
private fun PlayerErrorLanguagesPreview() = PlayerErrorPreview()
