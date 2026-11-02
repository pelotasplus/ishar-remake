package dev.pelotasplus.ishar.features.game

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.pelotasplus.ishar.engine.Input

/**
 * The phone's own keyboard while the game asks for text (the save name): a hidden field takes
 * the focus, and what is typed goes to the game as keys (letters, Space, Backspace, Done as Enter).
 */
@Composable
fun PromptKeyboard(asks: Int, onKey: (Int) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(EMPTY) }
    BasicTextField(
        value = value,
        onValueChange = { new ->
            if (new.text.length < SENTINEL.length) onKey(Input.BACKSPACE)
            else new.text.drop(SENTINEL.length).forEach { c -> keyFor(c)?.let(onKey) }
            value = EMPTY
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onKey(Input.ENTER) }),
        singleLine = true,
        modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
    )
    LaunchedEffect(asks) { // and again on a tap, if the player put the keyboard away
        focus.requestFocus()
        keyboard?.show()
    }
}

/** The field keeps one space, so a Backspace on it still shows up as a change. */
private const val SENTINEL = " "
private val EMPTY = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

private fun keyFor(c: Char): Int? = if (c == ' ') Input.SPACE else Input.scancodeFor(c.lowercaseChar())
