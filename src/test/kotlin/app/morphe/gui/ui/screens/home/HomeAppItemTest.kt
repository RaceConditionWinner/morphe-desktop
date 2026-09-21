/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home

import app.morphe.engine.model.PatchedAppRecord
import app.morphe.gui.util.DeviceInstallState
import app.morphe.gui.util.PatchedArtifactState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The semantic state behind both the app card and the information dialog: which
 * badge a card carries, and when pending work on a build is worth surfacing.
 */
class HomeAppItemTest {

    private fun record(
        pkg: String = "com.a",
        installsAs: String = pkg,
        isClone: Boolean = false,
        version: String = "1.0",
    ) = PatchedAppRecord(
        id = installsAs,
        packageName = pkg,
        currentPackageName = installsAs.takeIf { it != pkg },
        isClone = isClone,
        displayName = "A",
        apkVersion = version,
        inputApkPath = "/in.apk",
        outputApkPath = "/out.apk",
        patchedAt = 1L,
        patchedWithMorpheVersion = "test",
    )

    private fun item(
        record: PatchedAppRecord? = record(),
        deviceState: DeviceInstallState = DeviceInstallState.INSTALLED,
        artifactState: PatchedArtifactState = PatchedArtifactState.PRESENT,
        pending: Boolean = false,
        hasPatchUpdate: Boolean = false,
        versionStatus: AppVersionStatus? = null,
        deviceVersion: String? = null,
    ) = HomeAppItem(
        id = record?.trackingKey ?: "com.a",
        packageName = "com.a",
        installedPackageName = record?.installedPackageName ?: "com.a",
        displayName = "A",
        version = record?.apkVersion.orEmpty(),
        appIconColorHex = null,
        record = record,
        isClone = record?.isClone == true,
        deviceState = deviceState,
        artifactState = artifactState,
        deviceVersion = deviceVersion,
        deviceApkPath = null,
        isVerificationPending = pending,
        hasPatchUpdate = hasPatchUpdate,
        versionStatus = versionStatus,
        supportedVersion = versionStatus?.supportedVersion,
        supportedApp = null,
        updateInfo = null,
    )

    // ── Version status ──────────────────────────────────────────────────────

    @Test
    fun `no status when the build is already at the supported version`() {
        assertNull(appVersionStatus("1.2.3", "1.2.3"))
        assertNull(appVersionStatus("v1.2.3", "1.2.3"))
    }

    @Test
    fun `behind the sources is rebuildable, past them is not`() {
        assertTrue(appVersionStatus("1.0", "2.0")!!.isBehind)
        assertFalse(appVersionStatus("3.0", "2.0")!!.isBehind)
    }

    @Test
    fun `an unknown version on either side has nothing to report`() {
        assertNull(appVersionStatus(null, "2.0"))
        assertNull(appVersionStatus("1.0", null))
        assertNull(appVersionStatus("", "2.0"))
    }

    @Test
    fun `an ignored version answers that offer and no other`() {
        assertNull(appVersionStatus("1.0", "2.0", ignoredVersion = "2.0"))
        assertTrue(appVersionStatus("1.0", "3.0", ignoredVersion = "2.0")!!.isBehind)
        // Running past the sources is still described: it is not an offer to turn down
        assertFalse(appVersionStatus("3.0", "2.0", ignoredVersion = "2.0")!!.isBehind)
    }

    // ── Badges ──────────────────────────────────────────────────────────────

    @Test
    fun `a settled build surfaces the work pending on it`() {
        val behind = AppVersionStatus("1.0", "2.0", isBehind = true)
        assertTrue(item(hasPatchUpdate = true).showsUpdateBadge)
        assertTrue(item(versionStatus = behind).showsVersionBadge)
        assertTrue(item(hasPatchUpdate = true, versionStatus = behind).showsRebuildBadge)
    }

    @Test
    fun `an unplugged device does not take the rebuild badge away`() {
        assertTrue(
            item(deviceState = DeviceInstallState.NO_DEVICE, hasPatchUpdate = true).showsRebuildBadge
        )
    }

