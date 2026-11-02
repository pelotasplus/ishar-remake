package dev.pelotasplus.ishar.features.game

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.pelotasplus.ishar.getPlatform
import kotlin.time.TimeSource

/**
 * The game on a 4:3 screen: keyboard and mouse go to the engine. Touch: a tap is the left
 * button, two fingers the right (cancel); at a text prompt the phone's keyboard ([PromptKeyboard]).
 * The [MiniMap] sits in the window's bottom-right corner, in the room the 4:3 screen leaves: a tap
 * doubles it, the next opens [FullMap], and closing that brings back the small one.
 */
@Composable
fun GameScreen(viewModel: GameViewModel, modifier: Modifier = Modifier, menuOpen: Boolean = false) {
    val state by viewModel.state.collectAsState()
    val focus = remember { FocusRequester() }
    val shown = state.frame != null
    LaunchedEffect(shown) { if (shown) focus.requestFocus() }
    val phone = remember { getPlatform().name.let { it.startsWith("Android") || it.startsWith("iOS") || it.startsWith("iPadOS") } }
    val promptKeyboard by rememberUpdatedState(phone && state.namePrompt)
    var keyboardAsks by remember { mutableStateOf(0) }
    LaunchedEffect(state.namePrompt) { if (shown && !state.namePrompt) focus.requestFocus() }
    LaunchedEffect(state.mapOpen) { if (shown && !state.mapOpen) focus.requestFocus() }
    LaunchedEffect(menuOpen) { if (shown && !menuOpen && !state.namePrompt) focus.requestFocus() } // the menu's buttons took it
    val onKey = { e: KeyEvent ->
        val sc = scancodeOf(e.key)
        if (sc != null) {
            viewModel.resumeAudio()
            when (e.type) {
                KeyEventType.KeyDown -> viewModel.keyDown(sc)
                KeyEventType.KeyUp -> viewModel.keyUp(sc)
            }
        }
        sc != null
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        (state.error ?: state.saveError ?: state.stopped ?: state.audioError?.let { "No sound: $it" })?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val frame = state.frame
            if (frame == null) {
                if (state.error == null && state.stopped == null) CircularProgressIndicator()
            } else {
                CrtScreen(
                    argb = frame,
                    modifier = Modifier.aspectRatio(4f / 3f, matchHeightConstraintsFirst = true),
                    imageModifier = Modifier
                        .focusRequester(focus)
                        .onFocusChanged { if (!it.isFocused) viewModel.focusLost() }
                        .focusable()
                        .onKeyEvent(onKey)
                        .pointerInput(Unit) {
                            var held = 0
                            val touches = TouchGesture()
                            val clock = TimeSource.Monotonic.markNow()
                            fun send(b: Int) {
                                if (b == held) return
                                if (b != 0) viewModel.resumeAudio()
                                held = b
                                viewModel.buttons(b)
                                // At a prompt the phone's keyboard keeps the focus, or it goes away (the fee's OK, on the way to the name)
                                if (!promptKeyboard) focus.requestFocus() else if (b != 0) keyboardAsks++
                            }
                            awaitPointerEventScope {
                                while (true) {
                                    val now = clock.elapsedNow().inWholeMilliseconds
                                    val e = if (touches.waiting) {
                                        withTimeoutOrNull(TouchGesture.WINDOW_MS) { awaitPointerEvent() }
                                    } else {
                                        awaitPointerEvent()
                                    }
                                    if (e == null) { // the window ended with the finger still alone
                                        touches.update(1, now + TouchGesture.WINDOW_MS).forEach(::send)
                                        continue
                                    }
                                    val c = e.changes.firstOrNull() ?: continue
                                    viewModel.pointer(
                                        (c.position.x * 320 / size.width).toInt(),
                                        (c.position.y * 200 / size.height).toInt(),
                                        touch = c.type == PointerType.Touch,
                                    )
                                    if (c.type == PointerType.Touch) {
                                        val fingers = e.changes.count { it.type == PointerType.Touch && it.pressed }
                                        touches.update(fingers, clock.elapsedNow().inWholeMilliseconds).forEach(::send)
                                    } else {
                                        send(
                                            when {
                                                e.buttons.isSecondaryPressed -> 2
                                                e.buttons.isPrimaryPressed -> 1
                                                else -> 0
                                            },
                                        )
                                    }
                                }
                            }
                        },
                )
            }
        }
        if (phone && state.namePrompt) PromptKeyboard(asks = keyboardAsks, onKey = viewModel::type)
    }
    var doubled by remember { mutableStateOf(false) }
    val map = state.map
    val position = state.position
    if (map != null && position != null && !state.textPrompt) {
        val gameWidth = min(maxWidth - 24.dp, (maxHeight - 24.dp) * 4f / 3f)
        val room = maxOf((maxWidth - gameWidth) / 2, (maxHeight - gameWidth * 3f / 4f) / 2) - 12.dp
        val side = room.coerceIn(120.dp, 240.dp)
        if (state.mapOpen) {
            FullMap(map, position, state.creatures, state.groundItems, state.visited, onClose = { viewModel.setMapOpen(false) }, onKey = onKey)
        } else {
            MiniMap(
                map, position, state.creatures, state.groundItems, state.visited,
                side = if (doubled) min(side * 2f, min(maxWidth, maxHeight) - 12.dp) else side,
                onOpen = {
                    if (doubled) viewModel.setMapOpen(true)
                    doubled = !doubled
                    focus.requestFocus()
                },
                modifier = Modifier.align(Alignment.BottomEnd).safeContentPadding().padding(6.dp),
            )
        }
    }
    }
}
