package io.github.beal1031.fin2go.data.media

/** A movie, series or episode, reduced to what the app shows. */
data class MediaItem(
    val id: String,
    /** Movie or series name. For an episode: the name of its series. */
    val title: String,
    /** Only set for episodes: the series they belong to. */
    val seriesId: String? = null,
    /** Only set for episodes. */
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val imageUrl: String? = null,
    /** How much was already watched, from 0 to 1. Null when not started. */
    val progress: Float? = null,
    /** Fully watched. For a series: all episodes watched. */
    val watched: Boolean = false,
    /** Downloaded to this phone. Only movies and episodes can be. */
    val downloaded: Boolean = false,
)

/** The same items, each marked whether it is in [downloadedIds]. */
fun List<MediaItem>.withDownloads(downloadedIds: Set<String>): List<MediaItem> =
    map { it.copy(downloaded = it.id in downloadedIds) }
