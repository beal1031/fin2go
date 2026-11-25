package io.github.beal1031.fin2go.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.data.download.DownloadState
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/**
 * Round button for downloading a movie or episode, next to the watched button.
 * Not downloaded: arrow, a tap starts the download. Waiting or loading: a circle with the progress,
 * a tap asks whether to cancel. Downloaded: filled, a tap asks whether to delete it.
 * [name] is shown in the delete question. [confirmingInitially] opens the question at once, for the previews.
 */
@Composable
fun DownloadButton(
    state: DownloadState,
    name: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    confirmingInitially: Boolean = false,
) {
    var confirming by rememberSaveable { mutableStateOf(confirmingInitially) }
    val download = rememberNotificationPermission(onDownload)

    DownloadIconButton(
        state = state,
        onClick = { if (state == DownloadState.NotDownloaded) download() else confirming = true },
        modifier = modifier,
    )

    if (confirming) {
        when (state) {
            DownloadState.Waiting, is DownloadState.Downloading -> ConfirmDialog(
                title = stringResource(R.string.download_cancel_title),
                text = stringResource(R.string.download_cancel_text),
                confirm = stringResource(R.string.download_cancel_confirm),
                dismiss = stringResource(R.string.download_cancel_keep),
                onConfirm = { confirming = false; onCancel() },
                onDismiss = { confirming = false },
            )

            DownloadState.Downloaded -> ConfirmDialog(
                title = stringResource(R.string.download_delete_title),
                text = stringResource(R.string.download_delete_text, name),
                confirm = stringResource(R.string.download_delete_confirm),
                dismiss = stringResource(R.string.cancel),
                onConfirm = { confirming = false; onDelete() },
                onDismiss = { confirming = false },
            )

            DownloadState.NotDownloaded -> Unit
        }
    }
    // The download was removed while the question was open, e.g. it failed.
    LaunchedEffect(state) { if (state == DownloadState.NotDownloaded) confirming = false }
}

/**
 * Only the look of the download button for [state]: arrow, circle with the progress, or filled when downloaded.
 * The series page uses it for a whole season, with its own questions.
 */
@Composable
fun DownloadIconButton(state: DownloadState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        DownloadState.NotDownloaded -> {
            val description = stringResource(R.string.download_start)
            OutlinedIconButton(onClick = onClick, modifier = modifier) {
                Icon(painter = painterResource(R.drawable.ic_download), contentDescription = description)
            }
        }

        DownloadState.Waiting, is DownloadState.Downloading -> {
            val progress = (state as? DownloadState.Downloading)?.progress
            val description = if (progress == null) {
                stringResource(R.string.download_waiting)
            } else {
                stringResource(R.string.download_running, (progress * 100).toInt())
            }
            OutlinedIconButton(onClick = onClick, modifier = modifier.semantics { contentDescription = description }) {
                val circleModifier = Modifier.size(24.dp)
                if (progress == null) {
                    CircularProgressIndicator(modifier = circleModifier, strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(progress = { progress }, modifier = circleModifier, strokeWidth = 2.dp)
                }
            }
        }

        DownloadState.Downloaded -> {
            val description = stringResource(R.string.download_done)
            FilledIconButton(onClick = onClick, modifier = modifier) {
                Icon(painter = painterResource(R.drawable.ic_download_done), contentDescription = description)
            }
        }
    }
}

/** A question with a confirm and a dismiss button, for the download buttons. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

/**
 * From Android 13 on, the app must ask before it may show notifications. Without them a download still runs,
 * but its progress is not visible outside the app. So the first download asks; whatever the answer, it starts.
 */
@Composable
fun rememberNotificationPermission(onDownload: () -> Unit): () -> Unit {
    // The previews have no activity that could ask.
    if (LocalInspectionMode.current || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return onDownload
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDownload() }
    return { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

// --- Previews: one per state of the button ---

@Composable
private fun DownloadButtonPreview(states: List<DownloadState>, confirming: Boolean = false) {
    Fin2goTheme {
        // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                states.forEach { state ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DownloadButton(
                            state = state,
                            name = "Tears of Steel",
                            onDownload = {},
                            onCancel = {},
                            onDelete = {},
                            confirmingInitially = confirming,
                        )
                        Text(text = state.toString(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private val allStates = listOf(
    DownloadState.NotDownloaded,
    DownloadState.Waiting,
    DownloadState.Downloading(progress = 0.42f),
    DownloadState.Downloaded,
)

@Preview(name = "1 All states", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun DownloadButtonStatesPreview() = DownloadButtonPreview(allStates)

@Preview(name = "2 Cancel question", showSystemUi = true, device = PREVIEW_PHONE)
@Preview(name = "2b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "2c French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "2d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun DownloadButtonCancelPreview() =
    DownloadButtonPreview(listOf(DownloadState.Downloading(progress = 0.42f)), confirming = true)

@Preview(name = "3 Delete question", showSystemUi = true, device = PREVIEW_PHONE)
@Preview(name = "3b German", showSystemUi = true, device = PREVIEW_PHONE, locale = "de")
@Preview(name = "3c French", showSystemUi = true, device = PREVIEW_PHONE, locale = "fr")
@Preview(name = "3d Luxembourgish", showSystemUi = true, device = PREVIEW_PHONE, locale = "lb")
@Composable
private fun DownloadButtonDeletePreview() = DownloadButtonPreview(listOf(DownloadState.Downloaded), confirming = true)

/** Small "downloaded" sign next to a text, e.g. after "S1:E3" in lists. */
@Composable
fun DownloadedIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_download_done),
        contentDescription = stringResource(R.string.download_done),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(16.dp),
    )
}
