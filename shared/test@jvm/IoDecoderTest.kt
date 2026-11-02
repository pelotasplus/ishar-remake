package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.ByteReader
import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IoDecoderTest {

    @Test
    fun `decodes all 98 io containers to their exact declared sizes`() = runTest {
        val repo = TestResources()
        var lzCount = 0
        var storedCount = 0

        for (fileName in repo.ioFiles) {
            val raw = repo.readBytes(fileName).getOrThrow()
            val decoded = IoDecoder.decode(raw)
            assertEquals(
                decoded.header.decodedSize,
                decoded.bytes.size,
                "Decoded size mismatch for $fileName",
            )
            if (decoded.header.isCompressed) {
                lzCount++
                assertEquals(0xA0, decoded.header.mode)
            } else {
                storedCount++
                assertEquals("blancpc.io", fileName)
            }

            if (fileName == "main.io") {
                assertTrue(decoded.header.isCatalogue)
                val boot = assertNotNull(decoded.header.bootBlock)
                assertEquals(60, boot.assetTableEntries)
                assertEquals(50, boot.frameSlots)
                assertEquals(24_000, boot.frameMemoryBytes)
                assertEquals(500, boot.objectRecords)
                assertEquals(11_240, raw.size)
                assertEquals(26_384, decoded.bytes.size)
                // the reference engine: main.io's last match writes 2 overrun bytes past decodedSize
                assertEquals(2, decoded.overrun.size)
            } else {
                assertNull(decoded.header.bootBlock)
            }

            // Every decoded .io except blancpc.io has init script offset 0x0016 at +02 and version 0x17 at +05
            if (fileName != "blancpc.io") {
                assertEquals(0x0016, ByteReader.u16LE(decoded.bytes, 2), "init script offset in $fileName")
                assertEquals(0x17, ByteReader.u8(decoded.bytes, 5), "asset version in $fileName")
            }
        }

        assertEquals(97, lzCount)
        assertEquals(1, storedCount)
    }
}
