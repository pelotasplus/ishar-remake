package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AssetsTest {

    @Test
    fun `loads and unloads assets with sorted registration and heap compaction`() = runTest {
        val repo = TestResources()
        val files = GameFiles.fromResourceRepository(repo)
        val machine = Machine()
        Boot.boot(machine, files)

        val heapAfterMain = machine.g(0x0BCA)

        // Load logo.io (id 0x40), fond.io (id 0x05), and preson.io (id 0x3c, has packed samples)
        Assets.loadAsset(machine, files, 0x40, "logo.io")
        Assets.loadAsset(machine, files, 0x05, "fond.io")
        Assets.loadAsset(machine, files, 0x3C, "preson.io")

        assertEquals(4, machine.g(0x0BCE))
        assertNotNull(Assets.assetFindLoaded(machine, 0x05))
        assertNotNull(Assets.assetFindLoaded(machine, 0x3C))
        assertNotNull(Assets.assetFindLoaded(machine, 0x40))

        // Verify sorted order in asset table: 0x00, 0x05, 0x3c, 0x40
        val es = machine.g(0x0BD6)
        val base = machine.g(0x0BC2)
        val ids = (0 until 4).map { idx ->
            val (ofs, seg) = machine.far(es, base + 4 * idx)
            machine.rw(seg, ofs)
        }
        assertEquals(listOf(0x00, 0x05, 0x3C, 0x40), ids)

        // Unload logo.io, fond.io, and preson.io -> heap compacts back to heapAfterMain
        Assets.unloadAsset(machine, 0x05)
        Assets.unloadAsset(machine, 0x40)
        Assets.unloadAsset(machine, 0x3C)

        assertNull(Assets.assetFindLoaded(machine, 0x05))
        assertNull(Assets.assetFindLoaded(machine, 0x40))
        assertNull(Assets.assetFindLoaded(machine, 0x3C))
        assertEquals(1, machine.g(0x0BCE))
        assertEquals(heapAfterMain, machine.g(0x0BCA))
    }
}
