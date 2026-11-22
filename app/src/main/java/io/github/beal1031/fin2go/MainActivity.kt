package io.github.beal1031.fin2go

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.beal1031.fin2go.ui.theme.Fin2goTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Fin2goTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ServerScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun ServerScreen(modifier: Modifier = Modifier) {
    var address by remember { mutableStateOf("") }

    Column(modifier = modifier.padding(16.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Server-Adresse") }
        )
        Button(onClick = { /* kommt im nächsten Schritt */ }) {
            Text("Verbinden")
        }
        Text("Eingegeben: $address")
    }
}

@Preview(showBackground = true)
@Composable
fun ServerScreenPreview() {
    Fin2goTheme {
        ServerScreen()
    }
}