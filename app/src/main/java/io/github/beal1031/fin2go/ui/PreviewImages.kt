package io.github.beal1031.fin2go.ui

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler

/**
 * Pictures for previews, which cannot load images from a server.
 * Each one is cut to the format Jellyfin delivers: poster 2:3, backdrop and episode picture 16:9, profile picture 1:1.
 * Sample data uses [url] as image URL; [WithPreviewImages] then shows the matching picture.
 *
 * The files are in app/src/debug/res/drawable-nodpi, named "preview_" + the entry name in lower case.
 * Being in the debug folder, they are not part of the release app.
 *
 * All pictures are from the open movies of the Blender Foundation (blender.org), taken from Wikimedia Commons
 * (commons.wikimedia.org, file name in each comment), and are only cut and scaled down.
 * Licenses: CC BY 3.0 (creativecommons.org/licenses/by/3.0) or CC BY 4.0 (creativecommons.org/licenses/by/4.0).
 */
enum class PreviewImage {
    /** "Big buck bunny poster big.jpg", Blender Foundation, CC BY 3.0 */
    POSTER_BIG_BUCK_BUNNY,

    /** "Sintel poster.jpg", Blender Foundation, CC BY 3.0 */
    POSTER_SINTEL,

    /** "Tos-poster.png", Blender Foundation, CC BY 3.0 */
    POSTER_TEARS_OF_STEEL,

    /** "ElephantsDreamPoster.jpg", Bassam Kurdali, Andy Goralczyk and the Blender Foundation, CC BY 4.0 */
    POSTER_ELEPHANTS_DREAM,

    /** "CosmosLaundromatPoster.jpg", Andy Goralczyk and the Blender Foundation, CC BY 4.0 */
    POSTER_COSMOS_LAUNDROMAT,

    /** "Sprite Fright-movie poster.jpg", Blender Foundation, CC BY 4.0 */
    POSTER_SPRITE_FRIGHT,

    /** "Spring2019AlphaPosterBlender.jpg", Blender Foundation, CC BY 4.0 */
    POSTER_SPRING,

    /** "Blender Foundation - Caminandes - Episode 2 - Gran Dillama - Cover thumbnail.png", Blender Foundation, CC BY 3.0 */
    POSTER_CAMINANDES,

    /** "Tears of Steel frame 01 2a.jpg", Blender Foundation, CC BY 3.0 */
    BACKDROP_TEARS_OF_STEEL,

    /** "CosmosLaundromatYouTubeThumbnail.jpg", Blender Foundation, CC BY 3.0 */
    BACKDROP_COSMOS_LAUNDROMAT,

    /** "Blender Foundation - Caminandes - Episode 2 - Gran Dillama - Cover thumbnail.png", Blender Foundation, CC BY 3.0 */
    BACKDROP_CAMINANDES,

    /** "Pablo Vazquez - Caminandes - Episode 1 - Llama Drama - Cover thumbnail.png", Pablo Vazquez, CC BY 3.0 */
    EPISODE_CAMINANDES_1,

    /** "Blender Foundation - Caminandes - Episode 2 - Gran Dillama - Cover thumbnail.png", Blender Foundation, CC BY 3.0 */
    EPISODE_CAMINANDES_2,

    /** "Blender Foundation - Caminandes - Episode 3 - Llamigos - Cover thumbnail.png", Blender Foundation, CC BY 4.0 */
    EPISODE_CAMINANDES_3,

    /** "Blender Foundation - Caminandes - Episode 2 - Gran Dillama - Cover thumbnail.png", Blender Foundation, CC BY 3.0 */
    PROFILE,
    ;

    /** Stands in for the image URL in sample data. */
    val url: String get() = "preview://$name"
}

/** Shows the [PreviewImage] whose [PreviewImage.url] an image is loaded from; other URLs stay empty. */
@OptIn(ExperimentalCoilApi::class)
@Composable
fun WithPreviewImages(content: @Composable () -> Unit) {
    val handler = AsyncImagePreviewHandler { request ->
        val context = request.context
        val image = PreviewImage.entries.find { it.url == request.data }?.let { entry ->
            // Looked up by name, because the files only exist in debug builds.
            @SuppressLint("DiscouragedApi")
            val id = context.resources.getIdentifier("preview_${entry.name.lowercase()}", "drawable", context.packageName)
            if (id != 0) ContextCompat.getDrawable(context, id)?.asImage() else null
        }
        // Fully transparent, so the grey area behind it stays visible.
        image ?: ColorImage(0)
    }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides handler, content = content)
}
