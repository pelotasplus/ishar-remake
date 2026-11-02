package dev.pelotasplus.ishar.features.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.pelotasplus.ishar.engine.GameBuild

/** The start screen: which game to play. The other one is a tap away in the Debug menu. */
@Composable
fun GameSelectScreen(onSelect: (GameBuild) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(Color.Black).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Ishar", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        for (b in GameBuild.entries) {
            Button(onClick = { onSelect(b) }, modifier = Modifier.width(320.dp)) {
                Text(b.title)
            }
        }
    }
}
