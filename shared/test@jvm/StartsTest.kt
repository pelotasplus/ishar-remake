package dev.pelotasplus.ishar.engine

import kotlinx.coroutines.test.runTest
import dev.pelotasplus.ishar.data.store.MemorySaveStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hard-coded intro skip ends with ARAMIR walking in Fragonir (where s1 was at frame 900). */
class StartsTest {
    @Test
    fun `skip intro lands in Fragonir`() = runTest {
        val m = Starts.skipIntro(Play.files()).machine
        assertEquals(Cheats.Position(11, 29, 2, 1), Cheats.position(m))
        assertEquals(listOf("ARAMIR"), Cheats.party(m).map { it.name })
    }

    /** The intro waits on the language menu; keypad 2 picks FRANCAIS (426f = 0) and the game starts. */
    @Test
    fun `the language menu waits and takes a pick`() = runTest {
        val vm = Starts.toLanguageMenu(Play.files())
        val input = Input(vm).also { it.install() }
        repeat(50) { vm.frame() }
        assertTrue(Starts.languageMenu(vm.machine), "still on the menu")
        input.press(Input.KEYPAD_DIGITS[2])
        repeat(300) { vm.frame() }
        assertEquals(0, vm.machine.rb(0x126B, 0x426F + 0x2A0), "FRANCAIS")
        assertEquals(1, Cheats.position(vm.machine).contree)
        assertFalse(Starts.languageMenu(vm.machine))
    }

    /** In each language's sos*.io: the fee's OK / EXIT is a prompt frame but takes no letters; the name loop does; then it is over. */
    @Test
    fun `the name prompt is told from the fee in every language`() = runTest {
        for ((digit, language) in listOf(1 to 1, 2 to 0, 3 to 2, 4 to 3)) {
            val store = MemorySaveStore()
            val vm = Starts.toLanguageMenu(Play.files(store))
            val input = Input(vm).also { it.install() }
            repeat(50) { vm.frame() }
            input.press(Input.KEYPAD_DIGITS[digit])
            repeat(300) { vm.frame() }
            assertEquals(language, vm.machine.rb(0x126B, 0x426F + 0x2A0))
            val m = vm.machine
            assertFalse(Input.textPrompt(m) && Input.namePrompt(m), "in play")

            val seen = mutableListOf<Pair<Boolean, Boolean>>()
            val at = 1000
            val end = Play.run(vm, SaveGames.saveEvents("IO", at), at, SaveGames.saveFrames("IO")) {
                Cheats.freeSaves(m, true)
                seen += Input.textPrompt(m) to Input.namePrompt(m)
            }
            assertEquals(SaveGames.SIZE, store.read("io.sav")?.size, "language $language saved")
            val feeFirst = seen.indexOfFirst { it.first }
            val nameFirst = seen.indexOfFirst { it.second }
            val nameLast = seen.indexOfLast { it.second }
            assertTrue(feeFirst in 0 until nameFirst, "language $language: the fee comes first, keyboard shut ($feeFirst, $nameFirst)")
            assertTrue(nameLast - nameFirst > 10, "language $language: the name loop lasts ($nameFirst..$nameLast)")
            assertTrue((nameFirst..nameLast).all { seen[it].second }, "language $language: open throughout the name")
            assertTrue(nameLast < seen.lastIndex, "language $language: shut again after it")
            assertFalse(Input.namePrompt(m), "language $language: in play again")
            assertEquals(1000 + SaveGames.saveFrames("IO"), end)
        }
    }

    @Test
    fun `the logo is not the language menu`() = runTest {
        val vm = Starts.SKIP_INTRO.boot(Play.files())
        repeat(50) { vm.frame() }
        assertFalse(Starts.languageMenu(vm.machine))
    }

    @Test
    fun `menu lines map to their digits`() {
        assertEquals(listOf(1, 2, 3, 4, null, null), listOf(
            Starts.languageAt(150, 69), Starts.languageAt(150, 88), Starts.languageAt(150, 108),
            Starts.languageAt(150, 128), Starts.languageAt(20, 88), Starts.languageAt(150, 180),
        ))
    }
}
