package io.github.beal1031.fin2go.data.media

/** Everything the detail page shows about one series. Every part except the title can be missing on the server. */
data class SeriesDetails(
    val id: String,
    val title: String,
    /** e.g. "2010–2015", "2010–" while it still runs, or "2010". */
    val years: String? = null,
    /** Age rating as the server knows it, e.g. "FSK-12" or "TV-14". */
    val ageRating: String? = null,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    /** Wide picture (16:9) for the top of the page. */
    val backdropUrl: String? = null,
    /** Portrait poster (2:3), used blurred when there is no backdrop. */
    val posterUrl: String? = null,
    /** All episodes in watching order: season 1, 2, … and the specials (season 0) last. */
    val episodes: List<EpisodeDetails> = emptyList(),
    /** The episode the user is at, chosen when the page opens. Null when there are no episodes. */
    val currentEpisodeId: String? = null,
) {
    /** Season numbers in the order of [episodes]; 0 stands for the specials. */
    val seasons: List<Int> get() = episodes.map { it.seasonNumber }.distinct()

    fun episodesOf(season: Int): List<EpisodeDetails> = episodes.filter { it.seasonNumber == season }

    /**
     * The episodes of [season] that really change when the season is marked as [watched] (or not watched):
     * those not in that state yet, and started ones, whose position is removed.
     */
    fun changingEpisodes(season: Int, watched: Boolean): List<EpisodeDetails> =
        episodesOf(season).filter { it.watched != watched || it.progress != null }

    val currentEpisode: EpisodeDetails? get() = episodes.firstOrNull { it.id == currentEpisodeId }

    /** At least one episode watched or started. */
    val started: Boolean get() = episodes.any { it.watched || it.progress != null }

    /**
     * All episodes watched. Specials do not count, many people never watch them;
     * only a series that has nothing but specials needs those watched.
     */
    val allWatched: Boolean
        get() {
            val regular = episodes.filter { it.seasonNumber != 0 }.ifEmpty { episodes }
            return regular.isNotEmpty() && regular.all { it.watched }
        }
}

/** One episode, as the series page shows it. */
data class EpisodeDetails(
    val id: String,
    /** 0 for specials. */
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    /** Running time, rounded to full minutes. */
    val runtimeMinutes: Int? = null,
    val overview: String? = null,
    /** Wide picture (16:9): the episode's own, else one of the series. */
    val imageUrl: String? = null,
    /** How much was already watched, from 0 to 1. Null when not started or fully watched. */
    val progress: Float? = null,
    /** Minutes still to watch when started, rounded, at least 1. */
    val remainingMinutes: Int? = null,
    /** Fully watched. Watched and started again counts as started. */
    val watched: Boolean = false,
    /** Downloaded to this phone. */
    val downloaded: Boolean = false,
    /** Size of the video file, for the question before a whole season is downloaded. */
    val sizeBytes: Long? = null,
)
