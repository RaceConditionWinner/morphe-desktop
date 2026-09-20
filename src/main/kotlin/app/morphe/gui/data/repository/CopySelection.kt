/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import kotlinx.serialization.json.JsonElement

/**
 * One other (source, app) pairing that has a saved selection worth offering to copy from,
 * enriched for display and ranking. Ported from Manager's `CopySelectionCandidate` /
 * `CopySelectionLoader`.
 */
data class CopySelectionCandidate(
    val sourceId: String,
    val sourceName: String,
    val packageName: String,
    val packageDisplayName: String,
    /** How many patches this candidate's saved selection turned on. */
    val sourcePatchCount: Int,
    /** Of those, how many exist (by patch name) in the target bundle's current patch set. */
    val applicableCount: Int,
    /** Same remote endpoint as the target, imported under a different source id. */
    val isSameSource: Boolean,
    val isSamePackage: Boolean,
)

/** What copying one [CopySelectionCandidate] actually contributes, already filtered against
 *  the target bundle's current patches — nothing here refers to a patch the target doesn't have.
 *  Keyed by patch **name**, matching [PatchPreferencesRepository]'s own storage format — a
 *  patch's [app.morphe.gui.data.model.Patch.uniqueId] additionally folds in its compatible
 *  packages and description, which is finer-grained than what gets persisted. Callers that
 *  need uniqueIds (the patch-selection screen's [selectedByBundle]-shaped state) resolve
 *  name -> uniqueId themselves against their own current bundle, same as loading saved
 *  preferences already does — see [PatchSelectionViewModel]'s init block. */
data class CopiedSelection(
    val patchNames: Set<String>,
    val options: Map<String, Map<String, JsonElement>>,
) {
    val isEmpty: Boolean get() = patchNames.isEmpty() && options.isEmpty()
}

/**
 * Assembles candidates for "copy selection from another app/source", excluding the target
 * itself, with each row enriched by its overlap with [targetPatchNames]. Sorted so the
 * most useful match is first: same source endpoint, then same package, then largest overlap.
 *
 * Matches by *saved patch identity* (name — see [CopiedSelection]) throughout, never by list
 * position — a candidate whose bundle has since renamed or dropped a patch simply scores
 * lower / offers less, rather than the copy landing on the wrong row.
 *
 * @param targetPatchNames The current target bundle's patch **names** (`Patch.name`, not
 *   `Patch.uniqueId`) — what [PatchPreferencesRepository] actually keys saved selections by.
 * @param displayNameFor Resolves a package name to what the UI should show for it (app label
 *   if known, else the package name itself). Desktop has no single always-available app-data
 *   resolver the way Manager does (Manager reads it from the installed-package cache); callers
 *   pass whatever they already have — [app.morphe.gui.data.repository.PatchSourceManager]'s
 *   callers typically already have this from the supported-apps list.
 */
suspend fun loadCopySelectionCandidates(
    patchPreferencesRepository: PatchPreferencesRepository,
    allSources: List<PatchSource>,
    targetPackageName: String,
    targetSourceId: String,
    targetPatchNames: Set<String>,
    displayNameFor: (String) -> String = { it },
): List<CopySelectionCandidate> {
    val all = patchPreferencesRepository.allSelections()
    if (all.isEmpty()) return emptyList()

    val sourcesById = allSources.associateBy { it.id }
    val targetEndpointKey = sourcesById[targetSourceId]?.let(::endpointKey)

    val candidates = all.flatMap { (sourceId, byPkg) ->
        byPkg.mapNotNull { (packageName, bundle) ->
            if (sourceId == targetSourceId && packageName == targetPackageName) return@mapNotNull null

            // A source removed since the selection was saved has nothing left to copy
            // into — its saved rows are stale, ignored here rather than shown as a dead end.
            val source = sourcesById[sourceId] ?: return@mapNotNull null

            val sourcePatchNames = bundle.patches.filterValues { it.enabled }.keys
            if (sourcePatchNames.isEmpty()) return@mapNotNull null

            val applicable = sourcePatchNames.count { it in targetPatchNames }
            CopySelectionCandidate(
                sourceId = sourceId,
                sourceName = source.name,
                packageName = packageName,
                packageDisplayName = displayNameFor(packageName),
                sourcePatchCount = sourcePatchNames.size,
                applicableCount = applicable,
                isSameSource = targetEndpointKey != null && endpointKey(source) == targetEndpointKey,
                isSamePackage = packageName == targetPackageName,
            )
        }
    }

    return candidates.sortedWith(
        compareByDescending<CopySelectionCandidate> { it.isSameSource }
            .thenByDescending { it.isSamePackage }
            .thenByDescending { it.applicableCount }
            .thenBy { it.sourceName.lowercase() },
    )
}

/**
 * What copying [candidate] actually contributes: its saved enabled patch names and their
 * option values, reduced to the ones that still exist in [targetPatchNames]. Null when
 * nothing survives the filter — the caller should treat that as "nothing to copy", not
 * silently apply an empty selection.
 */
suspend fun resolveCopySelection(
    patchPreferencesRepository: PatchPreferencesRepository,
    candidate: CopySelectionCandidate,
    targetPatchNames: Set<String>,
): CopiedSelection? {
    val bundle = patchPreferencesRepository.get(candidate.sourceId, candidate.packageName) ?: return null

    val patchNames = bundle.patches.asSequence()
        .filter { (name, entry) -> entry.enabled && name in targetPatchNames }
        .map { it.key }
        .toSet()

    val options = bundle.patches.asSequence()
        .filter { (name, entry) -> name in targetPatchNames && entry.options.isNotEmpty() }
        .associate { (name, entry) -> name to entry.options }

    val result = CopiedSelection(patchNames, options)
    return result.takeUnless { it.isEmpty }
}

/**
 * Identity used to detect the same remote source imported twice under different source ids,
 * so it can be ranked first ([CopySelectionCandidate.isSameSource]) — it's the case most
 * likely to actually be useful (the exact same bundle, applied to a different app). Null for
 * local sources: two folder sources pointing at the same directory aren't meaningfully "the
 * same source" the way two imports of one GitHub repo are, so they're never favored on that
 * basis alone (still ranked, just not boosted).
 */
private fun endpointKey(source: PatchSource): String? =
    if (source.type == PatchSourceType.LOCAL) null else source.url?.lowercase()
