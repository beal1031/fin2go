package io.github.beal1031.fin2go

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import io.github.beal1031.fin2go.ui.AppNavigation
import io.github.beal1031.fin2go.ui.withoutTop
import io.github.beal1031.fin2go.ui.login.LoginScreen
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light icons in the status and navigation bar, because the app is always dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
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
                        // After a new login the navigation starts again from home, with fresh
                        // ViewModels, so nothing of the previous account remains.
                        // The status bar space is left to AppNavigation, so pictures can run behind the status bar.
                        is AppState.SignedIn -> AppNavigation(
                            userName = current.account.userName,
                            profileImageUrl = current.profileImageUrl,
                            onSignOut = appViewModel::signOut,
                            statusBarHeight = innerPadding.calculateTopPadding(),
                            modifier = Modifier
                                .padding(innerPadding.withoutTop())
                                .consumeWindowInsets(innerPadding),
                        )
                    }
                }
            }
        }
    }
}
