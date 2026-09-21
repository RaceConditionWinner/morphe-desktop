/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine

import app.morphe.engine.model.PatchedAppRecord
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatchedAppStoreTest {

    private val tmpDir: File = Files.createTempDirectory("morphe-store-test").toFile()
    private val storeFile: File get() = File(tmpDir, PatchedAppStore.FILE_NAME)
    private fun newStore() = PatchedAppStore(storeFile)

    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private fun record(
        pkg: String,
        apkVersion: String = "1.0",
        patchedAt: Long = 1L,
        installsAs: String? = null,
        isClone: Boolean = false,
    ) = PatchedAppRecord(
        id = installsAs ?: pkg,
        packageName = pkg,
        currentPackageName = installsAs,
        isClone = isClone,
        displayName = pkg.substringAfterLast('.'),
        apkVersion = apkVersion,
        inputApkPath = "/in/$pkg.apk",
        outputApkPath = "/out/$pkg-patched.apk",
        patchedAt = patchedAt,
        patchedWithMorpheVersion = "test",
    )

    @Test
    fun `empty when no file exists`() = runBlocking {
        assertEquals(emptyList(), newStore().getAll())
        assertNull(newStore().get("com.whatever"))
    }

    @Test
    fun `upsert then read back`() = runBlocking {
        val store = newStore()
        store.upsert(record("com.a"))
        assertEquals(1, store.getAll().size)
        assertEquals("com.a", store.get("com.a")?.packageName)
        assertNull(store.get("com.missing"))
    }

    @Test
    fun `upsert replaces the record for the same package`() = runBlocking {
        val store = newStore()
        store.upsert(record("com.a", apkVersion = "1.0"))
        store.upsert(record("com.a", apkVersion = "2.0"))
        val all = store.getAll()
        assertEquals(1, all.size)
        assertEquals("2.0", all.single().apkVersion)
    }

    @Test
    fun `most recently upserted comes first`() = runBlocking {
        val store = newStore()
        store.upsert(record("com.a"))
        store.upsert(record("com.b"))
        assertEquals("com.b", store.getAll().first().packageName)
    }

    @Test
    fun `delete removes the record`() = runBlocking {
        val store = newStore()
        store.upsert(record("com.a"))
        store.delete("com.a")
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun `records persist across store instances`() = runBlocking {
        PatchedAppStore(storeFile).upsert(record("com.a"))
        // Fresh instance (empty cache) must read the persisted file.
        assertEquals("com.a", PatchedAppStore(storeFile).get("com.a")?.packageName)
    }

    @Test
    fun `a copy of an app does not overwrite the app's own build`() = runBlocking {
        val store = newStore()
        store.upsert(record("com.a"))
        store.upsert(record("com.a", installsAs = "com.a.clone", isClone = true))
        store.upsert(record("com.a", installsAs = "com.a.clone2", isClone = true))

        val all = store.getAll()
        assertEquals(3, all.size)
        assertEquals(setOf("com.a", "com.a.clone", "com.a.clone2"), all.map { it.trackingKey }.toSet())
        // Every copy still points back at the app the bundle data is keyed by
        assertTrue(all.all { it.packageName == "com.a" })
    }

    @Test
    fun `deleting a copy leaves the app's own build alone`() = runBlocking {
        val store = newStore()
        store.upsert(record("com.a"))
        store.upsert(record("com.a", installsAs = "com.a.clone", isClone = true))

        store.delete("com.a.clone")

        assertEquals(listOf("com.a"), store.getAll().map { it.trackingKey })
    }

    @Test
    fun `a renamed build is filed under the package it installs as`() = runBlocking {
        val store = newStore()
        store.upsert(record("com.google.android.youtube", installsAs = "app.morphe.android.youtube"))
        assertEquals(
            "com.google.android.youtube",
            store.get("app.morphe.android.youtube")?.packageName,
        )
    }

    @Test
    fun `v1 history migrates without losing records`() = runBlocking {
        storeFile.parentFile.mkdirs()
        // Schema v1: keyed by package name, no id field at all
        storeFile.writeText(
            """
            {
              "version": 1,
              "records": [
                {
                  "packageName": "com.a",
                  "currentPackageName": "app.morphe.a",
                  "displayName": "A",
                  "apkVersion": "1.0",
                  "inputApkPath": "/in/a.apk",
                  "outputApkPath": "/out/a.apk",
                  "patchedAt": 5,
                  "patchedWithMorpheVersion": "old"
                },
                {
                  "packageName": "com.b",
                  "displayName": "B",
                  "apkVersion": "2.0",
                  "inputApkPath": "/in/b.apk",
                  "outputApkPath": "/out/b.apk",
                  "patchedAt": 6,
                  "patchedWithMorpheVersion": "old"
                }
              ]
            }
            """.trimIndent()
        )

        val store = PatchedAppStore(storeFile)
        val all = store.getAll()
        assertEquals(2, all.size)
        // A renamed v1 record keeps the identity it already had on the device
        assertEquals("app.morphe.a", all.first { it.packageName == "com.a" }.trackingKey)
        assertEquals("com.b", all.first { it.packageName == "com.b" }.trackingKey)
        // Nothing was invented: a v1 record is never a copy
        assertTrue(all.none { it.isClone })
        assertEquals("app.morphe.a", store.get("app.morphe.a")?.id)
    }

    @Test
    fun `migrated history is rewritten at the current schema on the next write`() = runBlocking {
        storeFile.parentFile.mkdirs()
        storeFile.writeText(
            """
            {"version": 1, "records": [{
              "packageName": "com.a", "displayName": "A", "apkVersion": "1.0",
              "inputApkPath": "/in/a.apk", "outputApkPath": "/out/a.apk",
              "patchedAt": 5, "patchedWithMorpheVersion": "old"
            }]}
            """.trimIndent()
        )
        val store = PatchedAppStore(storeFile)
        store.upsert(record("com.b"))

        assertTrue(storeFile.readText().contains("\"version\": ${PatchedAppStore.SCHEMA_VERSION}"))
        assertEquals(2, PatchedAppStore(storeFile).getAll().size)
    }

    @Test
    fun `tolerates a corrupt file and heals on next write`() = runBlocking {
        storeFile.parentFile.mkdirs()
        storeFile.writeText("{ not valid json ")
        val store = PatchedAppStore(storeFile)
        assertEquals(emptyList(), store.getAll()) // no throw
        store.upsert(record("com.a"))
        assertEquals(1, PatchedAppStore(storeFile).getAll().size)
    }
}
