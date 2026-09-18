/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Covers the hardened [SeenPatchesRepository]: round-trip, independence
 * between (package, source) pairs, atomic-write durability, and reading
 * both the current versioned-envelope format and the pre-hardening bare-map
 * format (keyed by source name, from before this identity fix).
 */
class SeenPatchesRepositoryTest {

    private fun tempFile(): File = File(createTempDirectory().toFile(), "seen-patches.json")

    @Test
    fun `get returns null for a pair that was never saved`() = runBlocking {
        assertNull(SeenPatchesRepository(tempFile()).get("com.example.app", "source-1"))
    }

    @Test
    fun `save then get round-trips`() = runBlocking {
        val repo = SeenPatchesRepository(tempFile())
        repo.save("com.example.app", "source-1", setOf("Patch A", "Patch B"))

        assertEquals(setOf("Patch A", "Patch B"), repo.get("com.example.app", "source-1"))
    }

    @Test
    fun `save replaces the previous set for the same pair`() = runBlocking {
        val repo = SeenPatchesRepository(tempFile())
        repo.save("com.example.app", "source-1", setOf("A"))
        repo.save("com.example.app", "source-1", setOf("A", "B"))

        assertEquals(setOf("A", "B"), repo.get("com.example.app", "source-1"))
    }

    @Test
    fun `different packages and sources are independent`() = runBlocking {
        val repo = SeenPatchesRepository(tempFile())
        repo.save("com.example.one", "source-1", setOf("A"))
        repo.save("com.example.one", "source-2", setOf("B"))
        repo.save("com.example.two", "source-1", setOf("C"))

        assertEquals(setOf("A"), repo.get("com.example.one", "source-1"))
        assertEquals(setOf("B"), repo.get("com.example.one", "source-2"))
        assertEquals(setOf("C"), repo.get("com.example.two", "source-1"))
    }

    @Test
    fun `a save is durable across separate repository instances`() = runBlocking {
        val file = tempFile()
        SeenPatchesRepository(file).save("com.example.app", "source-1", setOf("A"))
        assertEquals(setOf("A"), SeenPatchesRepository(file).get("com.example.app", "source-1"))
    }

    @Test
    fun `reads the pre-hardening bare-map format with no version envelope`() = runBlocking {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("""{"com.example.app": {"source-1": ["A", "B"]}}""")

        assertEquals(setOf("A", "B"), SeenPatchesRepository(file).get("com.example.app", "source-1"))
    }

    @Test
    fun `a malformed file does not crash lookups`() = runBlocking {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("{ not valid json !!!")

        assertNull(SeenPatchesRepository(file).get("com.example.app", "source-1"))
    }

    @Test
    fun `no leftover tmp staging file remains after a successful save`() = runBlocking {
        val file = tempFile()
        SeenPatchesRepository(file).save("com.example.app", "source-1", setOf("A"))

        assertFalse(File(file.parentFile, "${file.name}.tmp").exists())
        assertTrue(file.exists())
    }
}
