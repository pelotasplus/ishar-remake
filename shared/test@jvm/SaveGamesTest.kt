package dev.pelotasplus.ishar.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Every jump-point save loads from play through the disk menu: the party ends where the file says. */
class SaveGamesTest {

    @Test
    fun `jump-point saves load through the disk menu`() = runTest {
        val files = Play.files()
        val bad = TestResources.SAVES.mapNotNull { file ->
            val name = file.substringBefore('.')
            val got = Play.position(Play.loaded(files.fresh(), name).first.machine)
            val want = SaveGames.header(files.read(file))
            if (got != want) "$name: $got, want $want" else null
        }
        assertEquals(emptyList(), bad)
    }

    /** The browser reads the file; the live game after loading it must agree. */
    @Test
    fun `a summary says what the loaded game has`() = runTest {
        val files = Play.files()
        val bad = TestResources.SAVES.mapNotNull { file ->
            val name = file.substringBefore('.')
            val s = assertNotNull(SaveGames.summary(name, files.read(file)))
            val m = Play.loaded(files.fresh(), name).first.machine
            val live = Cheats.party(m)
            val region = m.rw(0x126B, 0x3EAC + 0x2A0).toShort().toInt()
            // life may tick by one in the frames after the load (Q: 1000 -> 999)
            val want = live.map { Triple(it.name, it.life, it.maxLife) }
            val got = s.party.zip(live) { m, l -> Triple(m.name, if (m.life - l.life in 0..1) l.life else m.life, m.maxLife) }
            if (got != want || s.gold != live.sumOf { it.gold } || s.region != region) "$name: $got ${s.gold} ${s.region}, want $want ${live.sumOf { it.gold }} $region" else null
        }
        assertEquals(emptyList(), bad)
        assertEquals("ANGARAHN", SaveGames.summary("LR", files.read("LR.SAV"))!!.regionName)
    }

    @Test
    fun `only names the prompt can type are typeable`() {
        listOf("A", "LR", "T1", "ABCDEFGH", "12345678").forEach { assertTrue(SaveGames.typeable(it), it) }
        listOf("", "MY-SAVE", "A B", "ABCDEFGHI", "lr").forEach { assertTrue(!SaveGames.typeable(it), it) }
    }

    /** A party saved with a member just below 0: the browser shows -5, not 65531. */
    @Test
    fun `a summary reads life as signed`() = runTest {
        val save = TestResources.save("LR.SAV").copyOf()
        val before = SaveGames.summary("LR", save)!!.party.first()
        assertTrue(before.life > 0)
        save[468] = 0xFF.toByte()
        save[469] = 0xFB.toByte()
        assertEquals(-5, SaveGames.summary("LR", save)!!.party.first().life)
    }

    @Test
    fun `a file of the wrong size has no summary`() {
        assertNull(SaveGames.summary("X", ByteArray(10)))
    }
}
