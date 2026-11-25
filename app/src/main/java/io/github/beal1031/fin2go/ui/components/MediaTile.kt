package io.github.beal1031.fin2go.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import io.github.beal1031.fin2go.ui.PreviewImage
import io.github.beal1031.fin2go.ui.WithPreviewImages
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/**
 * Wide tile (16:9, with progress bar) or poster tile (2:3), with the title below.
 * The width comes from [modifier]: fixed in a row, the cell width in a grid.
 * Without [onClick] and [onLongClick], the tile does not react to touch.
 * A downloaded movie or episode gets a sign: on a poster in the bottom left corner (the watched check mark is on
 * the right), on a wide tile next to the episode number, or next to the title when there is none.
 */
@Composable
fun MediaTile(
    item: MediaItem,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.then(
            if (onClick != null || onLongClick != null) {
                Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
            } else {
                Modifier
            }
        ),
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
            if (item.downloaded && !wide) {
                Icon(
                    painter = painterResource(R.drawable.ic_download_done),
                    contentDescription = stringResource(R.string.download_done),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        // Above the progress bar, when there is one.
                        .padding(start = 6.dp, end = 6.dp, bottom = if (item.progress != null) 14.dp else 6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(3.dp),
                )
            }
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
        val isEpisode = item.seasonNumber != null && item.episodeNumber != null
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (item.downloaded && wide && !isEpisode) DownloadedIcon()
        }
        if (item.seasonNumber != null && item.episodeNumber != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.episode_number, item.seasonNumber, item.episodeNumber),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.downloaded && wide) DownloadedIcon()
            }
        }
    }
}

// --- Previews: one per state of the tile, in phone format ---

@Composable
private fun MediaTilePreview(item: MediaItem, wide: Boolean = false) {
    Fin2goTheme {
        WithPreviewImages {
            // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).padding(16.dp)) {
                    MediaTile(item = item, wide = wide, modifier = Modifier.width(if (wide) 240.dp else 120.dp))
                }
            }
        }
    }
}

@Preview(name = "1 Poster, not started", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileNotStartedPreview() = MediaTilePreview(
    MediaItem(id = "1", title = "Sintel", imageUrl = PreviewImage.POSTER_SINTEL.url),
)

@Preview(name = "2 Poster, watched", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileWatchedPreview() = MediaTilePreview(
    MediaItem(id = "1", title = "Sintel", imageUrl = PreviewImage.POSTER_SINTEL.url, watched = true),
)

@Preview(name = "3 Wide, started", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileStartedPreview() = MediaTilePreview(
    MediaItem(id = "1", title = "Tears of Steel", imageUrl = PreviewImage.BACKDROP_TEARS_OF_STEEL.url, progress = 0.4f),
    wide = true,
)

@Preview(name = "4 Wide, episode", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileEpisodePreview() = MediaTilePreview(
    MediaItem(
        id = "1",
        title = "Caminandes",
        seasonNumber = 1,
        episodeNumber = 3,
        imageUrl = PreviewImage.EPISODE_CAMINANDES_3.url,
        progress = 0.4f,
    ),
    wide = true,
)

@Preview(name = "5 Poster, no picture", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileNoPicturePreview() = MediaTilePreview(MediaItem(id = "1", title = "Sintel"))

@Preview(name = "6 Poster, downloaded", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileDownloadedPreview() = MediaTilePreview(
    MediaItem(id = "1", title = "Sintel", imageUrl = PreviewImage.POSTER_SINTEL.url, downloaded = true),
)

@Preview(name = "6b Poster, downloaded and watched", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileDownloadedWatchedPreview() = MediaTilePreview(
    MediaItem(id = "1", title = "Sintel", imageUrl = PreviewImage.POSTER_SINTEL.url, watched = true, downloaded = true),
)

@Preview(name = "6c Poster, downloaded and started", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileDownloadedStartedPreview() = MediaTilePreview(
    MediaItem(id = "1", title = "Sintel", imageUrl = PreviewImage.POSTER_SINTEL.url, progress = 0.4f, downloaded = true),
)

@Preview(name = "6d Wide, downloaded episode", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileDownloadedEpisodePreview() = MediaTilePreview(
    MediaItem(
        id = "1",
        title = "Caminandes",
        seasonNumber = 1,
        episodeNumber = 3,
        imageUrl = PreviewImage.EPISODE_CAMINANDES_3.url,
        progress = 0.4f,
        downloaded = true,
    ),
    wide = true,
)

@Preview(name = "6e Wide, downloaded movie", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun MediaTileDownloadedWideMoviePreview() = MediaTilePreview(
    MediaItem(
        id = "1",
        title = "Tears of Steel",
        imageUrl = PreviewImage.BACKDROP_TEARS_OF_STEEL.url,
        progress = 0.4f,
        downloaded = true,
    ),
    wide = true,
)
