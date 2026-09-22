/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

import app.morphe.engine.model.PatchedAppRecord
import app.morphe.engine.util.FileChecksum
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The evidence ordering behind [resolveDeviceInstallState]. Kept free of ADB, so
 * the thing that decides whether an app counts as patched can be checked without
 * a device attached.
 */
class TrackedInstallResolverTest {

    private val ours = setOf("cert-morphe")
    private val installers = setOf("com.amazon.venezia", "org.fdroid.fdroid")

    @Test
    fun `no device is not an uninstalled app`() {
        assertEquals(
            DeviceInstallState.NO_DEVICE,
            resolveDeviceInstallState(
                deviceAttached = false,
                packageInstalled = false,
                deviceSignatureId = null,
                morpheSignatureIds = ours,
            ),
        )
    }

    @Test
    fun `an attached device with no such package reports it uninstalled`() {
        assertEquals(
            DeviceInstallState.NOT_INSTALLED,
            resolveDeviceInstallState(
                deviceAttached = true,
                packageInstalled = false,
                deviceSignatureId = null,
                morpheSignatureIds = ours,
            ),
        )
    }

    @Test
    fun `Morphe's own certificate identifies its build`() {
        assertEquals(
            DeviceInstallState.INSTALLED,
            resolveDeviceInstallState(
                deviceAttached = true,
                packageInstalled = true,
                deviceSignatureId = "cert-morphe",
                morpheSignatureIds = ours,
            ),
        )
    }

    @Test
    fun `a certificate that is none of ours belongs to another build`() {
        assertEquals(
            DeviceInstallState.REPLACED,
            resolveDeviceInstallState(
                deviceAttached = true,
                packageInstalled = true,
                deviceSignatureId = "cert-google",
                morpheSignatureIds = ours,
            ),
        )
    }

    @Test
    fun `an unreadable certificate leaves the question open`() {
        assertEquals(
            DeviceInstallState.UNVERIFIED,
            resolveDeviceInstallState(
                deviceAttached = true,
                packageInstalled = true,
                deviceSignatureId = null,
                morpheSignatureIds = ours,
            ),
        )
    }

    @Test
    fun `nothing to compare against is unverified rather than clean`() {
        assertEquals(
            DeviceInstallState.UNVERIFIED,
            resolveDeviceInstallState(
                deviceAttached = true,
                packageInstalled = true,
                deviceSignatureId = "cert-anything",
                morpheSignatureIds = emptySet(),
            ),
        )
    }

    @Test
    fun `an installer Morphe could plausibly have used is not proof on its own`() {
        // org.fdroid.fdroid is one of several real, unrelated stores that happen to sit in the
        // same candidate set Morphe's own spoofed installs use — membership in that set is not
        // evidence of identity, only a certificate (or a foreign installer plus install timing)
        // is. Ambiguous evidence must stay ambiguous rather than resolve to a positive match.
        assertEquals(
            DeviceInstallState.UNVERIFIED,
            resolveDeviceInstallState(
                deviceAttached = true,
                packageInstalled = true,
                deviceSignatureId = null,
                morpheSignatureIds = ours,
                installerPackage = "org.fdroid.fdroid",
                morpheInstallers = installers,
            ),
        )
    }

    @Test
    fun `suspicious timing under a plausible installer is unverified, not a confident replaced`() {
        // Installed well after patching would be damning evidence on its own, but the installer
        // credited here is still one of Morphe's own candidates — plausibly Morphe's install with
        // an install time this check can't fully trust, not confidently someone else's. Neither
        // side of the evidence is strong enough here to call it either way.
        assertEquals(
            DeviceInstallState.UNVERIFIED,
            resolveDeviceInstallState(
                deviceAttached = true,
                packageInstalled = true,
                deviceSignatureId = null,
                morpheSignatureIds = ours,
                installerPackage = "org.fdroid.fdroid",
                morpheInstallers = installers,
                installedAfterPatching = true,
            ),
        )
    }

    @Test
    fun `a package that appeared after the patch under a foreign installer was replaced`() {
        assertEquals(
            DeviceInstallState.REPLACED,
            resolveDeviceInstallState(
                deviceAttached = true,
                packageInstalled = true,
                deviceSignatureId = null,
                morpheSignatureIds = ours,
                installerPackage = "com.android.vending",
                morpheInstallers = installers,
                installedAfterPatching = true,
            ),
        )
    }

    @Test
    fun `a certificate always outranks installer evidence`() {
        // A store could have installed a build Morphe signed — e.g. the user
        // sideloaded the patched APK through one — and the certificate is what
        // actually identifies it
        assertEquals(
            DeviceInstallState.INSTALLED,
            resolveDeviceInstallState(
                deviceAttached = true,
                packageInstalled = true,
                deviceSignatureId = "cert-morphe",
                morpheSignatureIds = ours,
                installerPackage = "com.android.vending",
                morpheInstallers = installers,
                installedAfterPatching = true,
            ),
        )
    }

