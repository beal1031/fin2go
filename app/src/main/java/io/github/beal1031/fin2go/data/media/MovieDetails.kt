package io.github.beal1031.fin2go.data.media

/** Everything the detail page shows about one movie. Every part except the title can be missing on the server. */
data class MovieDetails(
    val id: String,
    val title: String,
    val year: Int? = null,
    /** Running time, rounded to full minutes. */
    val runtimeMinutes: Int? = null,
    /** Age rating as the server knows it, e.g. "FSK-12" or "PG-13". */
    val ageRating: String? = null,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    /** Wide picture (16:9) for the top of the page. */
    val backdropUrl: String? = null,
    /** Portrait poster (2:3), used blurred when there is no backdrop. */
    val posterUrl: String? = null,
)
