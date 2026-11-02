package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObjectsTest {

    @Test
    fun `creates and destroys script frames with memory compaction`() = runTest {
        val repo = TestResources()
        val files = GameFiles.fromResourceRepository(repo)
        val machine = Machine()
        Boot.boot(machine, files)

        Assets.loadAsset(machine, files, 0x40, "logo.io")
        val (logoSeg, logoOfs) = requireNotNull(Assets.assetFindLoaded(machine, 0x40))

        val frameMemBefore = machine.g(0x0BD8)
        val bpLogo = Objects.frameSlotTake(machine, logoSeg, logoOfs)
        assertEquals(2, machine.g(0x0BDC))
        assertEquals(0x40, machine.rw(machine.es, bpLogo - 0x10))

        // Destroy slot 6 (logo.io's frame) while running slot 0 -> compacts back
        machine.sg(0x0B02, 0)
        machine.sg(0x0B06, 6)
        Objects.frameDestroy(machine)

        assertEquals(1, machine.g(0x0BDC))
        assertEquals(frameMemBefore, machine.g(0x0BD8))
    }

    @Test
    fun `places and deletes sprite objects on a frame`() = runTest {
        val repo = TestResources()
        val files = GameFiles.fromResourceRepository(repo)
        val machine = Machine()
        Boot.boot(machine, files)

        // Frame 0 (main.io): place entry 0 (the arrow mouse pointer sprite) at (10, 20, 0) with index 5
        machine.bp = machine.g(0x0BF6)
        machine.es = machine.g(0x0BF8)
        machine.sgb(0x0C15, 1) // main.io
        machine.sgb(0x0C12, 0) // not mirrored
        machine.sgb(0x0C13, 0)
        machine.sg(0x0C02, 10)
        machine.sg(0x0C04, 20)
        machine.sg(0x0C06, 0)
        machine.sg(0x0C0E, 5)
        machine.dx = 0

        Objects.placeEntry(machine)

        val found = Objects.objectFind(machine)
        val ods = machine.g(0x0BEA)
        requireNotNull(found)
        assertEquals(0xFF, machine.rb(ods, found)) // state = 0xFF (new)
        assertEquals(5, machine.rb(ods, found + 1)) // index = 5
        assertEquals(10, machine.rw(ods, found + 0x16)) // world X
        assertEquals(20, machine.rw(ods, found + 0x18)) // world Y

        Objects.deleteObject(machine, index = 5, now = true)
        assertEquals(null, Objects.objectFind(machine))
    }
}
