package dev.pelotasplus.ishar.features.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pelotasplus.ishar.engine.GameBuild
import dev.pelotasplus.ishar.engine.SaveGames

/** The player's saves, where each party stands and who is in it; a tap loads it. */
@Composable
fun SavesScreen(viewModel: GameViewModel, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    var deleting by remember { mutableStateOf<String?>(null) }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete $name?") },
            text = { Text("The save and its map are gone for good.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSave(name)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).padding(end = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.build != GameBuild.ISHAR1) {
            Ishar2Saves(
                state.build,
                state.ishar2Saves,
                state.importable,
                onLoad = { name ->
                    viewModel.loadSave(name)
                    onPlay()
                },
                onDelete = { deleting = it },
            )
            return@Column
        }
        Text(if (state.saves.isEmpty()) "No saves yet" else "Saves", style = MaterialTheme.typography.titleMedium)
        for (save in state.saveSummaries) {
            SaveRow(save, onDelete = { deleting = save.name }) {
                viewModel.loadSave(save.name)
                onPlay()
            }
        }
        for (name in state.unloadableSaves) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text("Cannot be loaded", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { deleting = name }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun SaveRow(save: SaveGames.Summary, onDelete: () -> Unit, onLoad: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onLoad)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(save.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            Text(
                SaveGames.summaryLine(save),
                style = MaterialTheme.typography.bodySmall,
            )
            for (m in save.party) {
                Text(
                    "${m.name.padEnd(8)} L${m.level}  life ${m.life}/${m.maxLife}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("${save.gold} gold", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Ishar 2's saves: a tap loads one (through its disk menu's LOAD); Ishar 1 parties come in by its I -> II button. */
@Composable
private fun Ishar2Saves(
    build: GameBuild,
    saves: List<Pair<String, SaveGames.Header?>>,
    importable: List<String>,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Text(if (saves.isEmpty()) "No ${build.short} saves yet" else "${build.short} saves", style = MaterialTheme.typography.titleMedium)
    for ((name, h) in saves) {
        val loadable = h != null && SaveGames.typeable(name)
        Card(Modifier.fillMaxWidth().then(if (loadable) Modifier.clickable { onLoad(name) } else Modifier)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        SaveGames.headerLine(build, h),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { onDelete(name) }) { Text("Delete") }
            }
        }
    }
    if (build == GameBuild.ISHAR3) {
        Text("Ishar 1 and 2 parties to import", style = MaterialTheme.typography.titleMedium)
        Text("In the start menu: Load game, then Ishar 1 to Ishar 3 or Ishar 2 to Ishar 3, and type the save's name.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("Ishar 1 parties to import", style = MaterialTheme.typography.titleMedium)
    Text(
        if (importable.isEmpty()) "No Ishar 1 saves." else "In the game: disk icon, the I -> II button, then type one of: " + importable.joinToString(", "),
        style = MaterialTheme.typography.bodySmall,
    )
}
