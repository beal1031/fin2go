package io.github.beal1031.fin2go.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.beal1031.fin2go.data.account.Account
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/** Placeholder after login until the home screen exists. */
@Composable
fun SignedInScreen(
    account: Account,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Angemeldet als ${account.userName} auf ${account.serverName}")
        Button(onClick = onSignOut) {
            Text("Abmelden")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SignedInScreenPreview() {
    Fin2goTheme {
        SignedInScreen(
            account = Account(
                serverId = "1",
                serverUrl = "http://192.168.1.10:8096",
                serverName = "Mein Server",
                userId = "2",
                userName = "sen",
                accessToken = "token",
            ),
            onSignOut = {},
        )
    }
}
