/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home

import androidx.compose.runtime.Immutable
import app.morphe.engine.model.PatchedAppRecord
import app.morphe.gui.data.model.SupportedApp
import app.morphe.gui.util.DeviceInstallState
import app.morphe.gui.util.PatchedArtifactState
import app.morphe.gui.util.compareVersions
import app.morphe.gui.util.isNewerVersion
import java.io.File

/**
 * Where an app stands against the newest version the enabled sources can patch:
 * either behind it with a rebuild available, or past everything they cover,
 * which is where an app that updated itself outside Morphe ends up.
 *
 * An app already at that version has no status, so the absence of one is what
 * "nothing to report" looks like at every call site.
 */
@Immutable
data class AppVersionStatus(
    val patchedVersion: String,
    val supportedVersion: String,
    val isBehind: Boolean,
)

/**
 * Where [patchedVersion] stands against [supportedVersion], or null when the two
 * are the same version, when either side is unknown, or when the sources name no
 * version at all, which is what universal patches leave behind.
 *
 * [ignoredVersion] is the version the user turned down. It answers the offer to
 * move up to that one and nothing else, so a build that has run past the sources
 * is still described.
 */
fun appVersionStatus(
    patchedVersion: String?,
    supportedVersion: String?,
    ignoredVersion: String? = null,
): AppVersionStatus? {
    val patched = patchedVersion?.takeIf { it.isNotBlank() } ?: return null
    val supported = supportedVersion?.takeIf { it.isNotBlank() } ?: return null

    val comparison = compareVersions(patched, supported)
    if (comparison == 0) return null
    if (comparison < 0 && supported == ignoredVersion) return null

    return AppVersionStatus(
        patchedVersion = patched,
        supportedVersion = supported,
        isBehind = comparison < 0,
    )
}

/**
 * The one state badge a card carries, out of everything that could be said about
 * it. Resolved here rather than by the card so two badges can never contradict
 * each other, and so the same verdict reaches the dialog and the screen reader.
 */
enum class HomeAppStatus {
    /** Nothing to report: the build is where the record says it is. */
    NONE,

    /** The record outlived the patched APK it describes. */
    ARTIFACT_MISSING,

    /** The patched APK is still there but is no longer the file Morphe wrote. */
    ARTIFACT_MODIFIED,

    /** Something other than Morphe's build holds the package name. */
    REPLACED,

    /** The package is there but its identity could not be established. */
    UNVERIFIED,

    /** The device is attached and the app is not on it. */
    UNINSTALLED,

    /** The build has not been inspected yet. */
    PENDING,
}

/**
 * One card on the home screen: an app Morphe has a build of, one of the copies
 * it was cloned into, or an app it supports and has never patched.
 *
 * Identified by [id] rather than by [packageName], which several cards of one
 * app share and which all bundle data is keyed by.
 *
 * This is the single semantic state behind both the card and the app information
 * dialog. Neither re-derives installation, update, version or identity state of
 * its own: everything they disagree about would be a bug the user sees.
 */
