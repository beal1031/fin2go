package io.github.beal1031.fin2go.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.media.MediaItem
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/**
 * Wide tile (16:9, with progress bar) or poster tile (2:3), with the title below.
 * The width comes from [modifier]: fixed in a row, the cell width in a grid.
 */
@Composable
fun MediaTile(item: MediaItem, wide: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                .clip(MaterialTheme.shapes.medium)
                // Grey area while the picture loads or when there is none.
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Only the watched part is drawn, from the left edge; nothing for unwatched items.
            item.progress?.let { progress ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            // Fully watched: a round check mark in the bottom right corner instead of a full bar.
            if (item.watched) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.watched),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(3.dp),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.seasonNumber != null && item.episodeNumber != null) {
            Text(
                text = stringResource(R.string.episode_number, item.seasonNumber, item.episodeNumber),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Previews: one per state of the tile, in phone format ---

@Composable
private fun MediaTilePreview(item: MediaItem, wide: Boolean = false) {
    Fin2goTheme {
        // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).padding(16.dp)) {
                MediaTile(item = item, wide = wide, modifier = Modifier.width(if (wide) 240.dp else 120.dp))
            }
        }
    }
}

@Preview(name = "1 Poster, not started", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileNotStartedPreview() = MediaTilePreview(MediaItem(id = "1", title = "Example Movie"))

@Preview(name = "2 Poster, watched", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileWatchedPreview() = MediaTilePreview(MediaItem(id = "1", title = "Example Movie", watched = true))

@Preview(name = "3 Wide, started", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileStartedPreview() =
    MediaTilePreview(MediaItem(id = "1", title = "Example Movie", progress = 0.4f), wide = true)

@Preview(name = "4 Wide, episode", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileEpisodePreview() = MediaTilePreview(
    MediaItem(id = "1", title = "Example Series", seasonNumber = 1, episodeNumber = 3, progress = 0.4f),
    wide = true,
)
