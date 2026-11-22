package io.github.beal1031.fin2go.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.beal1031.fin2go.R
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
        onStartQuickConnect = viewModel::startQuickConnect,
        onCancelQuickConnect = viewModel::cancelQuickConnect,
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
    onStartQuickConnect: () -> Unit,
    onCancelQuickConnect: () -> Unit,
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
                    label = { Text(stringResource(R.string.login_server_address)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onConnect, enabled = !state.busy) {
                    Text(stringResource(R.string.login_connect))
                }
            }

            state.quickConnectCode != null -> {
                Text(stringResource(R.string.login_quick_connect_instructions))
                Text(
                    text = state.quickConnectCode,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.login_quick_connect_waiting), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancelQuickConnect) {
                    Text(stringResource(R.string.cancel))
                }
            }

            else -> {
                Text(stringResource(R.string.login_connected_to, state.serverName.orEmpty()))
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.login_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onLogin, enabled = !state.busy) {
                    Text(stringResource(R.string.login_sign_in))
                }
                if (state.quickConnectEnabled) {
                    OutlinedButton(onClick = onStartQuickConnect, enabled = !state.busy) {
                        Text(stringResource(R.string.login_quick_connect))
                    }
                }
                TextButton(onClick = onChangeServer) {
                    Text(stringResource(R.string.login_change_server))
                }
            }
        }

        if (state.busy) CircularProgressIndicator()
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
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
            onStartQuickConnect = {},
            onCancelQuickConnect = {},
        )
    }
}

@Preview(name = "1 Address", showBackground = true)
@Composable
private fun LoginAddressPreview() = LoginPreview(LoginState(address = "192.168.1.10:8096"))

@Preview(name = "1b Address with error", showBackground = true)
@Composable
private fun LoginAddressErrorPreview() = LoginPreview(
    LoginState(address = "wrong-address", error = R.string.login_server_not_found)
)

@Preview(name = "2 Username and password", showBackground = true)
@Composable
private fun LoginCredentialsPreview() = LoginPreview(
    LoginState(serverUrl = "http://192.168.1.10:8096", serverName = "My Server", username = "sen")
)

@Preview(name = "2b Signing in", showBackground = true)
@Composable
private fun LoginBusyPreview() = LoginPreview(
    LoginState(serverUrl = "http://192.168.1.10:8096", serverName = "My Server", username = "sen", busy = true)
)

@Preview(name = "2c With Quick Connect button", showBackground = true)
@Composable
private fun LoginQuickConnectAvailablePreview() = LoginPreview(
    LoginState(serverUrl = "http://192.168.1.10:8096", serverName = "My Server", quickConnectEnabled = true)
)

@Preview(name = "2d German", showBackground = true, locale = "de")
@Preview(name = "2e French", showBackground = true, locale = "fr")
@Preview(name = "2f Luxembourgish", showBackground = true, locale = "lb")
@Composable
private fun LoginLanguagesPreview() = LoginPreview(
    LoginState(serverUrl = "http://192.168.1.10:8096", serverName = "My Server", quickConnectEnabled = true)
)

@Preview(name = "3 Quick Connect code", showBackground = true)
@Composable
private fun LoginQuickConnectCodePreview() = LoginPreview(
    LoginState(
        serverUrl = "http://192.168.1.10:8096",
        serverName = "My Server",
        quickConnectEnabled = true,
        quickConnectCode = "482915",
    )
)
