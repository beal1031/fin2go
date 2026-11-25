package io.github.beal1031.fin2go.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/** Whether the server can be reached; set once for all pages in AppNavigation. */
val LocalServerReachable = compositionLocalOf { true }

/**
 * Calls [onChange] each time the app goes offline or comes back online, so a page shows the downloads
 * instead of what it got from the server, or the other way around. Not when the page is first shown.
 */
@Composable
fun OnConnectionChange(onChange: () -> Unit) {
    val reachable = LocalServerReachable.current
    val currentOnChange by rememberUpdatedState(onChange)
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(reachable) {
        if (first) first = false else currentOnChange()
    }
}
