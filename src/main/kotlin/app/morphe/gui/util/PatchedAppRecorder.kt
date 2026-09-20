/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

import app.morphe.engine.OriginalApkRepository
import app.morphe.engine.PatchedAppStore
import app.morphe.engine.UpdateChecker
import app.morphe.engine.model.PatchedAppRecord
import app.morphe.engine.util.ApkManifest
import app.morphe.engine.util.ApkManifestReader
import app.morphe.engine.util.BundleFormats
import app.morphe.engine.util.FileChecksum
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * What happens once a patch has succeeded, shared by Expert and Quick mode so the two
 * can't diverge: hand the original APK over to Morphe and record the patched app.
 *
 * Order matters, because the last step deletes the user's file:
 * 1. Archive the original ([OriginalApkRepository.saveOriginalApk]: copied, verified, registered).
 * 2. Persist the [PatchedAppRecord], pointing at the archive so it, not the user's file, is
 *    what a repatch reads.
 * 3. Only then remove the user's copy ([OriginalApkRepository.discardSource]).
 *
 * A failure at any step leaves the user's original where it was. If archiving fails, the
 * record still goes in, referencing the user's own path (the pre-canonical behavior) so the
 * app shows up in history and can still be repatched from there. If the record can't be
 * persisted, nothing is deleted and the failure is returned.
 */
class PatchedAppRecorder(
    private val patchedAppStore: PatchedAppStore,
    private val originalApkRepository: OriginalApkRepository,
) {

    /**
     * Records a finished patch of [inputApk] into [outputApk]. Runs to completion even if the
     * calling screen goes away mid-way: a half-finished archive/record sequence is the one
     * outcome to avoid. Failures are logged and returned, never thrown.
     *
     * [packageName] is the original (pre-rename) package; when blank it is taken from [patchResult].
     */
    suspend fun record(
        packageName: String,
        displayName: String,
        inputApk: File,
        outputApk: File,
        patchResult: PatchResult,
        sourcesSnapshot: List<PatchedAppRecord.PatchedSourceSnapshot>,
        patchSelectionByBundle: Map<String, Set<String>> = emptyMap(),
        patchOptionValues: Map<String, String> = emptyMap(),
    ): Result<Unit> = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
            val pkg = packageName.ifEmpty { patchResult.packageName }
            if (pkg.isEmpty()) {
                Logger.warn("Patched app not recorded: the patch result carries no package name")
                return@runCatching
            }

            val (sha, size) = FileChecksum.fingerprintOrNull(outputApk.path)
            // The output's manifest is the source for the post-rename package only. The version
            // is the ORIGINAL's, so the archive and the record describe the APK that was patched
            // (falling back to the output's, then the patcher's numeric versionCode).
            val outputManifest = ApkManifestReader.read(outputApk)
            val originalManifest = readOriginalManifest(inputApk)
            val version = originalManifest?.versionName?.takeIf { it.isNotBlank() }
                ?: outputManifest?.versionName?.takeIf { it.isNotBlank() }
                ?: patchResult.packageVersion

            val archived = try {
                originalApkRepository.saveOriginalApk(pkg, version, inputApk)
            } catch (e: Exception) {
                Logger.error("Could not archive the original APK for $pkg; keeping the user's file", e)
                null
            }

            patchedAppStore.upsert(
                PatchedAppRecord(
                    packageName = pkg,
                    currentPackageName = outputManifest?.packageName,
                    displayName = displayName.ifEmpty { pkg },
                    apkVersion = version,
                    apkVersionCode = originalManifest?.versionCode ?: outputManifest?.versionCode,
                    inputApkPath = (archived ?: inputApk).absolutePath,
                    outputApkPath = outputApk.absolutePath,
                    outputApkSha256 = sha,
                    outputApkSize = size,
                    patchSelectionByBundle = patchSelectionByBundle,
                    patchOptionValues = patchOptionValues,
                    sourcesSnapshot = sourcesSnapshot,
                    patchedAt = System.currentTimeMillis(),
                    patchedWithMorpheVersion = UpdateChecker.currentVersion() ?: "unknown",
                )
            )

            // Everything that references the archive is durable now, so the user's copy is redundant.
            if (archived != null) originalApkRepository.discardSource(inputApk, archived)
        }.onFailure { Logger.error("Failed to record patched app", it) }
    }

    /** Manifest of the original input; for a split-APK bundle, that of its base APK. */
    private fun readOriginalManifest(inputApk: File): ApkManifest? {
        if (!BundleFormats.isBundle(inputApk)) return ApkManifestReader.read(inputApk)
        val base = FileUtils.extractBaseApkFromBundle(inputApk) ?: return null
        return try {
            ApkManifestReader.read(base)
        } finally {
            base.delete()
        }
    }
}
