package io.github.beal1031.fin2go.data.download

import kotlinx.serialization.Serializable

/**
 * A movie or episode that is completely downloaded. The folder of the download also holds `item.json`
 * (everything the server knows about the item) and its pictures, for showing it without internet later.
 */
@Serializable
data class Download(
    val itemId: String,
    /** The account it was downloaded with. */
    val serverId: String,
    val userId: String,
    /** Only set for episodes. */
    val seriesId: String? = null,
    val seasonId: String? = null,
    /** Name of the video file inside the folder, e.g. "video.mkv". */
    val fileName: String,
    val sizeBytes: Long,
)

/** How far the download of one movie or episode is. */
sealed interface DownloadState {
    data object NotDownloaded : DownloadState

    /** Waiting to start, e.g. for Wi-Fi. */
    data object Waiting : DownloadState

    /** [progress] from 0 to 1; null when the server does not say how big the file is. */
    data class Downloading(val progress: Float?) : DownloadState

    data object Downloaded : DownloadState
}