@Immutable
data class HomeAppItem(
    val id: String,
    /** The original package, which patch bundles and supported-app data are keyed by. */
    val packageName: String,
    /** The package this build installs under, differing after a rename or a clone. */
    val installedPackageName: String,
    val displayName: String,
    /** The version this card is about: what Morphe patched, or what the sources suggest. */
    val version: String,
    /**
     * The `appIconColor` behind this card's palette and the dialog's accent. Taken
     * from the live bundle first and from the record second, so a card keeps its
     * color once the source that declared it is gone.
     *
     * This is also where an app-icon provider will hang once one exists: the card
     * and the dialog already read their identity from this model rather than
     * resolving anything themselves.
     */
    val appIconColorHex: String?,
    /** The patched-app record, or null for a supported app that was never patched. */
    val record: PatchedAppRecord?,
    val isClone: Boolean,
    val deviceState: DeviceInstallState,
    val artifactState: PatchedArtifactState,
    /** Version the device reports for [installedPackageName], when it has one. */
    val deviceVersion: String?,
    /** Where the device keeps the installed APK, when it says. */
    val deviceApkPath: String?,
    /** Whether this build's state has not been resolved yet. */
    val isVerificationPending: Boolean,
    /** Whether a source that patched this app has published relevant changes since. */
    val hasPatchUpdate: Boolean,
    val versionStatus: AppVersionStatus?,
    /** The newest version the enabled sources can patch, whatever this build is at. */
    val supportedVersion: String?,
    /** Live supported-app data, absent once no enabled source brings this app. */
    val supportedApp: SupportedApp?,
    /** Per-source freshness behind [hasPatchUpdate], and the dialog's source rows. */
    val updateInfo: RecallUpdateInfo?,
    val isHidden: Boolean = false,
) {
    /** Whether Morphe has a build of this app rather than merely supporting it. */
    val isTracked: Boolean get() = record != null

    /** The patched APK Morphe wrote, when the record still has one on disk. */
    val patchedApk: File? get() = record
        ?.takeIf { artifactState != PatchedArtifactState.MISSING }
        ?.let { File(it.outputApkPath) }

    /** Whether the app is on the attached device at all, patched by Morphe or not. */
    val isOnDevice: Boolean get() = deviceState == DeviceInstallState.INSTALLED ||
        deviceState == DeviceInstallState.REPLACED ||
        deviceState == DeviceInstallState.UNVERIFIED

    /** Whether the build on the device is the one Morphe produced, which gates its actions. */
    val isInstalledOnDevice: Boolean get() = deviceState == DeviceInstallState.INSTALLED

    /**
     * Whether the patched APK can be pushed as-is, without patching again: the
     * file is still there, and the device is either missing it or behind it.
     * Never true without a device, because nothing is known about one that is
     * not attached.
     */
    val installPending: Boolean get() = record != null &&
        artifactState != PatchedArtifactState.MISSING &&
        when (deviceState) {
            DeviceInstallState.NOT_INSTALLED -> true
            DeviceInstallState.INSTALLED -> isNewerVersion(record.apkVersion, deviceVersion)
            else -> false
        }

    val appliedPatchCount: Int get() = record?.appliedPatchCount ?: 0

    /**
     * Whether the build is settled enough for pending work on it to be worth
     * surfacing. A build in any other state keeps its flags but is described by
     * the state it is in instead.
     *
     * An unattached device is settled here where Manager's never is: Desktop's
     * device layer is optional, and a rebuild badge must not disappear because
     * the user unplugged their phone.
     */
    private val isSettled: Boolean get() = isTracked &&
        !isVerificationPending &&
        artifactState != PatchedArtifactState.MISSING &&
        (deviceState == DeviceInstallState.INSTALLED || deviceState == DeviceInstallState.NO_DEVICE)

    val showsUpdateBadge: Boolean get() = hasPatchUpdate && isSettled

    /**
     * A build past everything the sources cover has already lost its patches, so
     * the state it is in says more than the version it is at, and only the
     * rebuildable end is badged here.
     */
    val showsVersionBadge: Boolean get() = versionStatus?.isBehind == true && isSettled

    /**
     * Whether the card carries a rebuild badge: newer patches, a newer supported
     * app version, or both. One badge stands for either, because rebuilding is
     * the single answer to both.
     */
    val showsRebuildBadge: Boolean get() = showsUpdateBadge || showsVersionBadge

    /** The one state badge this card carries, most consequential first. */
    val status: HomeAppStatus get() = when {
        !isTracked -> HomeAppStatus.NONE
        isVerificationPending -> HomeAppStatus.PENDING
        artifactState == PatchedArtifactState.MISSING -> HomeAppStatus.ARTIFACT_MISSING
        deviceState == DeviceInstallState.REPLACED -> HomeAppStatus.REPLACED
        deviceState == DeviceInstallState.UNVERIFIED -> HomeAppStatus.UNVERIFIED
        deviceState == DeviceInstallState.NOT_INSTALLED -> HomeAppStatus.UNINSTALLED
        artifactState == PatchedArtifactState.MODIFIED -> HomeAppStatus.ARTIFACT_MODIFIED
        else -> HomeAppStatus.NONE
    }
}

