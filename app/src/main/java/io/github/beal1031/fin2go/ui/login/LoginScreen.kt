package io.github.beal1031.fin2go.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

/** Connects the ViewModel to the UI. Used by the app. */
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = viewModel(),
) {
    // Every change of the state redraws the UI.
    val state by viewModel.state.collectAsState()

    LoginContent(
        state = state,
        onAddressChange = viewModel::onAddressChange,
        onConnect = viewModel::connect,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onLogin = viewModel::login,
        onChangeServer = viewModel::changeServer,
        modifier = modifier,
    )
}

/**
 * The pure UI: it only shows [state] and reports clicks.
 * Because it needs no ViewModel, the previews below can show every state.
 */
@Composable
fun LoginContent(
    state: LoginState,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onChangeServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            state.serverUrl == null -> {
                OutlinedTextField(
                    value = state.address,
                    onValueChange = onAddressChange,
                    label = { Text("Server-Adresse") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onConnect, enabled = !state.busy) {
                    Text("Verbinden")
                }
            }

            else -> {
                Text("Verbunden mit ${state.serverName}")
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = { Text("Benutzername") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text("Passwort") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onLogin, enabled = !state.busy) {
                    Text("Anmelden")
                }
                TextButton(onClick = onChangeServer) {
                    Text("Anderer Server")
                }
            }
        }

        if (state.busy) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

// --- Previews: one per state of the screen ---

@Composable
private fun LoginPreview(state: LoginState) {
    Fin2goTheme {
        LoginContent(
            state = state,
            onAddressChange = {},
            onConnect = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onLogin = {},
            onChangeServer = {},
        )
    }
}

@Preview(name = "1 Adresse", showBackground = true)
@Composable
private fun LoginAddressPreview() = LoginPreview(LoginState(address = "192.168.1.10:8096"))

@Preview(name = "1b Adresse mit Fehler", showBackground = true)
@Composable
private fun LoginAddressErrorPreview() = LoginPreview(
    LoginState(address = "falsch", error = "Kein Jellyfin-Server unter dieser Adresse gefunden.")
)

@Preview(name = "2 Benutzer und Passwort", showBackground = true)
@Composable
private fun LoginCredentialsPreview() = LoginPreview(
    LoginState(serverUrl = "http://192.168.1.10:8096", serverName = "Mein Server", username = "sen")
)

@Preview(name = "2b Anmeldung läuft", showBackground = true)
@Composable
private fun LoginBusyPreview() = LoginPreview(
    LoginState(serverUrl = "http://192.168.1.10:8096", serverName = "Mein Server", username = "sen", busy = true)
)
