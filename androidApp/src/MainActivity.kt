package dev.pelotasplus.ishar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.pelotasplus.ishar.data.store.androidFilesDir

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        androidFilesDir = filesDir
        androidStartMuted = intent.getBooleanExtra("mute", false)
        // Back on the game leaves the app, never ends it: closing would lose the unsaved game. Compose's own handlers go first.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
