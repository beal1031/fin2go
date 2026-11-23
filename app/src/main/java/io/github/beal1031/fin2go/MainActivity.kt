package io.github.beal1031.fin2go

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.beal1031.fin2go.ui.home.HomeScreen
import io.github.beal1031.fin2go.ui.home.HomeViewModel
import io.github.beal1031.fin2go.ui.login.LoginScreen
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Fin2goTheme {
                val appViewModel: AppViewModel = viewModel()
                val state by appViewModel.state.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // consumeWindowInsets: the bars handled here are not added again by imePadding.
                    val contentModifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
                    when (val current = state) {
                        // Shown only for a moment while the saved account is read.
                        AppState.Loading -> Unit
                        AppState.SignedOut -> LoginScreen(modifier = contentModifier)
                        is AppState.SignedIn -> HomeScreen(
                            onSignOut = appViewModel::signOut,
                            modifier = contentModifier,
                            // A new login gets a new token and with it a fresh HomeViewModel,
                            // so no rows of the previous account remain.
                            viewModel = viewModel<HomeViewModel>(key = current.account.accessToken),
                        )
                    }
                }
            }
        }
    }
}
