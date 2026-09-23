/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.model.PatchedAppRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Covers the JSON-file-backed [OriginalApkRepository]: archiving, dedup of
 * an unchanged package+version, retention-disabled skipping, replacing an
 * older archived version, [OriginalApkRepository.pruneMissingApks], delete,
 * and the [OriginalApkRepository.changes] notification — including the
 * exact race [changes]'s `replay = 1` exists to close: a collector that
 * subscribes *after* the emission already happened must still see it.
 */
class OriginalApkRepositoryTest {

    private fun tempDir(): File = createTempDirectory().toFile()

    private fun fakeApk(dir: File, name: String = "input.apk", content: String = "fake apk bytes"): File =
        File(dir, name).apply { writeText(content) }

    private fun newRepository(
        archiveDir: File = tempDir(),
        retentionEnabled: Boolean = true,
        storeFile: File = File(tempDir(), "original-apks.json"),
    ) = OriginalApkRepository(
        file = storeFile,
        archiveDir = archiveDir,
        isRetentionEnabled = { retentionEnabled },
    )

    @Test
    fun `get returns null for a package that was never archived`() = runBlocking {
        assertNull(newRepository().get("com.example.app"))
    }

    @Test
    fun `saveOriginalApk archives a copy and get finds it`() = runBlocking {
        val repo = newRepository()
        val source = fakeApk(tempDir())

        val archived = repo.saveOriginalApk("com.example.app", "1.0.0", source)

        assertTrue(archived != null)
        assertTrue(archived!!.exists())
        assertEquals(source.readText(), archived.readText())
        val record = repo.get("com.example.app")!!
        assertEquals("1.0.0", record.version)
        assertEquals(archived.absolutePath, record.filePath)
        assertEquals(source.length(), record.fileSize)
        assertTrue(record.sha256 != null)
    }

    @Test
    fun `saveOriginalApk does nothing when retention is disabled`() = runBlocking {
        val repo = newRepository(retentionEnabled = false)
        assertNull(repo.saveOriginalApk("com.example.app", "1.0.0", fakeApk(tempDir())))
        assertNull(repo.get("com.example.app"))
    }

    @Test
    fun `saveOriginalApk returns null when the source file does not exist`() = runBlocking {
        val repo = newRepository()
        assertNull(repo.saveOriginalApk("com.example.app", "1.0.0", File(tempDir(), "missing.apk")))
    }

    @Test
    fun `saving the same package and version again does not re-copy`() = runBlocking {
        val archiveDir = tempDir()
        val repo = newRepository(archiveDir)
        val source = fakeApk(tempDir())

        val first = repo.saveOriginalApk("com.example.app", "1.0.0", source)!!
        val firstModified = first.lastModified()
        Thread.sleep(10)
        val second = repo.saveOriginalApk("com.example.app", "1.0.0", source)!!

        assertEquals(first.absolutePath, second.absolutePath)
        assertEquals(firstModified, second.lastModified(), "the file should not have been rewritten")
    }

    @Test
    fun `saving a newer version replaces the archived file for that package`() = runBlocking {
        val archiveDir = tempDir()
        val repo = newRepository(archiveDir)
        repo.saveOriginalApk("com.example.app", "1.0.0", fakeApk(tempDir(), content = "v1 bytes"))
        repo.saveOriginalApk("com.example.app", "2.0.0", fakeApk(tempDir(), content = "v2 bytes, longer!!"))

        val record = repo.get("com.example.app")!!
        assertEquals("2.0.0", record.version)
        assertEquals("v2 bytes, longer!!", File(record.filePath).readText())
        assertEquals(1, repo.getAll().size)
    }

