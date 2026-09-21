/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

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
    fun `the installer Morphe credits stands in for a certificate it cannot read`() {
        assertEquals(
            DeviceInstallState.INSTALLED,
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
