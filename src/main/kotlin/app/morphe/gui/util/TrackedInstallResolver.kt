/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

import app.morphe.engine.model.PatchedAppRecord
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a tracked build stands on the device Morphe is pointed at.
 *
 * Desktop's device layer is optional in a way Manager's never is: with nothing
 * attached, the question is not answered rather than answered "no", which is why
 * [NO_DEVICE] is a state of its own instead of collapsing into [NOT_INSTALLED].
 * Telling the two apart is what keeps a rebuild badge on a card while the user's
 * phone is unplugged.
 */
enum class DeviceInstallState {
    /** No device is attached, so nothing can be said about the install. */
    NO_DEVICE,

    /** The package on the device is the build Morphe produced. */
    INSTALLED,

    /** Something else holds the package name: a stock build, or somebody else's. */
    REPLACED,

    /** The package is there but its identity could not be established. */
    UNVERIFIED,

    /** A device is attached and holds no package under that name. */
    NOT_INSTALLED,
}

/** What is left of the patched APK Morphe wrote for a record. */
enum class PatchedArtifactState {
    PRESENT,

    /** Still on disk, but no longer the file Morphe wrote. */
    MODIFIED,

    /** Gone from disk, so the record has outlived the build it describes. */
    MISSING,
}

/** Everything one tracked record's actions need to know, resolved in one pass. */
data class TrackedInstall(
    val deviceState: DeviceInstallState,
    val artifactState: PatchedArtifactState,
    /** Version the device reports for the tracked package, when it has one. */
    val deviceVersion: String? = null,
    /** Where the device keeps the installed APK, when it says. */
    val deviceApkPath: String? = null,
) {
    /** The patched APK Morphe wrote is still there to install, export or inspect. */
    val hasUsableArtifact: Boolean get() = artifactState != PatchedArtifactState.MISSING

    /**
     * Whether the patched build can be pushed without patching again: the file is
     * there and the device is either missing it or behind it.
     */
    fun installPending(recordVersion: String): Boolean = hasUsableArtifact && when (deviceState) {
        DeviceInstallState.NOT_INSTALLED -> true
        DeviceInstallState.INSTALLED -> isNewerVersion(recordVersion, deviceVersion)
        else -> false
    }
}

/**
 * The record is written the moment the install finishes, so only clock skew
 * between host and device separates the two stamps.
 */
private const val INSTALL_TIME_TOLERANCE_MS = 5 * 60_000L

/**
 * Weighs the evidence that the package on the device is still the build Morphe
 * produced, strongest signal first. Kept free of ADB and file APIs so the
 * ordering between the signals can be tested directly.
 *
 * A mismatch only proves the installed package is not ours; it does not identify
 * what replaced it, and an unreadable certificate leaves the question open rather
 * than answered. Neither is reported as a clean install.
 */
internal fun resolveDeviceInstallState(
    deviceAttached: Boolean,
    packageInstalled: Boolean,
    deviceSignatureId: String?,
    morpheSignatureIds: Set<String>,
    installerPackage: String? = null,
    morpheInstallers: Set<String> = emptySet(),
    installedAfterPatching: Boolean = false,
): DeviceInstallState = when {
    !deviceAttached -> DeviceInstallState.NO_DEVICE
    !packageInstalled -> DeviceInstallState.NOT_INSTALLED

    // Patching signs with Morphe's own keystore, so the certificate identifies the
    // build whatever the package is called and whatever version it reports
    deviceSignatureId != null && deviceSignatureId in morpheSignatureIds -> DeviceInstallState.INSTALLED

    // A certificate we could read that is none of ours belongs to another build
    deviceSignatureId != null && morpheSignatureIds.isNotEmpty() -> DeviceInstallState.REPLACED

    // No certificate to compare. Morphe credits its own installs to a store that
    // is not on the device, which nothing else would have done
    installerPackage != null && installerPackage in morpheInstallers -> DeviceInstallState.INSTALLED

    // A package that only appeared after Morphe patched it, credited to an
    // installer Morphe would not have used, is somebody else's installation
    installedAfterPatching && installerPackage !in morpheInstallers -> DeviceInstallState.REPLACED

    // A comparison was possible in principle, so say the check did not happen
    // rather than imply it passed
    else -> DeviceInstallState.UNVERIFIED
}

/**
 * Whether the installation on the device is newer than the record tracking it.
 * An install Morphe made itself keeps the record it was made from, so this is
 * only ever weighed next to an installer Morphe would not have set.
 */
internal fun installedAfterPatching(firstInstallTime: Long?, patchedAt: Long): Boolean =
    firstInstallTime != null && firstInstallTime > patchedAt + INSTALL_TIME_TOLERANCE_MS

/** Whether the patched APK at [path] is still the file [record] describes. */
internal fun resolveArtifactState(path: File, recordedSize: Long): PatchedArtifactState = when {
    !path.exists() -> PatchedArtifactState.MISSING
    // A re-signed or rebuilt APK changes size. The recorded sha256 stays the
    // certain answer, but hashing every record on every refresh is not something
    // the home screen can afford, so size is the signal here.
    recordedSize > 0 && path.length() != recordedSize -> PatchedArtifactState.MODIFIED
    else -> PatchedArtifactState.PRESENT
}