    @Test
    fun `resolveInputApk never lets an older record pick up a newer record's archive`() = runBlocking {
        // Models two PatchedAppRecords sharing one package — the app's own build and a clone
        // patched later at a newer app version, which is exactly the scenario the archive's
        // one-slot-per-package design (see the test above) has to stay safe against: the app's
        // own record must keep repatching from the version it actually tracks, never silently
        // pick up the clone's replacement.
        val archiveDir = tempDir()
        val repo = newRepository(archiveDir)
        val v1Source = fakeApk(tempDir(), name = "v1-source.apk", content = "v1 bytes")

        repo.saveOriginalApk("com.example.app", "1.0.0", v1Source)
        val recordAtV1 = PatchedAppRecord(
            id = "com.example.app",
            packageName = "com.example.app",
            displayName = "Example",
            apkVersion = "1.0.0",
            inputApkPath = v1Source.absolutePath,
            outputApkPath = "/out.apk",
            patchedAt = 1L,
            patchedWithMorpheVersion = "test",
        )

        // Still resolves to the archive while it's the current one
        val beforeSupersede = repo.resolveInputApk(recordAtV1)
        assertEquals("v1 bytes", beforeSupersede?.readText())

        // A clone (or any other record sharing this package) gets patched at a newer version,
        // superseding — and deleting — the v1 archive
        repo.saveOriginalApk("com.example.app", "2.0.0", fakeApk(tempDir(), content = "v2 bytes"))

        // recordAtV1 still tracks 1.0.0. It must not resolve to the v2 archive now registered
        // under the same package — it should fall through to its own recorded path instead,
        // which is still there (saveOriginalApk never deletes the caller's source on its own;
        // that's the separate discardSource step)
        val afterSupersede = repo.resolveInputApk(recordAtV1)
        assertEquals(v1Source.absolutePath, afterSupersede?.absolutePath)
        assertEquals("v1 bytes", afterSupersede?.readText())
    }

    @Test
    fun `different packages are archived independently`() = runBlocking {
        val repo = newRepository()
        repo.saveOriginalApk("com.example.one", "1.0.0", fakeApk(tempDir(), "one.apk"))
        repo.saveOriginalApk("com.example.two", "1.0.0", fakeApk(tempDir(), "two.apk"))

        assertEquals(2, repo.getAll().size)
    }

    @Test
    fun `pruneMissingApks drops records whose file was deleted externally`() = runBlocking {
        val repo = newRepository()
        val archived = repo.saveOriginalApk("com.example.app", "1.0.0", fakeApk(tempDir()))!!

        archived.delete()
        repo.pruneMissingApks()

        assertNull(repo.get("com.example.app"))
    }

    @Test
    fun `pruneMissingApks removes leftover staging files from an interrupted save`() = runBlocking {
        val archiveDir = tempDir()
        val repo = newRepository(archiveDir)
        val staged = File(archiveDir, "leftover_1.0.0_original.apk.part").apply { writeText("partial") }

        repo.pruneMissingApks()

        assertFalse(staged.exists())
    }

    @Test
    fun `delete removes both the file and the record`() = runBlocking {
        val repo = newRepository()
        val archived = repo.saveOriginalApk("com.example.app", "1.0.0", fakeApk(tempDir()))!!

        repo.delete("com.example.app")

        assertNull(repo.get("com.example.app"))
        assertFalse(archived.exists())
    }

    @Test
    fun `changes replays the last emission to a collector that subscribes after it happened`() = runBlocking {
        // This is the exact race `replay = 1` exists to close (see PatchedAppStore's
        // class doc): the save fully completes — including its emission — before
        // anyone is listening, and a late subscriber must still see it rather than
        // hanging forever waiting for a next emission that never comes.
        val repo = newRepository()
        repo.saveOriginalApk("com.example.app", "1.0.0", fakeApk(tempDir()))

        withTimeout(5_000) {
            repo.changes.first()
        }
    }

    @Test
    fun `changes emits on save and on delete`() = runBlocking {
        val repo = newRepository()
        withTimeout(5_000) {
            repo.saveOriginalApk("com.example.app", "1.0.0", fakeApk(tempDir()))
            repo.changes.first()
        }
        withTimeout(5_000) {
            repo.delete("com.example.app")
            repo.changes.first()
        }
    }
}
