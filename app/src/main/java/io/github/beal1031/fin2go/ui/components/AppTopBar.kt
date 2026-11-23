package io.github.beal1031.fin2go.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/** The bar at the top of the app: "Fin2Go" on the left, the round profile picture with its menu on the right. */
@Composable
fun AppTopBar(
    userName: String,
    profileImageUrl: String?,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )

        // Only the UI needs to know whether the menu is open, so it is kept here and not in a ViewModel.
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            ProfileImage(
                userName = userName,
                imageUrl = profileImageUrl,
                // indication = null: no ripple animation on tap.
                modifier = Modifier.clickable(interactionSource = null, indication = null) { menuOpen = true },
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                // A little space between the picture and the menu.
                offset = DpOffset(x = 0.dp, y = 8.dp),
            ) {
                ProfileMenuItems(onSignOut = onSignOut, onClose = { menuOpen = false })
            }
        }
    }
}

/** The entries of the profile menu. Separate, so the previews can show them. */
@Composable
private fun ProfileMenuItems(onSignOut: () -> Unit, onClose: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.sign_out)) },
        onClick = {
            onClose()
            onSignOut()
        },
    )
}

/** Round picture. The first letter of the name is behind it and stays visible when there is no picture. */
@Composable
private fun ProfileImage(userName: String, imageUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = userName.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        AsyncImage(
            model = imageUrl,
            contentDescription = stringResource(R.string.profile_menu),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// --- Previews: one per state of the bar, in phone format ---

/**
 * Previews cannot load pictures from the server. With [withPicture], a coloured area
 * stands in for the profile picture; without it, the first letter is visible.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
private fun AppTopBarPreview(withPicture: Boolean, menuOpen: Boolean = false) {
    Fin2goTheme {
        val pictureColor = MaterialTheme.colorScheme.tertiary.toArgb()
        val previewHandler = AsyncImagePreviewHandler { ColorImage(pictureColor) }
        CompositionLocalProvider(LocalAsyncImagePreviewHandler provides previewHandler) {
            // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                Column(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
                    AppTopBar(
                        userName = "sen",
                        profileImageUrl = if (withPicture) "https://example.com/user.jpg" else null,
                        onSignOut = {},
                    )
                    // The real menu is a popup, which the static preview does not draw.
                    // So the same entries are drawn here in a box that looks like the menu.
                    if (menuOpen) {
                        Box(modifier = Modifier.fillMaxWidth().padding(end = 16.dp), contentAlignment = Alignment.TopEnd) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                tonalElevation = 3.dp,
                                shadowElevation = 3.dp,
                            ) {
                                Column(modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp)) {
                                    ProfileMenuItems(onSignOut = {}, onClose = {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "1 Without profile picture", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun AppTopBarInitialPreview() = AppTopBarPreview(withPicture = false)

@Preview(name = "2 With profile picture", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun AppTopBarPicturePreview() = AppTopBarPreview(withPicture = true)

@Preview(name = "3 Menu open", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun AppTopBarMenuPreview() = AppTopBarPreview(withPicture = true, menuOpen = true)

@Preview(name = "3b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "3c French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "3d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun AppTopBarLanguagesPreview() = AppTopBarPreview(withPicture = true, menuOpen = true)