/**
 * One reading of the device, shared by every card built from it.
 *
 * [installedPackages] is the same `pm list packages` answer the verdicts were
 * drawn from, so an app with no record of its own can be told apart from one
 * that is simply absent without a second device call.
 */
data class TrackedInstallSnapshot(
    val deviceAttached: Boolean,
    val installedPackages: Set<String>,
    val byTrackingKey: Map<String, TrackedInstall>,
) {
    companion object {
        /** Nothing attached and nothing resolved, which is where the home screen starts. */
        val None = TrackedInstallSnapshot(false, emptySet(), emptyMap())
    }
}

/**
 * Resolves the device- and disk-side state of every tracked record in one pass.
 *
 * Repeated refreshes for an unchanged app are answered from the previous result:
 * a `dumpsys package` per record per refresh is what made the home screen stall
 * with a large history attached. The fingerprint covers everything the verdict
 * reads, so a reinstall, a repatch or a different device all invalidate it.
 */
class TrackedInstallResolver(
    private val adbManager: AdbManager,
) {
    private data class Cached(val fingerprint: String, val install: TrackedInstall)

    private val cache = HashMap<String, Cached>()

    /**
     * Resolves [records] against [deviceId], or against no device at all when it
     * is null. Returns one entry per record, keyed by [PatchedAppRecord.trackingKey].
     * A record missing from the result is one whose state is not yet known.
     */
    suspend fun resolve(
        records: Collection<PatchedAppRecord>,
        deviceId: String?,
        morpheSignatureIds: Set<String>,
    ): TrackedInstallSnapshot = withContext(Dispatchers.IO) {
        // One `pm list packages` for the whole history rather than one per record.
        // A device that cannot be listed is treated as absent: claiming every app
        // was uninstalled because a single ADB call failed is the worse answer.
        val installedPackages = deviceId?.let { id ->
            adbManager.listInstalledPackages(id).getOrElse { e ->
                Logger.debug("Could not list packages on $id (${e.message}); treating the device as unavailable")
                null
            }
        }
        val attached = installedPackages != null
        val morpheInstallers = adbManager.morpheInstallerCandidates()
        val signatureKey = morpheSignatureIds.sorted().joinToString(",")

        val resolved = LinkedHashMap<String, TrackedInstall>(records.size)
        for (record in records) {
            val output = File(record.outputApkPath)
            val devicePackage = record.installedPackageName
            val packageInstalled = installedPackages?.contains(devicePackage) == true
            val fingerprint = fingerprint(record, deviceId, packageInstalled, output, signatureKey)
            val hit = synchronized(cache) { cache[record.trackingKey]?.takeIf { it.fingerprint == fingerprint } }
            if (hit != null) {
                resolved[record.trackingKey] = hit.install
                continue
            }

            val devicePackageInfo = if (packageInstalled && deviceId != null) {
                runCatching { adbManager.getDevicePackageInfo(deviceId, devicePackage) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            } else {
                null
            }

            val install = TrackedInstall(
                deviceState = resolveDeviceInstallState(
                    deviceAttached = attached,
                    packageInstalled = packageInstalled,
                    deviceSignatureId = devicePackageInfo?.signatureId,
                    morpheSignatureIds = morpheSignatureIds,
                    installerPackage = devicePackageInfo?.installerPackage,
                    morpheInstallers = morpheInstallers,
                    installedAfterPatching = installedAfterPatching(
                        firstInstallTime = devicePackageInfo?.firstInstallTime,
                        patchedAt = record.patchedAt,
                    ),
                ),
                artifactState = resolveArtifactState(output, record.outputApkSize),
                deviceVersion = devicePackageInfo?.versionName,
                deviceApkPath = devicePackageInfo?.apkPath,
            )
            synchronized(cache) { cache[record.trackingKey] = Cached(fingerprint, install) }
            resolved[record.trackingKey] = install
        }

        // Records that left the history have no verdict to keep warm
        synchronized(cache) { cache.keys.retainAll(resolved.keys) }
        TrackedInstallSnapshot(
            deviceAttached = attached,
            installedPackages = installedPackages.orEmpty(),
            byTrackingKey = resolved,
        )
    }

    /** Drops the remembered verdict for [trackingKey] so the next read inspects again. */
    fun invalidate(trackingKey: String) {
        synchronized(cache) { cache.remove(trackingKey) }
    }

    /** Drops every remembered verdict, e.g. after an install or uninstall. */
    fun invalidateAll() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * Everything that can change what [resolve] answers, read without a further
     * device call. Package presence comes from the one `pm list packages` above,
     * so an install or uninstall made outside Morphe invalidates the verdict on
     * its own; only a same-package in-place update needs [invalidateAll].
     */
    private fun fingerprint(
        record: PatchedAppRecord,
        deviceId: String?,
        packageInstalled: Boolean,
        output: File,
        signatureKey: String,
    ): String = buildString {
        append(deviceId).append('|')
        append(packageInstalled).append('|')
        append(record.installedPackageName).append('|')
        append(record.apkVersion).append('|')
        append(record.patchedAt).append('|')
        append(output.length()).append(':').append(output.lastModified()).append('|')
        append(signatureKey)
    }
}
