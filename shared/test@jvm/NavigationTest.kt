package dev.pelotasplus.ishar

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationTest {
    @Test
    fun `the game is under every other screen so back returns to it`() {
        assertEquals(listOf(Game, Saves), stackFor(Saves))
    }

    @Test
    fun `the game has nothing under it so back leaves the app`() {
        assertEquals(listOf(Game), stackFor(Game))
    }
}