    @Test
    fun `install time only counts past the clock-skew tolerance`() {
        val patchedAt = 1_000_000L
        assertFalse(installedAfterPatching(firstInstallTime = patchedAt, patchedAt = patchedAt))
        assertFalse(installedAfterPatching(firstInstallTime = patchedAt + 60_000L, patchedAt = patchedAt))
        assertTrue(installedAfterPatching(firstInstallTime = patchedAt + 3_600_000L, patchedAt = patchedAt))
        assertFalse(installedAfterPatching(firstInstallTime = null, patchedAt = patchedAt))
    }
}

/** What is left of the patched APK Morphe wrote, judged from the file alone. */
class PatchedArtifactStateTest {

    private val dir: File = Files.createTempDirectory("morphe-artifact-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun apk(name: String, bytes: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `a file that is gone means the record outlived its build`() {
        assertEquals(
            PatchedArtifactState.MISSING,
            resolveArtifactState(File(dir, "absent.apk"), recordedSize = 10),
        )
    }

    @Test
    fun `a file of the recorded size is the one Morphe wrote`() {
        assertEquals(
            PatchedArtifactState.PRESENT,
            resolveArtifactState(apk("a.apk", 128), recordedSize = 128),
        )
    }

    @Test
    fun `a different size means it changed outside Morphe`() {
        assertEquals(
            PatchedArtifactState.MODIFIED,
            resolveArtifactState(apk("b.apk", 128), recordedSize = 64),
        )
    }

    @Test
    fun `a record written before sizes were kept cannot be judged modified`() {
        assertEquals(
            PatchedArtifactState.PRESENT,
            resolveArtifactState(apk("c.apk", 128), recordedSize = 0),
        )
    }
}

/**
 * [TrackedInstallResolver.verifyArtifact]: the SHA-256-backed layer above
 * [resolveArtifactState], which a same-size overwrite would otherwise pass as
 * unchanged.
 */
class VerifyArtifactTest {

    private val dir: File = Files.createTempDirectory("morphe-verify-artifact-test").toFile()

    // Never makes an ADB call, so a default, unconfigured AdbManager is fine here
    private val resolver = TrackedInstallResolver(AdbManager())

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun recordFor(file: File, sha256: String?): PatchedAppRecord = PatchedAppRecord(
        id = "com.a",
        packageName = "com.a",
        displayName = "A",
        apkVersion = "1.0",
        inputApkPath = "/in.apk",
        outputApkPath = file.absolutePath,
        outputApkSha256 = sha256,
        outputApkSize = file.length(),
        patchedAt = 1L,
        patchedWithMorpheVersion = "test",
    )

    @Test
    fun `an untouched file verifies as present`() {
        val file = File(dir, "a.apk").apply { writeBytes(ByteArray(64) { 1 }) }
        val record = recordFor(file, FileChecksum.sha256(file))

        assertEquals(PatchedArtifactState.PRESENT, resolver.verifyArtifact(record, file))
    }

    @Test
    fun `a same-size file with different content is still caught, by its hash`() {
        val file = File(dir, "b.apk").apply { writeBytes(ByteArray(64) { 1 }) }
        val record = recordFor(file, FileChecksum.sha256(file))

        // Same size, different bytes — resolveArtifactState alone would call this unchanged
        file.writeBytes(ByteArray(64) { 2 })

        assertEquals(PatchedArtifactState.MODIFIED, resolver.verifyArtifact(record, file))
    }

    @Test
    fun `a record with no stored hash is trusted on size alone`() {
        val file = File(dir, "c.apk").apply { writeBytes(ByteArray(64) { 3 }) }
        val record = recordFor(file, sha256 = null)
        file.writeBytes(ByteArray(64) { 4 })

        // No hash to compare against — a size match is the most that can be said, exactly as it
        // was before this layer existed, for records written before outputApkSha256 was tracked
        assertEquals(PatchedArtifactState.PRESENT, resolver.verifyArtifact(record, file))
    }

    @Test
    fun `an unchanged size and mtime is read back from cache rather than rehashed`() {
        val file = File(dir, "d.apk").apply { writeBytes(ByteArray(64) { 5 }) }
        val record = recordFor(file, FileChecksum.sha256(file))
        assertEquals(PatchedArtifactState.PRESENT, resolver.verifyArtifact(record, file))

        // Tamper with the content but restore the exact (size, mtime) pair the first call saw —
        // the only way the second call could still say MODIFIED is if it rehashed rather than
        // trusting the cache, which this deliberately defeats to prove the cache is consulted
        val mtime = file.lastModified()
        file.writeBytes(ByteArray(64) { 6 })
        file.setLastModified(mtime)

        assertEquals(PatchedArtifactState.PRESENT, resolver.verifyArtifact(record, file))
    }

    @Test
    fun `a genuinely modified file is detected even after a prior verified read`() {
        val file = File(dir, "e.apk").apply { writeBytes(ByteArray(64) { 7 }) }
        val record = recordFor(file, FileChecksum.sha256(file))
        assertEquals(PatchedArtifactState.PRESENT, resolver.verifyArtifact(record, file))

        // A deterministic mtime bump rather than a real-time sleep, so this can't flake on a
        // filesystem with coarse mtime granularity
        val bumpedMtime = file.lastModified() + 60_000L
        file.writeBytes(ByteArray(96) { 8 })
        file.setLastModified(bumpedMtime)

        assertEquals(PatchedArtifactState.MODIFIED, resolver.verifyArtifact(record, file))
    }
}
