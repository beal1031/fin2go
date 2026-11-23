package io.github.beal1031.fin2go.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.beal1031.fin2go.R

// Parts that the movie page and the series page share.

/**
 * The wide picture (16:9) over the full width, fading into the background at the bottom.
 * It starts at the very top, behind the status bar and the top bar. It is [statusBarHeight] taller than 16:9,
 * so the part below the status bar still has the full height; the picture is cut a little at the sides for that.
 * Without a backdrop, the poster is shown blurred and darkened instead.
 */
@Composable
fun DetailHeader(backdropUrl: String?, posterUrl: String?, statusBarHeight: Dp) = BoxWithConstraints {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(maxWidth * 9 / 16 + statusBarHeight)
            // Grey area while the picture loads or when there is none.
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (backdropUrl != null) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Blur only works from Android 12 on; older phones show the poster sharp, but darkened.
                modifier = Modifier.fillMaxSize().blur(24.dp),
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
        }
        // Top: slightly darkened, so the back arrow of the bar stays readable on bright pictures.
        // Bottom: fades into the page, so the title below does not start at a hard edge.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.5f),
                        0.35f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to background,
                    ),
                ),
        )
    }
}

/**
 * Depends on how far the movie or episode was watched ([progress] null: not started or fully watched):
 * not started: "Play"; started: progress bar with the time left, "Resume" and "Start over"; watched: "Play again".
 * [canResume] shows "Resume" and "Start over" also without a bar; the series page uses it when the next episode
 * is not started yet. On the right the round watched button, unless [onToggleWatched] is null.
 */
@Composable
fun PlayButtons(
    progress: Float?,
    remainingMinutes: Int?,
    watched: Boolean,
    onPlay: () -> Unit,
    onStartOver: () -> Unit,
    onToggleWatched: (() -> Unit)?,
    modifier: Modifier = Modifier,
    canResume: Boolean = progress != null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (progress != null) {
            // Same look as the bar on the tiles, with a track for the part still to watch.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                remainingMinutes?.let {
                    Text(
                        text = stringResource(R.string.movie_time_left, runtimeText(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canResume) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    PlayButtonContent(R.string.movie_resume)
                }
                OutlinedButton(onClick = onStartOver) {
                    Text(text = stringResource(R.string.movie_start_over))
                }
            } else {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    PlayButtonContent(if (watched) R.string.movie_play_again else R.string.movie_play)
                }
            }
            onToggleWatched?.let { WatchedButton(watched = watched, onClick = it) }
        }
    }
}

/** Round check mark button: filled when watched, only outlined when not. A tap switches between the two. */
@Composable
fun WatchedButton(watched: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(if (watched) R.string.movie_mark_unwatched else R.string.movie_mark_watched)
    val icon = @Composable {
        Icon(painter = painterResource(R.drawable.ic_check), contentDescription = description)
    }
    if (watched) {
        FilledIconButton(onClick = onClick, modifier = modifier, content = icon)
    } else {
        OutlinedIconButton(onClick = onClick, modifier = modifier, content = icon)
    }
}

/** Play triangle and text, inside a button. */
@Composable
private fun PlayButtonContent(@StringRes text: Int) {
    Icon(
        painter = painterResource(R.drawable.ic_play),
        contentDescription = null,
        modifier = Modifier.size(ButtonDefaults.IconSize),
    )
    Text(text = stringResource(text), modifier = Modifier.padding(start = 8.dp))
}

/** e.g. "2 h 12 min", "2 h" or "12 min". */
@Composable
fun runtimeText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.runtime_minutes, rest)
        rest == 0 -> stringResource(R.string.runtime_hours, hours)
        else -> stringResource(R.string.runtime_hours_minutes, hours, rest)
    }
}
