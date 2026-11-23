package io.github.beal1031.fin2go.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.beal1031.fin2go.R
import io.github.beal1031.fin2go.ui.PREVIEW_PHONE
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/** Fills the free space and centers its content, e.g. a loading circle, an error or an empty hint. */
@Composable
fun CenteredBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

// --- Preview in phone format ---

@Preview(name = "1 Error with retry", showSystemUi = true, device = PREVIEW_PHONE)
@Composable
private fun CenteredBoxPreview() {
    Fin2goTheme {
        // Same Scaffold as in MainActivity, so status bar and camera cutout are respected.
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
                CenteredBox {
                    Text(stringResource(R.string.load_failed), textAlign = TextAlign.Center)
                    Button(onClick = {}) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}
