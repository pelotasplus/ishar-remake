package dev.pelotasplus.ishar.features.main_debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import dev.pelotasplus.ishar.Game
import dev.pelotasplus.ishar.engine.Cheats
import dev.pelotasplus.ishar.engine.GameBuild
import androidx.compose.material3.FilterChip
import dev.pelotasplus.ishar.Saves

@Composable
fun DebugMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDestinationClick: (NavKey) -> Unit,
    freeSaves: Boolean,
    onFreeSavesChange: (Boolean) -> Unit,
    onNewGame: () -> Unit,
    onHealAll: () -> Unit,
    onAddGold: () -> Unit,
    onRemoveCurses: () -> Unit,
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    musicOn: Boolean,
    onMusicOnChange: (Boolean) -> Unit,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
    position: Cheats.Position?,
    build: GameBuild = GameBuild.ISHAR1,
    onSelectGame: (GameBuild) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ishar1 = build == GameBuild.ISHAR1
    Box(modifier = modifier.fillMaxSize()) {
        if (!expanded) {
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = { onExpandedChange(true) }) {
                    Text("Debug")
                }
                position?.takeIf { it.contree != 0 }?.let {
                    Text(
                        "c${it.contree} X${it.x} Y${it.y}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (paused) {
                    Text(
                        "PAUSED",
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .width(210.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = { onExpandedChange(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Close")
                }

                Button(
                    onClick = {
                        onDestinationClick(Game)
                        onExpandedChange(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Game")
                }

                // The game: picking the other one starts a new game of it
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    for (b in GameBuild.entries) {
                        FilterChip(
                            selected = b == build,
                            onClick = {
                                if (b != build) {
                                    onSelectGame(b)
                                    onDestinationClick(Game)
                                    onExpandedChange(false)
                                }
                            },
                            label = { Text(b.short) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Button(
                    onClick = {
                        onNewGame()
                        onDestinationClick(Game)
                        onExpandedChange(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("New game")
                }

                Button(
                    onClick = {
                        onDestinationClick(Saves)
                        onExpandedChange(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Saves")
                }

                Button(
                    onClick = {
                        onHealAll()
                        onExpandedChange(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Heal all")
                }

                Button(
                    onClick = {
                        onRemoveCurses()
                        onExpandedChange(false)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove curses")
                }

                Button(
                    onClick = onAddGold,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add gold")
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Sound", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = !muted, onCheckedChange = { onMutedChange(!it) })
                }
                if (!ishar1) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Music", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = musicOn, onCheckedChange = onMusicOnChange)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Pause", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = paused, onCheckedChange = onPausedChange)
                }

                if (ishar1) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Free saves (no 1000 GP fee)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = freeSaves, onCheckedChange = onFreeSavesChange)
                }
            }
        }
    }
}
