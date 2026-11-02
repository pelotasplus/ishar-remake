package dev.pelotasplus.ishar.features.game

import androidx.compose.ui.input.key.Key
import dev.pelotasplus.ishar.engine.Input

private val LETTERS = listOf(
    Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
)
private val DIGITS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val NUMPAD = listOf(
    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
    Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
)
private val FKEYS = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10)

/**
 * A host key as the PC scancode the game expects. Digits on either row go to the keypad,
 * which is what the game's menus answer to (its own table gives the top row AZERTY symbols).
 */
fun scancodeOf(key: Key): Int? = when (key) {
    Key.DirectionUp -> Input.UP
    Key.DirectionDown -> Input.DOWN
    Key.DirectionLeft -> Input.LEFT
    Key.DirectionRight -> Input.RIGHT
    Key.Enter, Key.NumPadEnter -> Input.ENTER
    Key.Escape -> Input.ESC
    Key.Spacebar -> Input.SPACE
    Key.Backspace -> Input.BACKSPACE
    Key.Tab -> Input.TAB
    Key.ShiftLeft -> Input.LEFT_SHIFT
    Key.ShiftRight -> Input.RIGHT_SHIFT
    Key.CtrlLeft, Key.CtrlRight -> Input.CTRL
    Key.AltLeft, Key.AltRight -> Input.ALT
    in FKEYS -> Input.F1 + FKEYS.indexOf(key)
    in DIGITS -> Input.KEYPAD_DIGITS[DIGITS.indexOf(key)]
    in NUMPAD -> Input.KEYPAD_DIGITS[NUMPAD.indexOf(key)]
    in LETTERS -> Input.scancodeFor('a' + LETTERS.indexOf(key))
    else -> null
}
