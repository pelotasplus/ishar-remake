package dev.pelotasplus.ishar.data

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceRepositoryTest {

    @Test
    fun `ByteReader reads little and big endian values accurately`() {
        val bytes = byteArrayOf(
            0x34, 0x12,
            0xFE.toByte(), 0xFF.toByte(),
            0x01, 0x02, 0x03,
            0x78, 0x56, 0x34, 0x12,
        )
        val reader = ByteReader(bytes)
        assertEquals(0x1234, reader.readU16LE())
        assertEquals(-2, reader.readI16LE())
        assertEquals(0x030201, reader.readU24LE())
        assertEquals(0x12345678L, reader.readU32LE())
        assertEquals(false, reader.hasMore())

        reader.seek(0)
        assertEquals(0x3412, reader.readU16BE())
    }

    @Test
    fun `manifest loads all 107 staged game files with expected sizes`() = runTest {
        val repo = ResourceRepositoryImpl()
        assertEquals(98, repo.ioFiles.size)
        assertEquals(8, repo.ficFiles.size)
        assertEquals(1, repo.programFiles.size)
        assertEquals(107, repo.allFiles.size)

        for (fileName in repo.allFiles) {
            val data = repo.readBytes(fileName).getOrThrow()
            assertTrue(data.isNotEmpty(), "Expected non-empty file for $fileName")
        }

        // Case-insensitive lookup (scripts request uppercase DOS names)
        assertEquals(14, repo.readBytes("START.STP").getOrThrow().size)
        assertEquals(4_860, repo.readBytes("CONT1.FIC").getOrThrow().size)
        assertEquals(3_640, repo.readBytes("EN1.FIC").getOrThrow().size)
        assertEquals(361, repo.readBytes("TAB1.FIC").getOrThrow().size)
    }
}
