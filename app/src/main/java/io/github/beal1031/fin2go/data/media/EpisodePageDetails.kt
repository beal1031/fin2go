package io.github.beal1031.fin2go.data.media

/** Everything the episode page shows: the episode itself and a little of its series. */
data class EpisodePageDetails(
    val episode: EpisodeDetails,
    /** Name of the series, shown small above the episode title. Null when the server does not send it. */
    val seriesTitle: String? = null,
    /** Wide picture (16:9) for the top of the page: the episode's own, else the backdrop of the series. */
    val backdropUrl: String? = null,
    /** Portrait poster (2:3) of the series, used blurred when there is no wide picture. */
    val posterUrl: String? = null,
)
