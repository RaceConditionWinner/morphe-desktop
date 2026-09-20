/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.repository

import app.morphe.engine.MultiSourceLoader
import app.morphe.engine.patches.PatchProvider
import app.morphe.engine.patches.RemotePatchSourceFactory
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import app.morphe.gui.util.Logger
import io.ktor.client.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which top-level UI mode is currently visible. Used by [PatchSourceManager]
 * to gate per-VM patch loading so only the visible mode's VM does the work.
 */
enum class ActiveMode { QUICK, EXPERT }

/**
 * Manages PatchRepository instances for different patch sources.
 * Creates and caches a PatchRepository per GitHub-based source.
 * Emits [sourceVersion] whenever the active source changes so the UI can react.
 */
class PatchSourceManager(
    private val httpClient: HttpClient,
    private val configRepository: ConfigRepository,
    private val blocklistRepository: BlocklistRepository,
    private val sourceMuteRepository: SourceMuteRepository,
    private val patchPreferencesRepository: PatchPreferencesRepository,
) {
    private val repositories = mutableMapOf<String, PatchRepository>()

    // Cached active state for synchronous access
    private var cachedActiveRepo: PatchRepository? = null
    private var cachedActiveSource: PatchSource? = null

    // Snapshot of currently-enabled sources for sync access. Updated on initialize()
    // and whenever setSourceEnabled / addSource / removeSource fires.
    private var cachedEnabledSources: List<PatchSource> = emptyList()

    // Incremented on every source switch / enable change so Compose can key on it
    private val _sourceVersion = MutableStateFlow(0)
    val sourceVersion: StateFlow<Int> = _sourceVersion.asStateFlow()

    // Observable list of ALL sources (enabled + disabled). Drives the
    // SourceManagementSheet which needs to render every source with a toggle.
    private val _allSources = MutableStateFlow<List<PatchSource>>(emptyList())
    val allSources: StateFlow<List<PatchSource>> = _allSources.asStateFlow()

    /**
     * Which mode's ViewModel is currently driving the UI. Used by both
     * [HomeViewModel] (EXPERT) and [QuickPatchViewModel] (QUICK) to skip
     * patch-loading when they're not visible. Both VMs can be alive
     * simultaneously (QuickVM is `remember`-scoped to App.kt, HomeVM is
     * created by Voyager when the Navigator branch composes), and without
     * this gate they'd race to download the same sources twice on every
     * cache clear / source toggle.
     */
    private val _activeMode = MutableStateFlow(ActiveMode.QUICK)
    val activeMode: StateFlow<ActiveMode> = _activeMode.asStateFlow()

    fun setActiveMode(mode: ActiveMode) {
        if (_activeMode.value != mode) {
            Logger.info("PatchSourceManager: active mode → $mode")
            _activeMode.value = mode
        }
    }

    /**
     * Load the active source from config and cache its PatchRepository.
     * Call once at app startup (from a LaunchedEffect).
     */
    suspend fun initialize() {
        // Load first (fast, disk-only) so isSourceBlocked/addSource enforce the
        // last-known state even before refresh() completes; then best-effort
        // refresh from the network. A failed refresh silently keeps the cached
        // state — see BlocklistRepository.refresh().
        blocklistRepository.loadFromCache()
        blocklistRepository.refresh()

        configRepository.migrateSourceChannelFlags()
        val source = configRepository.getActivePatchSource()
        cachedActiveSource = source
        cachedActiveRepo = getRepositoryForSource(source)
        refreshEnabledSources()
        Logger.info("PatchSourceManager initialized with source '${source.name}' (type=${source.type})")
        Logger.info("Enabled sources: ${cachedEnabledSources.joinToString { it.name }}")
    }

    /**
     * Switch the active source, persist it, and signal the UI.
     */
    suspend fun switchSource(id: String) {
        configRepository.setActivePatchSource(id)
        val source = configRepository.getActivePatchSource()
        cachedActiveSource = source
        cachedActiveRepo = getRepositoryForSource(source)
        _sourceVersion.value++
        Logger.info("Switched active patch source to '${source.name}' (type=${source.type})")
    }

    /**
     * Whether the current active source is a local .mpp file.
     */
    fun isLocalSource(): Boolean {
        return cachedActiveSource?.type == PatchSourceType.LOCAL
    }

    /**
     * Get the local .mpp file path if the active source is LOCAL, null otherwise.
     */
    fun getLocalFilePath(): String? {
        val source = cachedActiveSource ?: return null
        return if (source.type == PatchSourceType.LOCAL) source.filePath else null
    }

    /**
     * Get the display name of the active source.
     */
    fun getActiveSourceName(): String {
        return cachedActiveSource?.name ?: "Morphe Patches"
    }

    /**
     * Whether the active source is the built-in Morphe default.
     */
    fun isDefaultSource(): Boolean {
        return cachedActiveSource?.type == PatchSourceType.DEFAULT
    }

    /**
     * Get the cached active PatchRepository synchronously.
     * Returns null for LOCAL sources (no GitHub API needed).
     * Falls back to default repo if not yet initialized and source is not LOCAL.
     */
    fun getActiveRepositorySync(): PatchRepository {
        return cachedActiveRepo ?: defaultMorpheRepository().also {
            if (!isLocalSource()) cachedActiveRepo = it
        }
    }

    /**
     * Build the fallback PatchRepository pointed at the built-in Morphe
     * repo (`MorpheApp/morphe-patches` on GitHub). Used when the active
     * source isn't yet known.
     */
    private fun defaultMorpheRepository(): PatchRepository {
        val remote = RemotePatchSourceFactory.build(
            PatchProvider.GITHUB,
            "MorpheApp/morphe-patches",
            httpClient,
        )
        return PatchRepository(remote)
    }

    /**
     * Get the PatchRepository for the currently active source (suspend version).
     * For LOCAL sources, returns null (caller should use the file path directly).
     */
    suspend fun getActiveRepository(): PatchRepository? {
        val source = configRepository.getActivePatchSource()
        return getRepositoryForSource(source)
    }

    /**
     * Get the PatchRepository for a specific source.
     * Returns null for LOCAL sources (no remote API needed).
     */
    fun getRepositoryForSource(source: PatchSource): PatchRepository? {
        if (source.type == PatchSourceType.LOCAL) return null

        return repositories.getOrPut(source.id) {
            val repoPath = extractRepoPath(source)
            // Map the GUI's persisted source type to the engine's provider
            // enum. DEFAULT inherits GitHub (Morphe Patches lives there).
            val provider = when (source.type) {
                PatchSourceType.GITLAB -> PatchProvider.GITLAB
                else -> PatchProvider.GITHUB
            }
            Logger.info("Creating PatchRepository for source '${source.name}' (repo=$repoPath, provider=$provider)")
            val remote = RemotePatchSourceFactory.build(provider, repoPath, httpClient)
            PatchRepository(remote)
        }
    }

    /**
     * Get the active patch source config.
     */
    suspend fun getActiveSource(): PatchSource {
        return configRepository.getActivePatchSource()
    }

    /**
     * Extract "owner/repo" from a PatchSource's URL. Works for both GitHub
     * and GitLab hosts. Falls back to the built-in default repo when no URL
     * is configured (e.g. for the DEFAULT source on first launch).
     */
    private fun extractRepoPath(source: PatchSource): String {
        val url = source.url ?: return "MorpheApp/morphe-patches"
        return url
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("https://gitlab.com/")
            .removePrefix("http://gitlab.com/")
            .removeSuffix("/")
            .removeSuffix(".git")
    }

    /**
     * Notify that cached patch files were deleted (e.g. via "Clear Cache" in settings).
     * Clears cached repo state and bumps [sourceVersion] so ViewModels reload.
     */
    fun notifyCacheCleared() {
        cachedActiveRepo?.clearCache()
        _sourceVersion.value++
    }

    // ── Multi-source API ──────────────────────────────────────────────────────

    /**
     * Snapshot of currently-enabled sources, in config order. Synchronous.
     */
    fun getEnabledSourcesSync(): List<PatchSource> = cachedEnabledSources

    /**
     * Pair each enabled source with its [PatchRepository]. The repo is null for LOCAL
     * sources. Callers should use [PatchSource.filePath] directly in that case.
     */
    fun getEnabledRepositories(): List<Pair<PatchSource, PatchRepository?>> =
        cachedEnabledSources.map { it to getRepositoryForSource(it) }

    /**
     * Toggle enablement of a source. Persists, refreshes the cached snapshot, and
     * bumps [sourceVersion] so consumers reload. Default-source safety net is
     * applied at the [ConfigRepository] layer.
     */
    suspend fun setSourceEnabled(id: String, enabled: Boolean) {
        configRepository.setPatchSourceEnabled(id, enabled)
        refreshEnabledSources()
        _sourceVersion.value++
        Logger.info("Source '$id' enabled=$enabled. Enabled now: ${cachedEnabledSources.joinToString { it.name }}")
    }

    /**
     * Add a new source. Persists and refreshes the cached snapshot.
     */
    suspend fun addSource(source: PatchSource) {
        val blocklistKey = source.url?.let(blocklistRepository::toBlocklistKey)
        if (blocklistKey != null && blocklistRepository.isBlocked(blocklistKey)) {
            Logger.warn("Refused to add blocked source: $blocklistKey (${source.url})")
            return
        }
        configRepository.addPatchSource(source)
        refreshEnabledSources()
        _sourceVersion.value++
    }

    /**
     * Whether [source] is currently on the remote blocklist. Exposed for the UI
     * (e.g. to grey out / flag an already-added source that's since been
     * blocked) — mirrors morphe-manager's `PatchBundleRepository.blockedSources`.
     */
    fun isSourceBlocked(source: PatchSource): Boolean {
        val key = source.url?.let(blocklistRepository::toBlocklistKey) ?: return false
        return blocklistRepository.isBlocked(key)
    }

    /**
     * Remove a source by id. Refuses non-deletable (default) sources. Drops the
     * cached repo for that id so a re-add doesn't reuse stale state.
     */
    suspend fun removeSource(id: String) {
        configRepository.removePatchSource(id)
        repositories.remove(id)
        // A removed source can't stay "muted for" anything, and a stale strike against a
        // file that no longer exists would only ever misattribute a future unrelated source
        // reusing the same id.
        sourceMuteRepository.resetForSource(id)
        patchPreferencesRepository.resetForSource(id)
        MultiSourceLoader.forgetLoadGuardState(id)
        refreshEnabledSources()
        _sourceVersion.value++
    }

    // ── Source muting ────────────────────────────────────────────────────────
    //
    // Narrows which of the *enabled* sources are offered for one particular app, without
    // touching that source for anything else. See [SourceMuteRepository] for the persisted
    // state and the invariants (a mute is never recorded if it would leave an app with
    // nothing to patch from).

    /**
     * The enabled sources actually offered for [packageName]: [getEnabledSourcesSync] with
     * any sources [packageName] has been muted from removed — unless removing them would
     * leave nothing behind, in which case every enabled source is offered as a fallback
     * (see [withoutMutedSources]).
     */
    suspend fun effectiveSourcesFor(packageName: String): List<PatchSource> {
        val muted = sourceMuteRepository.getMutedFor(packageName)
        return cachedEnabledSources.withoutMutedSources(muted) { it.id }
    }

    suspend fun isSourceMutedForApp(packageName: String, sourceId: String): Boolean =
        sourceId in sourceMuteRepository.getMutedFor(packageName)

    suspend fun mutedSourceIdsForApp(packageName: String): Set<String> =
        sourceMuteRepository.getMutedFor(packageName)

    /**
     * Mutes [sourceId] for [packageName], unless doing so would leave the app with nothing
     * to patch from — the same "last usable source" protection as Manager's `appsToKeepFrom`.
     * Returns false (and does nothing) when it would.
     *
     * @param coveredByThisApp The set of enabled source ids that actually have a patch
     *   targeting [packageName] (e.g. from `EnabledSourcesLoader.Result.guiPatchesBySource`
     *   filtered by `Patch.compatiblePackages`), when the caller has already computed it.
     *   Precise: matches Manager's `appsToKeepFrom` exactly. When omitted, falls back to
     *   "does this app have more than one *enabled* source at all" — a safe but coarser
     *   approximation (it can't tell a source with no matching patch from one that does),
     *   used by call sites that haven't loaded per-app coverage.
     */
    suspend fun muteSourceForApp(
        packageName: String,
        sourceId: String,
        coveredByThisApp: Set<String>? = null,
    ): Boolean {
        if (coveredByThisApp != null) {
            val alreadyMuted = sourceMuteRepository.getMutedFor(packageName)
            val keep = appsToKeepFrom(
                sourceId = sourceId,
                apps = setOf(packageName),
                coveredBy = mapOf(packageName to coveredByThisApp),
                keptFrom = mapOf(packageName to alreadyMuted),
            )
            if (packageName !in keep) return false
        } else {
            val currentlyOffered = effectiveSourcesFor(packageName).map { it.id }.toSet()
            if (currentlyOffered.size <= 1 && sourceId in currentlyOffered) return false
        }
        sourceMuteRepository.mute(packageName, sourceId)
        return true
    }

    suspend fun unmuteSourceForApp(packageName: String, sourceId: String) =
        sourceMuteRepository.unmute(packageName, sourceId)

    suspend fun unmuteAllSourcesForApp(packageName: String) =
        sourceMuteRepository.unmuteAll(packageName)

    /**
     * Persist a new source ordering. Order affects only the display-name
     * tiebreak and UI presentation, not which patches load.
     */
    suspend fun reorderSources(orderedIds: List<String>) {
        configRepository.reorderPatchSources(orderedIds)
        refreshEnabledSources()
        _sourceVersion.value++
    }

    /**
     * Update an existing source (e.g. rename). For non-deletable sources, only updates the pre-release flag.
     */
    suspend fun updateSource(updated: PatchSource) {
        configRepository.updatePatchSource(updated)
        // Drop the cached repo so the new url/name is picked up on next access.
        repositories.remove(updated.id)
        refreshEnabledSources()
        _sourceVersion.value++
    }

    private suspend fun refreshEnabledSources() {
        val all = configRepository.loadConfig().patchSource
        val enabled = all.filter { it.enabled }
        cachedEnabledSources = enabled
        _allSources.value = all
    }
}
