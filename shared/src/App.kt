package dev.pelotasplus.ishar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import dev.pelotasplus.ishar.features.game.GameScreen
import dev.pelotasplus.ishar.features.game.GameSelectScreen
import dev.pelotasplus.ishar.features.game.GameViewModel
import dev.pelotasplus.ishar.features.game.SavesScreen
import dev.pelotasplus.ishar.features.main_debug.DebugMenu
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.jetbrains.compose.reload.DevelopmentEntryPoint

/** Navigation 3 destination keys. Serializable so the back stack survives state restoration. */
@Serializable
data object Game : NavKey

@Serializable
data object Saves : NavKey

private val navConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(Game::class, Game.serializer())
            subclass(Saves::class, Saves.serializer())
        }
    }
}

/** The game is always underneath, so Back from any other screen returns to it. */
internal fun stackFor(target: NavKey): List<NavKey> = if (target == Game) listOf(Game) else listOf(Game, target)

@Composable
@Preview
@DevelopmentEntryPoint
fun App(gameViewModel: GameViewModel = viewModel { GameViewModel(build = null) }) {
    LifecycleStartEffect(gameViewModel) {
        gameViewModel.setVisible(true)
        onStopOrDispose { gameViewModel.setVisible(false) }
    }
    LifecycleResumeEffect(gameViewModel) {
        onPauseOrDispose { gameViewModel.focusLost() }
    }
    MaterialTheme {
        val backStack = rememberNavBackStack(navConfig, Game)
        var debugMenuExpanded by remember { mutableStateOf(false) }
        val choosing by remember { gameViewModel.state.map { it.choosing }.distinctUntilChanged() }.collectAsState(gameViewModel.state.value.choosing)
        if (choosing) {
            GameSelectScreen(onSelect = gameViewModel::selectGame, modifier = Modifier.safeContentPadding())
            return@MaterialTheme
        }

        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize(),
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = {
                    if (backStack.size > 1) {
                        backStack.removeLastOrNull()
                    }
                },
                entryProvider = entryProvider {
                    entry<Game> {
                        GameScreen(viewModel = gameViewModel, menuOpen = debugMenuExpanded)
                    }
                    entry<Saves> {
                        SavesScreen(viewModel = gameViewModel, onPlay = {
                            backStack.clear()
                            backStack.add(Game)
                        })
                    }
                },
            )

            // After NavDisplay, so an open menu takes Back before the screen under it
            NavigationBackHandler(
                state = rememberNavigationEventState(NavigationEventInfo.None),
                isBackEnabled = debugMenuExpanded,
                onBackCompleted = { debugMenuExpanded = false },
            )

            val freeSaves by remember { gameViewModel.state.map { it.freeSaves }.distinctUntilChanged() }.collectAsState(false)
            val position by remember { gameViewModel.state.map { it.position }.distinctUntilChanged() }.collectAsState(null)
            val paused by remember { gameViewModel.state.map { it.paused }.distinctUntilChanged() }.collectAsState(false)
            val muted by remember { gameViewModel.state.map { it.muted }.distinctUntilChanged() }.collectAsState(false)
            val musicOn by remember { gameViewModel.state.map { it.musicOn }.distinctUntilChanged() }.collectAsState(true)
            val build by remember { gameViewModel.state.map { it.build }.distinctUntilChanged() }.collectAsState(gameViewModel.state.value.build)
            DebugMenu(
                freeSaves = freeSaves,
                onFreeSavesChange = gameViewModel::setFreeSaves,
                onNewGame = gameViewModel::newGame,
                onHealAll = gameViewModel::healAll,
                onAddGold = gameViewModel::addGold,
                onRemoveCurses = gameViewModel::removeCurses,
                muted = muted,
                onMutedChange = gameViewModel::setMuted,
                musicOn = musicOn,
                onMusicOnChange = gameViewModel::setMusicOn,
                paused = paused,
                position = position,
                onPausedChange = { if (it) gameViewModel.pause() else gameViewModel.resume() },
                expanded = debugMenuExpanded,
                onExpandedChange = { debugMenuExpanded = it },
                build = build,
                onSelectGame = gameViewModel::selectGame,
                onDestinationClick = { target ->
                    if (backStack.lastOrNull() != target) {
                        backStack.clear()
                        backStack.addAll(stackFor(target))
                    }
                },
            )
        }
    }
}