/**
 * What a home screen card is about, before anything is read off the device to
 * describe it.
 */
data class HomeAppSlot(
    val id: String,
    val packageName: String,
    val record: PatchedAppRecord?,
    val isClone: Boolean,
)

/**
 * The cards one app is shown as: the app itself, followed by every further build
 * of it, ordered by the package they install under so the list does not move
 * around between reads.
 *
 * A rename is not what gives a build a card of its own. Patches rename an app for
 * reasons of their own, and such a build is still the app's own install, so it
 * belongs on the app's card, which is where the user goes to rebuild it. The app
 * keeps that card even once its only builds are copies, because it is the only
 * place another copy can be made from.
 */
fun homeAppSlots(packageName: String, records: List<PatchedAppRecord>): List<HomeAppSlot> {
    // Two records can describe the app's own build: a renamed one and a plain one
    // side by side. The card goes to the one answering to the app's own name, and
    // the other keeps a card of its own rather than dropping out of reach of the
    // actions that manage it.
    val own = records.firstOrNull { !it.isClone && it.installedPackageName == packageName }
        ?: records.filterNot { it.isClone }.minByOrNull { it.trackingKey }
    val separate = records.filter { it.trackingKey != own?.trackingKey }

    return buildList {
        add(HomeAppSlot(packageName, packageName, own, isClone = false))
        separate.sortedBy { it.trackingKey }.forEach { record ->
            add(HomeAppSlot(record.trackingKey, packageName, record, record.isClone))
        }
    }
}

/**
 * One bundle that contributed patches to a build, named from the live source
 * first and from what patching recorded second.
 *
 * A source the user has since removed still has to be nameable, which is the
 * whole reason the record keeps a snapshot of it. An internal id is never part
 * of the answer, so a deleted source reads as one rather than as a hash.
 */
@Immutable
data class AppliedBundle(
    val sourceId: String,
    val title: String,
    val version: String?,
    /** Whether the source is still around, which decides how its patches resolved. */
    val available: Boolean,
    /** Patches resolved to the names their bundle gives them. */
    val patchNames: List<String>,
    /** Patches whose bundle could not name them, shown as recorded. */
    val unresolvedNames: List<String>,
) {
    val patchCount: Int get() = patchNames.size + unresolvedNames.size
}

/**
 * Names a changelog could have scoped its entries to for an app whose sources
 * call it [supportedAppName] and whose own resolved label is [displayName],
 * under [packageName]. Multiple candidates exist because the same app can be
 * named differently across sources — the bundle's own Compatibility
 * declaration versus a locally recorded label — and matching any one is
 * enough. [displayName] alone keeps this working once the source that
 * supplied [supportedAppName] is gone.
 *
 * The one definition of "what does a changelog consider this app" — used to
 * decide whether a source update is relevant (before any [HomeAppItem]
 * exists to ask) and, via [HomeAppItem.changelogAppNames], to scope a
 * changelog's actual content once one does. The update badge, the dialog's
 * rebuild banner, and What's New all read from this rather than each
 * resolving names of their own.
 */
fun changelogAppNames(
    supportedAppName: String?,
    displayName: String,
    packageName: String,
): Set<String> = buildSet {
    supportedAppName?.takeIf { it.isNotBlank() }?.let { add(it) }
    displayName.takeIf { it.isNotBlank() }?.let { add(it) }
    add(SupportedApp.getDisplayName(packageName))
}

/** [changelogAppNames] for this item. */
val HomeAppItem.changelogAppNames: Set<String>
    get() = changelogAppNames(supportedApp?.displayName, displayName, packageName)