    @Test
    fun `a build in trouble is described by that rather than by what is pending`() {
        listOf(
            item(artifactState = PatchedArtifactState.MISSING, hasPatchUpdate = true),
            item(deviceState = DeviceInstallState.REPLACED, hasPatchUpdate = true),
            item(deviceState = DeviceInstallState.UNVERIFIED, hasPatchUpdate = true),
            item(deviceState = DeviceInstallState.NOT_INSTALLED, hasPatchUpdate = true),
            item(pending = true, hasPatchUpdate = true),
        ).forEach { assertFalse(it.showsRebuildBadge, "${it.status} should not carry a rebuild badge") }
    }

    @Test
    fun `a version past everything the sources cover is not badged as rebuildable`() {
        val ahead = AppVersionStatus("3.0", "2.0", isBehind = false)
        assertFalse(item(versionStatus = ahead).showsVersionBadge)
    }

    @Test
    fun `one badge per card, most consequential first`() {
        assertEquals(HomeAppStatus.NONE, item().status)
        assertEquals(HomeAppStatus.PENDING, item(pending = true).status)
        assertEquals(
            HomeAppStatus.ARTIFACT_MISSING,
            item(artifactState = PatchedArtifactState.MISSING, deviceState = DeviceInstallState.REPLACED).status,
        )
        assertEquals(HomeAppStatus.REPLACED, item(deviceState = DeviceInstallState.REPLACED).status)
        assertEquals(HomeAppStatus.UNVERIFIED, item(deviceState = DeviceInstallState.UNVERIFIED).status)
        assertEquals(HomeAppStatus.UNINSTALLED, item(deviceState = DeviceInstallState.NOT_INSTALLED).status)
        assertEquals(
            HomeAppStatus.ARTIFACT_MODIFIED,
            item(artifactState = PatchedArtifactState.MODIFIED).status,
        )
        // An app Morphe has no build of has nothing to report about one
        assertEquals(HomeAppStatus.NONE, item(record = null, deviceState = DeviceInstallState.NOT_INSTALLED).status)
    }

    // ── Install readiness ───────────────────────────────────────────────────

    @Test
    fun `the patched APK can be pushed when the device is missing it or behind it`() {
        assertTrue(item(deviceState = DeviceInstallState.NOT_INSTALLED).installPending)
        assertTrue(item(deviceVersion = "0.9").installPending)
        assertFalse(item(deviceVersion = "1.0").installPending)
        assertFalse(
            item(deviceState = DeviceInstallState.NOT_INSTALLED, artifactState = PatchedArtifactState.MISSING)
                .installPending
        )
        // Nothing can be said about a device that is not there
        assertFalse(item(deviceState = DeviceInstallState.NO_DEVICE).installPending)
    }

    // ── Card slots ──────────────────────────────────────────────────────────

    @Test
    fun `an app with no build still gets its card`() {
        val slots = homeAppSlots("com.a", emptyList())
        assertEquals(1, slots.size)
        assertEquals("com.a", slots.single().id)
        assertNull(slots.single().record)
        assertFalse(slots.single().isClone)
    }

    @Test
    fun `a rename keeps the build on the app's own card`() {
        val renamed = record(pkg = "com.a", installsAs = "app.morphe.a")
        val slots = homeAppSlots("com.a", listOf(renamed))
        assertEquals(1, slots.size)
        assertEquals("com.a", slots.single().id)
        assertEquals(renamed, slots.single().record)
    }

    @Test
    fun `every copy gets a card of its own, in a stable order`() {
        val own = record()
        val cloneB = record(installsAs = "com.a.b", isClone = true)
        val cloneA = record(installsAs = "com.a.a", isClone = true)

        val slots = homeAppSlots("com.a", listOf(cloneB, own, cloneA))

        assertEquals(listOf("com.a", "com.a.a", "com.a.b"), slots.map { it.id })
        assertEquals(listOf(false, true, true), slots.map { it.isClone })
        assertEquals(own, slots.first().record)
    }

    @Test
    fun `an app whose only builds are copies keeps the card copies are made from`() {
        val clone = record(installsAs = "com.a.clone", isClone = true)
        val slots = homeAppSlots("com.a", listOf(clone))

        assertEquals(listOf("com.a", "com.a.clone"), slots.map { it.id })
        assertNull(slots.first().record)
        assertEquals(clone, slots.last().record)
    }
}
