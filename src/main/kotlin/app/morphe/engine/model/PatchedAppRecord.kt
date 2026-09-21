/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.engine.model

import kotlinx.serialization.Serializable

/**
 * A record of one app the user has patched, the "recall" data behind the
 * patched-app history (see `patched-app-recall-plan.md`).
 *
 * Written by both the CLI and the GUI on a successful patch (the store lives in
 * the shared engine layer so the two pipelines feed one history), and read back
 * to surface "you've patched this before / an update is available" UX.
 *
 * Identified by [id], not by [packageName]. One app can be patched into several
 * installs of its own — a clone carries a package name it does not share with
 * the app it was copied from — and all of them key their bundle data off the
 * original [packageName]. Keying the history off the package would let the
 * second build overwrite the first.
 */
@Serializable
data class PatchedAppRecord(
    /**
     * Stable identity of this record, which is the package the build installs
     * under. Blank only in records written before the field existed;
     * [trackingKey] is what every reader should use, and
     * `PatchedAppStore` fills this in on migration.
     */
    val id: String = "",
    /** The original (pre-patch) package name, which is what bundle data is keyed by. */
    val packageName: String,
    /**
     * Post-patch package as it installs on a device, differing from [packageName]
     * when a rename patch was applied (e.g. `com.google.android.youtube` →
     * `app.morphe.android.youtube`). Read from the output APK's manifest at patch
     * time. Null/blank = no rename (same as [packageName]). Mirrors Manager's
     * `currentPackageName`.
     */
    val currentPackageName: String? = null,
    /**
     * Whether this record is a copy of the app rather than the app's own install.
     *
     * Recorded when the copy is made, never inferred from a package name that
     * differs from [packageName]: patches rename an app for reasons of their own
     * and such a build is still the app's own install.
     */
    val isClone: Boolean = false,
    /** Human-readable app name shown in UI. */
    val displayName: String,
    /**
     * The `appIconColor` the patch bundle declared for this app at patch time,
     * retained so a card keeps its color once the source that declared it is
     * gone. Null when the bundle declared none.
     */
    val appIconColorHex: String? = null,
    /** APK version at patch time. */
    val apkVersion: String,
    val apkVersionCode: Int? = null,

    /**
     * The original (pre-patch) APK a repatch reads. For records saved since Morphe took over
     * the original, this is the Morphe-managed copy under `morphe-data/original-apks/`.
     * Older records, and patches made with original-APK retention off or archiving failed,
     * hold the user's own path, which may no longer exist. Resolve through
     * `OriginalApkRepository.resolveInputApk` rather than trusting this directly.
     */
    val inputApkPath: String,
    /** Output APK path we wrote. Its existence is the "is it still here" signal. */
    val outputApkPath: String,

    /**
     * Baseline integrity fingerprint of the output APK, captured at patch time.
     * Lets us later detect when the patched APK was changed outside Morphe
     * (hash mismatch) vs untouched. Null only for records written before this
     * field existed, or if hashing failed. See `patched-app-recall-plan.md` (Phase 6).
     */
    val outputApkSha256: String? = null,
    /** Size in bytes of the output APK at patch time. */
    val outputApkSize: Long = 0,

    /** Bundle source id → set of enabled patch unique ids (same shape as the selection state). */
    val patchSelectionByBundle: Map<String, Set<String>> = emptyMap(),
    /** "patchName.optionKey" → raw value string (deserialized by patch type at apply time). */
    val patchOptionValues: Map<String, String> = emptyMap(),

    /**
     * Which sources + versions were enabled at patch time. "Update available"
     * detection compares current source versions against this snapshot, and the
     * app information dialog names the source from it once the source itself is
     * gone.
     */
    val sourcesSnapshot: List<PatchedSourceSnapshot> = emptyList(),

    /** Epoch millis of when the patch completed. */
    val patchedAt: Long,
    val patchedWithMorpheVersion: String,
) {
    /** Package actually installed on a device (post-rename if applicable). */
    val installedPackageName: String get() = currentPackageName?.takeIf { it.isNotBlank() } ?: packageName

    /**
     * What this record is filed under. Falls back to the installed package so a
     * record written before [id] existed still resolves to the same key the
     * migration assigns it.
     */
    val trackingKey: String get() = id.takeIf { it.isNotBlank() } ?: installedPackageName

    /** Whether a patch renamed the package, which a clone does not on its own. */
    val isRenamed: Boolean get() = installedPackageName != packageName

    /** Total patches applied across every bundle that contributed to this build. */
    val appliedPatchCount: Int get() = patchSelectionByBundle.values.sumOf { it.size }

    /** Bundles that actually contributed a patch, which an enabled-but-unused one did not. */
    val contributingBundleCount: Int get() = patchSelectionByBundle.count { it.value.isNotEmpty() }

    @Serializable
    data class PatchedSourceSnapshot(
        val sourceId: String,
        val sourceName: String,
        /** `.mpp` release version, e.g. `v1.5.0`. */
        val version: String,
    )
}
