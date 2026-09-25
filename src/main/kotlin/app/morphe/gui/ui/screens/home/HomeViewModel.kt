/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home

import app.morphe.engine.MorpheData
import app.morphe.engine.PatchEngine.Config.Companion.DEFAULT_KEYSTORE_ALIAS
import app.morphe.engine.OriginalApkRepository
import app.morphe.engine.PatchedAppStore
import app.morphe.engine.UpdateInfo
import app.morphe.engine.model.PatchedAppRecord
import app.morphe.engine.readableMessage
import app.morphe.engine.util.ApkManifestReader
import app.morphe.engine.util.SignatureIdentity
import app.morphe.gui.data.constants.AppConstants
import app.morphe.gui.data.model.Patch
import app.morphe.gui.data.model.SourceVersionPref
import app.morphe.gui.data.model.SupportedApp
import app.morphe.gui.data.repository.ActiveMode
import app.morphe.gui.data.repository.ChangelogRepository
import app.morphe.gui.data.repository.changelogRequest
import app.morphe.gui.data.repository.ConfigRepository
import app.morphe.gui.data.repository.PatchRepository
import app.morphe.gui.data.repository.PatchSourceManager
import app.morphe.gui.data.repository.UpdateCheckRepository
import app.morphe.gui.ui.screens.home.components.AppListFilter
import app.morphe.gui.util.AdbException
import app.morphe.gui.util.AdbManager
import app.morphe.gui.util.ChecksumStatus
import app.morphe.gui.util.DeviceInstallState
import app.morphe.gui.util.DeviceMonitor
import app.morphe.gui.util.PatchedArtifactState
import app.morphe.gui.util.TrackedInstallResolver
import app.morphe.gui.util.TrackedInstallSnapshot
import app.morphe.gui.util.EnabledSourcesLoader
import app.morphe.gui.util.FileUtils
import app.morphe.gui.util.FormatUtils
import app.morphe.gui.util.Logger
import app.morphe.gui.util.PatchService
import app.morphe.gui.util.PatchException
import app.morphe.gui.util.SupportedAppExtractor
import app.morphe.gui.util.VersionResolution
import app.morphe.gui.util.VersionStatus
import app.morphe.gui.util.isNewerVersion
import app.morphe.gui.util.humanizePatchLoadError
import app.morphe.gui.util.resolveVersionStatus
import app.morphe.morphe_desktop.generated.resources.*
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

class HomeViewModel(
    private val patchSourceManager: PatchSourceManager,
    private val patchService: PatchService,
    private val configRepository: ConfigRepository,
    private val updateCheckRepository: UpdateCheckRepository,
    private val patchedAppStore: PatchedAppStore,
    private val changelogRepository: ChangelogRepository,
    private val originalApkRepository: OriginalApkRepository,
    private val adbManager: AdbManager = AdbManager(),
) : ScreenModel {

    /**
     * Device- and disk-side verdicts for the patched-app history. Owned here so
     * one reading of the device backs every card and the app information dialog.
     */
    private val trackedInstallResolver = TrackedInstallResolver(adbManager)

    /** Serializes the rebuild: a patch finishing and a device arriving can land together. */
    private val trackedStateLock = Mutex()

    private var patchRepository: PatchRepository = patchSourceManager.getActiveRepositorySync()
    private var localPatchFilePath: String? = patchSourceManager.getLocalFilePath()
    private var isDefaultSource: Boolean = patchSourceManager.isDefaultSource()

    private val _uiState = MutableStateFlow(HomeUiState(isDefaultSource = isDefaultSource))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Cached patches and supported apps
    private var cachedPatches: List<Patch> = emptyList()
    private var cachedPatchesFile: File? = null
    /** All resolved patch files across enabled sources. Single-element in
     *  single-source mode. Exposed via [getAllResolvedPatchFiles] for screens
     *  that navigate downstream and need to pass the full set. */
    private var cachedAllPatchFiles: List<File> = emptyList()
    private var loadJob: Job? = null

    fun getAllResolvedPatchFiles(): List<File> =
        cachedAllPatchFiles.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(cachedPatchesFile)

    /** Display names for each entry in [getAllResolvedPatchFiles], in the same
     *  order. Used by PatchSelectionScreen to badge patches with their source. */
    fun getAllResolvedPatchSourceNames(): List<String> =
        cachedSourcesResult
            ?.resolved
            ?.filter { it.patchFile != null }
            ?.map { it.source.name }
            ?: emptyList()

    init {
        screenModelScope.launch {
            val config = configRepository.loadConfig()
            val info = updateCheckRepository.getUpdateInfo()
            val dismissed = config.dismissedUpdateVersion
            val multiSourceShouldShow = !config.multiSourceHintDismissed &&
                    patchSourceManager.getEnabledSourcesSync().size > 1
            _uiState.value = _uiState.value.copy(
                updateInfo = info,
                dismissedUpdateVersion = dismissed,
                showMultiSourceHint = multiSourceShouldShow,
                appListFilter = runCatching {
                    AppListFilter.valueOf(config.homeAppListFilter)
                }.getOrDefault(AppListFilter.ALL),
                sortMode = HomeAppSortMode.fromPreference(config.homeAppSortMode),
            )
        }

        // React to history changes (a patch just completed, a record forgotten)
        // so badges + device state update immediately. No leave-and-return needed.
        screenModelScope.launch {
            patchedAppStore.changes.collect { refreshTrackedState() }
        }

        // Best-effort, once per app launch: drop original-APK records whose file
        // is gone (deleted outside the app, moved disk, etc.) and clean up any
        // `.part` staging file an interrupted save left behind — see
        // OriginalApkRepository.pruneMissingApks. Never blocks startup on this.
        screenModelScope.launch {
            runCatching { originalApkRepository.pruneMissingApks() }
        }

        // Optional device layer: when the selected ADB device changes (connect,
        // disconnect, authorize), refresh which patched apps are installed on it.
        // distinctUntilChanged on (id, ready) avoids re-querying on noisy emits.
        screenModelScope.launch {
            DeviceMonitor.state
                .map { it.selectedDevice?.id to (it.selectedDevice?.isReady == true) }
                .distinctUntilChanged()
                // A different device knows nothing about the last one's verdicts
                .collect { refreshTrackedState(forceDeviceRefresh = true) }
        }

        // Load patches whenever EXPERT becomes the active mode. StateFlow
        // emits its current value on subscribe, so this also covers the
        // "VM was just created while EXPERT is active" case. Replaces the
        // unconditional init-block load that used to fire even when the
        // user was actually in Quick mode (we don't construct HomeVM in
        // pure Quick sessions today, but Voyager keeps it alive across
        // mode switches, so the gate prevents wasted reloads on return).
        screenModelScope.launch {
            patchSourceManager.activeMode.collect { mode ->
                if (mode == ActiveMode.EXPERT) {
                    loadPatchesAndSupportedApps()
                }
            }
        }

        screenModelScope.launch {
            patchSourceManager.sourceVersion.drop(1).collect {
                // Skip when Quick mode is active. QuickPatchViewModel will
                // handle the reload for its (single) active source. Without
                // this gate both VMs fire parallel loads on every cache
                // clear, doubling network traffic and tripling the
                // cancellation cascade surface on slow connections.
                if (patchSourceManager.activeMode.value != ActiveMode.EXPERT) return@collect
                Logger.info("HomeVM: Source changed, reloading patches...")
                patchRepository = patchSourceManager.getActiveRepositorySync()
                localPatchFilePath = patchSourceManager.getLocalFilePath()
                isDefaultSource = patchSourceManager.isDefaultSource()
                lastLoadedVersion = null
                cachedPatchesFile = null
                // Preserve update banner state across source changes.
                val carriedUpdate = _uiState.value.updateInfo
                val carriedDismissed = _uiState.value.dismissedUpdateVersion
                _uiState.value = HomeUiState(
                    isDefaultSource = isDefaultSource,
                    updateInfo = carriedUpdate,
                    dismissedUpdateVersion = carriedDismissed,
                )
                loadPatchesAndSupportedApps(forceRefresh = true)
            }
        }
    }

    /**
     * Re-run the update check. Called by Settings after the user changes the
     * update channel preference so the banner state matches the new channel
     * without waiting for a restart.
     */
    fun refreshUpdateCheck() {
        Logger.info("HomeVM: refreshUpdateCheck() called")
        screenModelScope.launch {
            updateCheckRepository.clearCache()
            val info = updateCheckRepository.getUpdateInfo()
            val dismissed = configRepository.loadConfig().dismissedUpdateVersion
            Logger.info("HomeVM: refresh result — info=${info?.latestVersion}, dismissed=$dismissed")
            _uiState.value = _uiState.value.copy(
                updateInfo = info,
                dismissedUpdateVersion = dismissed,
                updateBannerSessionDismissed = false,
            )
        }
    }

    /**
     * Hide the update banner for the rest of this app session only. The banner
     * will reappear on next startup. Cheap path for users who want to be
     * reminded but not nagged right now.
     */
    fun dismissUpdateForSession() {
        _uiState.value = _uiState.value.copy(updateBannerSessionDismissed = true)
    }

    /**
     * Dismiss the multi-source intro hint persistently. One-shot.
     */
    fun dismissMultiSourceHint() {
        _uiState.value = _uiState.value.copy(showMultiSourceHint = false)
        screenModelScope.launch {
            configRepository.setMultiSourceHintDismissed()
        }
    }

    /** Dismiss the "some sources failed" banner for now. It re-appears if a different
     *  source starts failing on a later load. */
    fun dismissSourcesFailedBanner() {
        sourcesFailedBannerDismissed = true
        _uiState.value = _uiState.value.copy(showSourcesFailedBanner = false)
    }

    // Backing state for [dismissSourcesFailedBanner]: the failed-source set last dismissed,
    // and whether it is currently dismissed. Reset when the failed set changes (see load).
    private var lastFailedSourceIds: Set<String> = emptySet()
    private var sourcesFailedBannerDismissed: Boolean = false

    /**
     * Install the already-patched output APK for [packageName] onto the selected
     * device (no re-patch needed). On completion, refresh the device layer so the
     * "install pending" badge clears the moment the device reports the new version.
     */
    fun installPatchedApp(trackingKey: String) {
        val record = recordsByKey[trackingKey] ?: return
        val device = DeviceMonitor.state.value.selectedDevice ?: return
        if (!device.isReady || _uiState.value.installingApp != null) return
        _uiState.value = _uiState.value.copy(installingApp = trackingKey)
        screenModelScope.launch {
            // Always record a non-Play installer so the Play Store won't clobber
            // the patched app with an official update.
            val installer = adbManager.resolveSpoofInstaller(device.id)
            val result = adbManager.installApk(record.outputApkPath, device.id, installerPackage = installer)

            // Mirror ResultScreen: if the user opted into auto-routing links,
            // point the patched app at its web links right after a good install.
            if (result.isSuccess) {
                val config = configRepository.loadConfig()
                if (config.autoRouteLinksAfterInstall) {
                    adbManager.setLinkHandling(
                        deviceId = device.id,
                        patchedPackage = record.installedPackageName,
                        stockPackage = if (config.disableStockLinksAfterInstall) record.packageName else null,
                        enable = true,
                    )
                }
            }

            val installError = result.exceptionOrNull()?.let {
                val detail = (it as? AdbException)?.getUserMessage() ?: it.message ?: ""
                getString(Res.string.home_install_failed, detail)
            } ?: _uiState.value.error
            _uiState.value = _uiState.value.copy(
                installingApp = null,
                error = installError,
            )
            // Our own install changed what the device holds without changing
            // anything the resolver's fingerprint reads, so the verdict has to go.
            refreshTrackedState(forceDeviceRefresh = true)
        }
    }

    /**
     * Uninstall the patched app for [packageName] from the selected device. When
     * [alsoForget] is true, the recall record is removed afterward (uninstall +
     * delete history). Otherwise the record is kept (uninstall + keep history) so
     * the card stays as a not-installed entry the user can re-install/re-patch.
     *
     * Removing through Morphe (vs the launcher) keeps our device-state tracking
     * accurate. The tracked state is re-resolved on completion so the card flips
     * to not-installed immediately.
     */
    fun uninstallPatchedApp(trackingKey: String, alsoForget: Boolean) {
        val record = recordsByKey[trackingKey] ?: return
        val device = DeviceMonitor.state.value.selectedDevice ?: return
        if (!device.isReady || _uiState.value.uninstallingApp != null) return
        _uiState.value = _uiState.value.copy(uninstallingApp = trackingKey)
        screenModelScope.launch {
            val result = adbManager.uninstallApk(record.installedPackageName, device.id)
            val forgetFailure = if (result.isSuccess && alsoForget) deleteRecord(trackingKey) else null
            val uninstallError = result.exceptionOrNull()?.let {
                val detail = (it as? AdbException)?.getUserMessage() ?: it.message ?: ""
                getString(Res.string.home_uninstall_failed, detail)
            } ?: forgetFailure?.let {
                getString(Res.string.home_uninstall_forget_failed, it.message ?: "")
            } ?: _uiState.value.error
            _uiState.value = _uiState.value.copy(
                uninstallingApp = null,
                error = uninstallError,
            )
            refreshTrackedState(forceDeviceRefresh = true)
        }
    }

    fun setSortMode(mode: HomeAppSortMode) {
        if (_uiState.value.sortMode == mode) return
        _uiState.value = _uiState.value.copy(sortMode = mode)
        screenModelScope.launch { configRepository.setHomeAppSortMode(mode.name) }
    }

    /** Switch the home apps tab (ALL/YOURS) and remember it for next launch. */
    fun setAppListFilter(filter: AppListFilter) {
        if (_uiState.value.appListFilter == filter) return
        _uiState.value = _uiState.value.copy(appListFilter = filter)
        screenModelScope.launch { configRepository.setHomeAppListFilter(filter.name) }
    }

    /**
     * Hide the update banner persistently for the current available version.
     * The banner will reappear automatically when an even newer version becomes
     * available.
     */
    fun dismissUpdateForVersion() {
        val target = _uiState.value.updateInfo?.latestVersion ?: return
        _uiState.value = _uiState.value.copy(dismissedUpdateVersion = target)
        screenModelScope.launch {
            configRepository.setDismissedUpdateVersion(target)
        }
    }

    // Track the last loaded version to avoid reloading unnecessarily
    private var lastLoadedVersion: String? = null
    // Snapshot of per-source pinned versions used in the last load. Drives
    // refreshPatchesIfNeeded so we reload when ANY source's pin changes.
    private var lastLoadedVersionsBySource: Map<String, SourceVersionPref> = emptyMap()

    /**
     * Load patches from all enabled sources via [EnabledSourcesLoader] and build
     * the union supported-apps list. Single-enabled-source case produces output
     * equivalent to the pre-multi-source flow.
     */
    private fun loadPatchesAndSupportedApps(forceRefresh: Boolean = false) {
        // An explicit refresh refreshes what the sources say about themselves as well; the
        // patches reloading while their changelogs keep serving old text would read as a
        // refresh that half-happened
        if (forceRefresh) changelogRepository.invalidateAll()
        loadJob?.cancel()
        loadJob = screenModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPatches = true, patchLoadError = null, showSourcesFailedBanner = false)

            try {
                val enabled = patchSourceManager.getEnabledRepositories()
                if (enabled.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingPatches = false,
                        patchLoadError = getString(Res.string.home_no_sources_enabled)
                    )
                    return@launch
                }

                // Per-source pinned versions (with one-time migration from legacy
                // single-source field). Each source's resolver looks up its own pin.
                // no cross-source contamination.
                val prefs = configRepository.getSourceVersionPrefs()
                lastLoadedVersionsBySource = prefs
                val result = EnabledSourcesLoader.loadAll(enabled, patchService, prefs, configRepository.loadConfig().excludedMppPatterns)

                if (!result.anyLoaded) {
                    val firstThrowable = result.loaded.perSource.firstNotNullOfOrNull { it.error }
                    val rawTechnicalError = firstThrowable?.message
                        ?: result.resolved.firstNotNullOfOrNull { it.error }
                        ?: "Failed to load any patches"
                    // Log the real throwable (full stack). Never only a null/blank .message.
                    if (firstThrowable != null) {
                        Logger.error("Failed to load any patches: $rawTechnicalError", firstThrowable)
                    } else {
                        Logger.warn("Failed to load any patches: $rawTechnicalError")
                    }

                    val firstError = result.resolved.firstNotNullOfOrNull { it.getUserErrorMessage() }
                        ?: firstThrowable?.let { humanizePatchLoadError(it) }
                        ?: getString(Res.string.error_could_not_load_patches)
                    val friendlyError = if (firstError.contains("zip", ignoreCase = true) || firstError.contains("END header", ignoreCase = true)) {
                        getString(Res.string.home_patch_file_corrupted)
                    } else {
                        firstError
                    }
                    result.loaded.perSource.filter { !it.isSuccess }.forEach { src ->
                        val err = src.error
                        if (err != null) {
                            Logger.error("Patch source '${src.sourceName}' failed to load", err)
                        }
                    }
                    // Record the snapshot even though nothing loaded. The source sheet
                    // reads it for the per-row FAILED state, and a total failure is
                    // exactly when the user opens the sheet to find out which source
                    // broke. Every other reader filters on patchFile != null, so they
                    // see an empty result rather than stale success data.
                    cachedSourcesResult = result
                    _uiState.value = _uiState.value.copy(
                        isLoadingPatches = false,
                        patchLoadError = friendlyError
                    )
                    return@launch
                }

                cachedPatches = result.unionGuiPatches
                // Preserve existing single-file API for downstream navigation. In
                // multi-source mode this points at the first resolved source. The
                // full list is exposed via [getAllResolvedPatchFiles] and the
                // per-source data via [getResolvedSourcesSnapshot].
                val firstResolved = result.resolved.firstOrNull { it.patchFile != null }
                cachedPatchesFile = firstResolved?.patchFile
                cachedAllPatchFiles = result.resolved.mapNotNull { it.patchFile }
                lastLoadedVersion = firstResolved?.resolvedVersion
                cachedSourcesResult = result

                val supportedApps = SupportedAppExtractor.extractSupportedApps(result.unionGuiPatches)
                Logger.info(
                    "Loaded ${supportedApps.size} supported apps from " +
                            "${result.resolved.count { it.patchFile != null }} source(s): " +
                            supportedApps.map { it.displayName }
                )

                // Only flag the whole UI as offline when EVERY successfully-resolved
                // source had to fall back to its cache. One source being offline
                // while others are online shouldn't make the whole screen scream
                // "offline". That's a per-source state, surfaced in the sheet.
                val resolvedSources = result.resolved.filter { it.patchFile != null }
                val isOffline = resolvedSources.isNotEmpty() && resolvedSources.all { it.isOffline }
                val displayVersion = firstResolved?.resolvedVersion
                val sourceName = if (result.resolved.size == 1) {
                    firstResolved?.source?.name ?: patchSourceManager.getActiveSourceName()
                } else {
                    val count = result.resolved.count { it.patchFile != null }
                    getPluralString(Res.plurals.count_sources, count, count)
                }

                latestResolvedApps = null // fresh load — drop any stale eager-resolved apps

                // Partial-failure surfacing: some sources loaded, but others may have failed
                // (e.g. a bundle needing a newer patcher). Collect the failed source ids from
                // both the resolve phase and the load phase so the banner + per-row FAILED
                // state agree. Re-show the banner when the failed set changes, even if the
                // user dismissed a previous one.
                val failedSourceIds = buildSet {
                    result.resolved.forEach { if (it.error != null) add(it.source.id) }
                    result.loaded.perSource.forEach { if (!it.isSuccess) add(it.sourceId) }
                }
                if (failedSourceIds.isNotEmpty()) {
                    // One summary + full stack per failed source so partial failures are
                    // diagnosable from the log file (not only a red "Failed to load" LED).
                    val details = buildList {
                        result.resolved.forEach { r -> r.error?.let { add("${r.source.name}: $it") } }
                        result.loaded.perSource.forEach { s ->
                            if (!s.isSuccess) {
                                val err = s.error
                                add("${s.sourceName}: ${err?.readableMessage() ?: "failed to load"}")
                                if (err != null) {
                                    Logger.error("Patch source '${s.sourceName}' failed to load", err)
                                }
                            }
                        }
                    }
                    Logger.warn("Some patch sources failed to load — ${details.joinToString("; ")}")
                }
                if (failedSourceIds != lastFailedSourceIds) {
                    sourcesFailedBannerDismissed = false
                    lastFailedSourceIds = failedSourceIds
                }

                _uiState.value = _uiState.value.copy(
                    isLoadingPatches = false,
                    isOffline = isOffline,
                    supportedApps = supportedApps,
                    patchesVersion = displayVersion,
                    patchesChannel = firstResolved?.channel,
                    patchSourceName = sourceName,
                    patchLoadError = null,
                    showSourcesFailedBanner = failedSourceIds.isNotEmpty() && !sourcesFailedBannerDismissed,
                    failedSourcesCount = failedSourceIds.size,
                    failedSourceIds = failedSourceIds,
                )
                // Sources just (re)resolved, so every card's update and version
                // state has to be worked out against them again.
                rebuildTrackedState(forceDeviceRefresh = false)
                reanalyzeSelectedApk()
                eagerlyResolveLatestApps() // upgrade update-info to the LATEST patch's app versions
            } catch (e: CancellationException) {
                // Cancellation is normal coroutine bookkeeping (a newer load
                // superseded this one, or the screen left composition). Do NOT
                // write UI state. Otherwise a stale "Job was cancelled" can
                // clobber the in-flight successor's loading/success state.
                throw e
            } catch (e: Throwable) {
                // Throwable, not just Exception: a bundle built against a newer patcher
                // throws java.lang.Error (NoSuchMethodError / LinkageError) at link time.
                // As an Error it would slip past catch(Exception), leaving isLoadingPatches
                // stuck true and the loading skeleton animating forever with no way to reach
                // the source manager. Widening it guarantees loading always ends in a state.
                Logger.error("Failed to load patches and supported apps", e)
                _uiState.value = _uiState.value.copy(
                    isLoadingPatches = false,
                    patchLoadError = humanizePatchLoadError(e),
                )
            }
        }
    }

    /**
     * The patched-app history, keyed by [PatchedAppRecord.trackingKey] so the
     * app's own build and any copies of it stay apart.
     */
    private var recordsByKey: Map<String, PatchedAppRecord> = emptyMap()

    /** Per-record update guidance, recomputed whenever sources or history change. */
    private var updateInfoByKey: Map<String, RecallUpdateInfo> = emptyMap()

    /** The last reading of the device, shared by every card built from it. */
    private var trackedInstalls: TrackedInstallSnapshot = TrackedInstallSnapshot.None

    /** Cards the user hid, and supported versions they turned down. */
    private var homeAppPrefs: HomeAppPrefs = HomeAppPrefs()

    private data class HomeAppPrefs(
        val hidden: Set<String> = emptySet(),
        val ignoredVersions: Map<String, String> = emptyMap(),
    )

    /** The patched-app record filed under [trackingKey], or null if there is none. */
    fun getPatchedRecord(trackingKey: String): PatchedAppRecord? = recordsByKey[trackingKey]

    // supportedApps parsed from the LATEST patches (eagerly resolved when a newer
    // patch exists), so the UI shows the real future app version without tapping Update.
    private var latestResolvedApps: List<SupportedApp>? = null

    /**
     * When a newer patch than the loaded one exists, resolve+download the latest
     * patches in the background, parse their supported app versions, and rebuild
     * the cards against them. So a card can show "App vX → vY" up front.
     * Best-effort. Failures keep the loaded-patch info.
     */
    private fun eagerlyResolveLatestApps() {
        val anyBehind = cachedSourcesResult?.resolved?.any {
            it.patchFile != null && it.resolvedVersion != null &&
                isNewerVersion(it.latestAvailableVersion ?: it.resolvedVersion, it.resolvedVersion)
        } == true
        if (!anyBehind || recordsByKey.isEmpty()) return
        screenModelScope.launch {
            try {
                val enabled = patchSourceManager.getEnabledRepositories()
                val result = EnabledSourcesLoader.loadAll(enabled, patchService, emptyMap(), configRepository.loadConfig().excludedMppPatterns)
                latestResolvedApps = SupportedAppExtractor.extractSupportedApps(result.unionGuiPatches)
                rebuildTrackedState(forceDeviceRefresh = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.error("Eager latest-patch resolve failed", e)
            }
        }
    }

    /**
     * Re-reads the history and the device. Called when the home screen appears,
     * which is also where it lands after a patch or an install elsewhere in the
     * app, so the verdicts are taken again rather than trusted.
     */
    fun refreshHomeApps() = refreshTrackedState(forceDeviceRefresh = true)

    /**
     * Rebuilds every home card from the history, the resolved sources and the
     * device, on [screenModelScope]. This is the live-refresh path; a full patch
     * reload calls [rebuildTrackedState] directly because it is already inside a
     * coroutine of its own.
     */
    private fun refreshTrackedState(forceDeviceRefresh: Boolean = false) {
        screenModelScope.launch { rebuildTrackedState(forceDeviceRefresh) }
    }

    /**
     * One pass over everything a card is made of: the stored records, what the
     * sources say about them now, and what the device holds.
     *
     * Serialized, because a patch finishing and a device arriving can land within
     * a frame of each other and the second reading must not overtake the first.
     * Failures leave the previous cards standing rather than emptying the screen.
     */
    private suspend fun rebuildTrackedState(forceDeviceRefresh: Boolean) = trackedStateLock.withLock {
        try {
            val config = configRepository.loadConfig()
            homeAppPrefs = HomeAppPrefs(
                hidden = config.hiddenHomeApps.toSet(),
                ignoredVersions = config.ignoredSupportedVersions,
            )

            val records = patchedAppStore.getAll()
                .map { withResolvedInput(it) }
                .associateBy { it.trackingKey }
            recordsByKey = records

            val apps = latestResolvedApps ?: _uiState.value.supportedApps
            updateInfoByKey = records.values.associate { it.trackingKey to recallUpdateInfo(it, apps) }

            if (forceDeviceRefresh) trackedInstallResolver.invalidateAll()
            val device = DeviceMonitor.state.value.selectedDevice?.takeIf { it.isReady }
            trackedInstalls = trackedInstallResolver.resolve(
                records = records.values,
                deviceId = device?.id,
                morpheSignatureIds = morpheSignatureIds(),
            )

            _uiState.value = _uiState.value.copy(
                homeApps = buildHomeApps(records),
                ignoredVersions = homeAppPrefs.ignoredVersions,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.error("Failed to rebuild the home app list", e)
        }
    }

    /**
     * One card per app the sources bring, plus one per further build of it, plus
     * one for every app only the history still knows about — patched through a
     * source that has since been removed, or with universal patches no supported
     * app list mentions. Dropping those would put a patched app out of reach of
     * the actions that manage it.
     */
    private fun buildHomeApps(records: Map<String, PatchedAppRecord>): List<HomeAppItem> {
        val appsByPackage = _uiState.value.supportedApps.associateBy { it.packageName }
        val recordsByPackage = records.values.groupBy { it.packageName }
        val packages = appsByPackage.keys + recordsByPackage.keys
        return packages
            .flatMap { pkg -> homeAppSlots(pkg, recordsByPackage[pkg].orEmpty()) }
            .map { slot -> buildHomeApp(slot, appsByPackage[slot.packageName]) }
    }

    private fun buildHomeApp(slot: HomeAppSlot, app: SupportedApp?): HomeAppItem {
        val record = slot.record
        val install = record?.let { trackedInstalls.byTrackingKey[it.trackingKey] }
        val update = record?.let { updateInfoByKey[it.trackingKey] }
        val installedPackageName = record?.installedPackageName ?: slot.packageName

        // The newest version a rebuild would land on, staying in the channel the
        // app was patched on. Falls back to the source's recommendation for an
        // app Morphe has no build of.
        val supportedVersion = update?.appSuggestedVersion ?: app?.recommendedVersion

        // Live source data first, the record second: a card has to keep its name
        // and color once the source that declared them is gone.
        val displayName = app?.displayName?.takeIf { it.isNotBlank() }
            ?: record?.displayName?.takeIf { it.isNotBlank() }
            ?: SupportedApp.getDisplayName(slot.packageName)

        val deviceState = install?.deviceState ?: when {
            record != null -> DeviceInstallState.NO_DEVICE
            !trackedInstalls.deviceAttached -> DeviceInstallState.NO_DEVICE
            // Nothing was verified about a stock install and nothing needs to be:
            // it is on the device, and it is certainly not a build of Morphe's.
            installedPackageName in trackedInstalls.installedPackages -> DeviceInstallState.UNVERIFIED
            else -> DeviceInstallState.NOT_INSTALLED
        }

        return HomeAppItem(
            id = slot.id,
            packageName = slot.packageName,
            installedPackageName = installedPackageName,
            displayName = displayName,
            version = record?.apkVersion ?: app?.recommendedVersion.orEmpty(),
            appIconColorHex = app?.appIconColor ?: record?.appIconColorHex,
            record = record,
            isClone = slot.isClone,
            deviceState = deviceState,
            artifactState = install?.artifactState ?: PatchedArtifactState.PRESENT,
            deviceVersion = install?.deviceVersion,
            deviceApkPath = install?.deviceApkPath,
            // A record with no verdict yet has not been judged, and must not be
            // presented as any of the states a judgement would have produced.
            isVerificationPending = record != null && install == null,
            hasPatchUpdate = update?.hasRelevantSourceUpdate == true,
            versionStatus = appVersionStatus(
                patchedVersion = record?.apkVersion,
                supportedVersion = supportedVersion,
                // Keyed by the package the sources know, which a copy shares with
                // the app it was copied from rather than carrying one of its own
                ignoredVersion = homeAppPrefs.ignoredVersions[slot.packageName],
            ),
            supportedVersion = supportedVersion,
            supportedApp = app,
            updateInfo = update,
            isHidden = slot.id in homeAppPrefs.hidden,
        )
    }

    /**
     * Answers the offer to rebuild [packageName] at [version], leaving later
     * versions to be offered on their own.
     */
    fun ignoreSupportedVersion(packageName: String, version: String) {
        screenModelScope.launch {
            configRepository.setIgnoredSupportedVersion(packageName, version)
            rebuildTrackedState(forceDeviceRefresh = false)
        }
    }

    /** Undoes [ignoreSupportedVersion], so whatever the sources support is offered again. */
    fun stopIgnoringSupportedVersion(packageName: String) {
        screenModelScope.launch {
            configRepository.setIgnoredSupportedVersion(packageName, null)
            rebuildTrackedState(forceDeviceRefresh = false)
        }
    }

    /** Hides or restores one card. The history behind it is never touched. */
    fun setAppHidden(id: String, hidden: Boolean) {
        screenModelScope.launch {
            configRepository.setHomeAppHidden(id, hidden)
            rebuildTrackedState(forceDeviceRefresh = false)
        }
    }

    /** Shows or hides the hidden cards for the rest of this session. */
    fun setShowHiddenApps(show: Boolean) {
        _uiState.value = _uiState.value.copy(showHiddenApps = show)
    }

    /**
     * The bundles that produced [record]'s build, named and counted for the app
     * information dialog.
     *
     * Patches are resolved to the names their bundle gives them where the source
     * is still around, and shown as recorded where it is not: a build patched by
     * a source the user has since removed still has to be able to say what went
     * into it. Pure map work over already-loaded data, so it costs a dialog
     * nothing to ask.
     */
    fun appliedBundles(record: PatchedAppRecord): List<AppliedBundle> {
        val patchesBySource = cachedSourcesResult?.guiPatchesBySource.orEmpty()
        val sourceNames = patchSourceManager.allSources.value.associate { it.id to it.name }
        val snapshots = record.sourcesSnapshot.associateBy { it.sourceId }

        return record.patchSelectionByBundle
            .filterValues { it.isNotEmpty() }
            .map { (sourceId, selected) ->
                val snapshot = snapshots[sourceId]
                val patches = patchesBySource[sourceId]
                val namesByUniqueId = patches?.associate { it.uniqueId to it.name }.orEmpty()
                val resolved = selected.mapNotNull { namesByUniqueId[it] }.sorted()
                val unresolved = selected
                    .filterNot { it in namesByUniqueId }
                    // A unique id leads with the patch's own name, which reads far
                    // better than the whole id when its bundle cannot name it
                    .map { it.substringBefore('|') }
                    .sorted()
                AppliedBundle(
                    sourceId = sourceId,
                    title = sourceNames[sourceId]
                        ?: snapshot?.sourceName?.takeIf { it.isNotBlank() }
                        ?: sourceId,
                    version = snapshot?.version?.takeIf { it.isNotBlank() && it != "unknown" },
                    available = patches != null,
                    patchNames = resolved,
                    unresolvedNames = unresolved,
                )
            }
            .sortedBy { it.title.lowercase() }
    }

    /**
     * Explicitly remove the record filed under [trackingKey] from the history and
     * refresh the cards. The only way a record leaves the store. We never
     * auto-delete. Touches no files. Re-patching the app recreates the record.
     */
    fun forgetPatchedApp(trackingKey: String) {
        // delete() emits a change → the store observer rebuilds the cards.
        screenModelScope.launch {
            deleteRecord(trackingKey)?.let { showError(getString(Res.string.home_forget_failed, it.message ?: "")) }
        }
    }

    /** Deletes the history record filed under [trackingKey]. Returns the failure if it couldn't be
     *  persisted (the record is then still there), or null on success. */
    private suspend fun deleteRecord(trackingKey: String): Exception? = try {
        patchedAppStore.delete(trackingKey)
        trackedInstallResolver.invalidate(trackingKey)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.error("Failed to remove $trackingKey from the patched-app history", e)
        e
    }

    /**
     * Per-source patch freshness plus app-version freshness for [record],
     * comparing the snapshot it was patched with against the currently resolved
     * sources and the supported app's recommended/experimental versions. The app
     * suggestion stays in the channel the user patched on (stable vs experimental).
     *
     * This is the one place update state is worked out. The card's rebuild badge,
     * the dialog's banner and its source rows all read the result rather than
     * each deciding for themselves what "outdated" means.
     */
    private suspend fun recallUpdateInfo(
        record: PatchedAppRecord,
        apps: List<SupportedApp>,
    ): RecallUpdateInfo {
        val resolvedBySource = resolvedVersionBySource()   // what Re-patch will use right now
        val latestBySource = latestAvailableBySource()     // newest available (may need downloading)
        val app = apps.find { it.packageName == record.packageName }
        val appNames = changelogAppNames(app?.displayName, record.displayName, record.packageName)
        val sources = record.sourcesSnapshot
            // Only sources that actually contributed patches. The selection map has an
            // (empty) entry per enabled bundle, so an enabled-but-unused source has an
            // empty set → drop it. Null = key mismatch/old record → keep (don't hide).
            .filter { snap ->
                val sel = record.patchSelectionByBundle[snap.sourceId]
                sel == null || sel.isNotEmpty()
            }
            .map { snap ->
                val latest = latestBySource[snap.sourceId]
                val outdated = isNewerVersion(latest, snap.version)
                RecallUpdateInfo.SourceUpdate(
                    sourceId = snap.sourceId,
                    name = snap.sourceName,
                    usedVersion = snap.version,
                    resolvedVersion = resolvedBySource[snap.sourceId],
                    latestAvailableVersion = latest,
                    outdated = outdated,
                    hasRelevantChanges = outdated && publishedChangesFor(snap, latest, appNames),
                )
            }
        val used = record.apkVersion
        val (suggested, channel) = suggestedAppVersion(app, used)
        val latestStable = app?.recommendedVersion
        // Supported if the patch targets any version (recommendedVersion null), or the
        // used version is in its stable/experimental lists. Unknown app → assume yes.
        val usedSupported = app == null || app.recommendedVersion == null ||
            app.supportedVersions.any { it.equals(used, ignoreCase = true) } ||
            app.experimentalVersions.any { it.equals(used, ignoreCase = true) }
        return RecallUpdateInfo(
            sources = sources,
            appUsedVersion = used,
            appChannel = channel,
            appSuggestedVersion = suggested,
            appOutdated = isNewerVersion(suggested, used),
            appUsedSupported = usedSupported,
            latestStableVersion = latestStable,
            stableUpdateAvailable = isNewerVersion(latestStable, used),
        )
    }

    /**
     * Whether a newer release of [snap]'s source actually says anything about this
     * app. A source that bumped its version without touching the app is not a
     * reason to tell the user to rebuild.
     *
     * Everything that leaves the question open answers yes: no resolved source to
     * read a changelog from, no changelog, or no name to match its scopes against.
     * Staying quiet about a real update is the worse failure.
     */
    private suspend fun publishedChangesFor(
        snap: PatchedAppRecord.PatchedSourceSnapshot,
        latest: String?,
        appNames: Set<String>,
    ): Boolean {
        val resolved = cachedSourcesResult?.resolved?.firstOrNull { it.source.id == snap.sourceId }
            ?: return true
        val prerelease = resolved.channel == EnabledSourcesLoader.Channel.DEV_LATEST ||
            resolved.channel == EnabledSourcesLoader.Channel.DEV_OLDER
        val relevant = changelogRepository.hasRelevantChanges(
            resolved.source.changelogRequest(sinceVersion = snap.version, appNames = appNames)
                .copy(prerelease = prerelease)
        )
        if (!relevant) {
            Logger.debug(
                "Changelog: '${snap.sourceName}' ${snap.version} -> $latest lists no scoped " +
                    "changes for ${appNames.first()} (tried ${appNames.joinToString(", ")}), no badge"
            )
        }
        return relevant
    }

    /**
     * The app version a re-patch should aim for, staying on the channel the user
     * patched on. **Experimental track** = the patched version is in the experimental
     * list OR is already newer than the latest stable (i.e. they're ahead of stable).
     * Returns (targetVersion, channel).
     *
     * Crucially this keys off the channel, not exact membership of the OLD version in
     * the NEW patch's lists. So when a newer patch introduces a newer experimental
     * app version (e.g. patch 1.30 adds YouTube 21.21.80) it's still suggested even
     * though the user's 21.20.400 has rolled off the experimental list.
     */
    private fun suggestedAppVersion(
        app: SupportedApp?,
        used: String,
    ): Pair<String?, RecallUpdateInfo.AppChannel> {
        if (app == null) return null to RecallUpdateInfo.AppChannel.UNKNOWN
        val latestStable = app.recommendedVersion
        val latestExperimental = app.experimentalVersions.firstOrNull()
        val onExperimental = app.experimentalVersions.any { it.equals(used, ignoreCase = true) } ||
            (latestStable != null && isNewerVersion(used, latestStable))
        return if (onExperimental) {
            (latestExperimental ?: latestStable) to RecallUpdateInfo.AppChannel.EXPERIMENTAL
        } else {
            (latestStable ?: latestExperimental) to RecallUpdateInfo.AppChannel.STABLE
        }
    }

    /** source id → version currently resolved/downloaded (what Re-patch uses now).
     *  Keyed by the source's stable id, not its display name — compared against
     *  PatchedAppRecord.sourcesSnapshot, which persists across sessions, so a
     *  renameable name is the wrong join key here (see PatchPreferencesRepository's
     *  class doc for the general reasoning). */
    private fun resolvedVersionBySource(): Map<String, String?> =
        cachedSourcesResult?.resolved
            ?.filter { it.patchFile != null }
            ?.associate { it.source.id to it.resolvedVersion }
            ?: emptyMap()

    /** source id → newest available version (falls back to resolved when unknown/offline). */
    private fun latestAvailableBySource(): Map<String, String?> =
        cachedSourcesResult?.resolved
            ?.filter { it.patchFile != null }
            ?.associate { it.source.id to (it.latestAvailableVersion ?: it.resolvedVersion) }
            ?: emptyMap()
    /**
     * [record] with [PatchedAppRecord.inputApkPath] set to the original a repatch should read,
     * per [OriginalApkRepository.resolveInputApk] (the Morphe-managed copy first). Records saved
     * since archives became canonical already name it; this presents older ones, which still name
     * the user's own file, the same way, so every consumer (row, detail dialog, repatch) agrees.
     * Read-side only: nothing is persisted. When no original is usable the recorded path is kept
     * so the "not found" dialog can still show where it was.
     */
    private suspend fun withResolvedInput(record: PatchedAppRecord): PatchedAppRecord {
        val resolved = try {
            originalApkRepository.resolveInputApk(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.warn("Could not resolve the original APK for ${record.packageName}: ${e.message}")
            null
        } ?: return record
        return if (resolved.absolutePath == record.inputApkPath) record else record.copy(inputApkPath = resolved.absolutePath)
    }

    /**
     * Resolves every enabled source to its latest release, downloading what is missing, without
     * touching the version prefs saved in settings. Returns the .mpp paths and the matching
     * source names, in the same order.
     */
    suspend fun resolvePatchFiles(
        onDownloadProgress: ((String, Float) -> Unit)? = null,
    ): Result<Pair<List<String>, List<String>>> = try {
        val result = EnabledSourcesLoader.loadAll(
            patchSourceManager.getEnabledRepositories(),
            patchService,
            emptyMap(),
            configRepository.loadConfig().excludedMppPatterns,
            onDownloadProgress,
        )
        val resolvedOk = result.resolved.filter { it.patchFile != null }

        if (resolvedOk.isEmpty()) {
            Result.failure(PatchException("Could not resolve patch files", Res.string.home_could_not_resolve_patch_files))
        } else {
            Result.success(resolvedOk.map { it.patchFile!!.absolutePath } to resolvedOk.map { it.source.name })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.error("Failed to resolve patch files", e)
        Result.failure(e)
    }

    /**
     * Signature ids of Morphe's signing certs. The shared default keystore plus
     * the user's configured keystore (if any). An installed app whose device
     * signature id is in this set was signed by Morphe.
     */
    private suspend fun morpheSignatureIds(): Set<String> = buildSet {
        SignatureIdentity.idForKeystore(
            MorpheData.defaultKeystoreFile,
            storePassword = null,
            alias = DEFAULT_KEYSTORE_ALIAS,
        )?.let { add(it) }
        val config = configRepository.loadConfig()
        config.resolvedKeystorePath()?.let { ks ->
            SignatureIdentity.idForKeystore(ks, config.keystorePassword, config.keystoreAlias)?.let { add(it) }
        }
    }

    /**
     * Snapshot of the most recent multi-source load. Used by 9d's
     * PatchSelectionViewModel migration to render badged per-source patches.
     */
    fun getResolvedSourcesSnapshot(): EnabledSourcesLoader.Result? = cachedSourcesResult
    private var cachedSourcesResult: EnabledSourcesLoader.Result? = null

    /**
     * Re-runs APK analysis against the freshly-loaded `supportedApps` so the info
     * card reflects the new patch file's version compatibility (e.g. a v23 file
     * marks the APK "too new", but switching to v24 should clear that warning).
     */
    private suspend fun reanalyzeSelectedApk() {
        val file = _uiState.value.selectedApk ?: return
        val refreshed = withContext(Dispatchers.IO) { parseApkManifest(file) } ?: return
        _uiState.value = _uiState.value.copy(apkInfo = refreshed)
    }

    /**
     * Retry loading patches.
     */
    fun retryLoadPatches() {
        loadPatchesAndSupportedApps(forceRefresh = true)
    }

    /**
     * Refresh patches if any source's pinned version was changed (e.g. via
     * PatchesScreen). Called when returning to HomeScreen from another screen.
     */
    fun refreshPatchesIfNeeded() {
        // A patch that just finished moved the selected APK into Morphe's managed storage,
        // so the selection would now point at a file that's gone.
        if (_uiState.value.selectedApk?.exists() == false) clearSelection()
        screenModelScope.launch {
            val saved = configRepository.getSourceVersionPrefs()
            if (saved != lastLoadedVersionsBySource) {
                Logger.info("Patches versions changed across sources: $lastLoadedVersionsBySource -> $saved, reloading...")
                loadPatchesAndSupportedApps(forceRefresh = true)
            }
        }
    }

    /**
     * Get the cached patches file path for navigation to next screen.
     */
    fun getCachedPatchesFile(): File? = cachedPatchesFile

    /**
     * Get recommended version for a package from loaded patches.
     */
    fun getRecommendedVersion(packageName: String): String? {
        return SupportedAppExtractor.getRecommendedVersion(cachedPatches, packageName)
    }

    fun onFileSelected(file: File) {
        screenModelScope.launch {
            Logger.info("File selected: ${file.absolutePath}")

            _uiState.value = _uiState.value.copy(isAnalyzing = true)

            val validationResult = withContext(Dispatchers.IO) {
                validateAndAnalyzeApk(file)
            }

            if (validationResult.isValid) {
                _uiState.value = _uiState.value.copy(
                    selectedApk = file,
                    apkInfo = validationResult.apkInfo,
                    error = null,
                    isReady = true,
                    isAnalyzing = false
                )
                Logger.info("APK analyzed successfully: ${validationResult.apkInfo?.appName ?: file.name}")
            } else {
                _uiState.value = _uiState.value.copy(
                    selectedApk = null,
                    apkInfo = null,
                    error = validationResult.getUserErrorMessage() ?: validationResult.errorMessage,
                    isReady = false,
                    isAnalyzing = false
                )
                Logger.warn("APK validation failed: ${validationResult.errorMessage}")
            }
        }
    }

    fun onFilesDropped(files: List<File>) {
        val apkFile = files.firstOrNull { FileUtils.isApkFile(it) }
        if (apkFile != null) {
            onFileSelected(apkFile)
        } else {
            screenModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    error = getString(Res.string.error_drop_valid_apk),
                    isReady = false
                )
            }
        }
    }

    fun clearSelection() {
        // Preserve loaded patches state when clearing APK selection
        _uiState.value = _uiState.value.copy(
            selectedApk = null,
            apkInfo = null,
            error = null,
            isDragHovering = false,
            isReady = false,
            isAnalyzing = false
        )
        Logger.info("APK selection cleared")
    }

    fun showError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun setDragHover(isHovering: Boolean) {
        _uiState.value = _uiState.value.copy(isDragHovering = isHovering)
    }

    private suspend fun validateAndAnalyzeApk(file: File): ApkValidationResult {
        if (!file.exists()) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "File does not exist: ${file.absolutePath}",
                errorRes = Res.string.home_validation_file_not_exist
            )
        }

        if (!file.isFile) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "Selected item is not a file: ${file.absolutePath}",
                errorRes = Res.string.home_validation_not_a_file
            )
        }

        if (!FileUtils.isApkFile(file)) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "Invalid APK file extension: ${file.name}",
                errorRes = Res.string.home_validation_invalid_extension
            )
        }

        if (file.length() < 1024) {
            return ApkValidationResult(
                isValid = false,
                errorMessage = "APK file is too small (${file.length()} bytes): ${file.name}",
                errorRes = Res.string.home_validation_file_too_small
            )
        }

        // Parse APK info from AndroidManifest.xml using apk-parser
        val apkInfo = parseApkManifest(file)

        return if (apkInfo != null) {
            ApkValidationResult(true, apkInfo = apkInfo)
        } else {
            ApkValidationResult(
                isValid = false,
                errorMessage = "Failed to parse APK manifest: ${file.name}",
                errorRes = Res.string.home_validation_parse_failed
            )
        }
    }

    /**
     * Parse APK metadata directly from AndroidManifest.xml using apk-parser library.
     * This works with APKs from any source, not just APKMirror.
     */
    private suspend fun parseApkManifest(file: File): ApkInfo? {
        // For split APK bundles (.apkm, .xapk, .apks), extract base.apk first
        val isBundleFormat = FileUtils.isBundleFormat(file)
        val apkToParse = if (isBundleFormat) {
            FileUtils.extractBaseApkFromBundle(file) ?: run {
                Logger.error("Failed to extract base APK from bundle: ${file.name}")
                return null
            }
        } else {
            file
        }

        return try {
            // ARSCLib reader (in engine). Same library morphe-patcher uses.
            // Handles split APKs cleanly because we only read direct string
            // attributes (no resource resolution that crashes apk-parser on
            // cross-split references).
            val manifest = ApkManifestReader.read(apkToParse)
                ?: throw IllegalStateException("ARSCLib couldn't read manifest")

            val packageName = manifest.packageName
            val versionName = manifest.versionName ?: getString(Res.string.unknown)
            val versionCode = manifest.versionCode
            val minSdk = manifest.minSdkVersion

            val loadedApps = _uiState.value.supportedApps
            val dynamicSupportedApp = loadedApps.find { it.packageName == packageName }
            val isSupported = dynamicSupportedApp != null ||
                (loadedApps.isEmpty() && packageName in AppConstants.FALLBACK_PACKAGES)

            if (!isSupported) {
                Logger.warn("Unsupported package: $packageName — no compatible patches found")
            }

            // Display name: prefer supported app's name. Fall back to ARSCLib's
            // literal label (null for resource-referenced labels like SoundCloud's
            // `@string/app_name`). Last resort: derived from package.
            val appName = dynamicSupportedApp?.displayName
                ?: SupportedApp.resolveDisplayName(packageName, manifest.applicationLabel)

            val versionResolution = if (dynamicSupportedApp != null) {
                resolveVersionStatus(versionName, dynamicSupportedApp, versionCode)
            } else {
                VersionResolution(VersionStatus.UNKNOWN, null)
            }
            val suggestedVersion = versionResolution.suggestedVersion
            val versionStatus = versionResolution.status

            // Get supported architectures from native libraries.
            // For split bundles, scan the original bundle (splits hold native libs, not base.apk).
            val architectures = FileUtils.extractArchitectures(if (isBundleFormat) file else apkToParse)

            // TODO: Re-enable when checksums are provided via .mpp files
            val checksumStatus = ChecksumStatus.NotConfigured

            Logger.info(
                "Parsed APK: $packageName v${manifest.versionName ?: "unknown"}" +
                    (versionCode?.let { " build $it" } ?: "") +
                    " (recommended=$suggestedVersion, minSdk=$minSdk, archs=$architectures)"
            )

            ApkInfo(
                fileName = file.name,
                filePath = file.absolutePath,
                fileSize = file.length(),
                appName = appName,
                packageName = packageName,
                versionName = versionName,
                versionCode = versionCode,
                architectures = architectures,
                minSdk = minSdk,
                suggestedVersion = suggestedVersion,
                versionStatus = versionStatus,
                checksumStatus = checksumStatus,
                isUnsupportedApp = !isSupported
            )
        } catch (e: Exception) {
            // apk-parser commonly chokes on split-APK base.apks whose resource
            // references point into other splits (SoundCloud and similar). The
            // base.apk is structurally valid. Android installs it fine, the
            // patcher merges + patches it fine. But apk-parser can't resolve
            // cross-split references from an isolated file.
            //
            // Fall back to a "limited info" parse: extract package/version from
            // the filename (APKMirror naming convention), fuzzy-match supported
            // apps by display name, and let the user proceed to patching
            // regardless. ApkInfo.hasLimitedInfo=true so the UI can warn that
            // card details may be approximate.
            Logger.warn(
                "Full APK manifest parse failed for ${file.name}: ${e.message}. " +
                    "Falling back to limited-info mode (filename heuristics + fuzzy match)."
            )
            parseApkManifestMinimal(file, isBundleFormat)
        } finally {
            if (isBundleFormat) apkToParse.delete()
        }
    }

    /**
     * Fallback parser when full manifest parsing fails (typically split APKs with
     * cross-split resource references). Recovers what it can from the filename and
     * the bundle's native libs, fuzzy-matches against the supported-apps list, and
     * sets [ApkInfo.hasLimitedInfo] = true so the UI can warn the user.
     *
     * Patching still works regardless. The patcher merges splits first and reads
     * the manifest from the merged APK via its own (working) reader.
     */
    private suspend fun parseApkManifestMinimal(file: File, isBundleFormat: Boolean): ApkInfo {
        val (packageFromName, versionFromName) = parseFromApkMirrorFilename(file.name)
        val supportedApps = _uiState.value.supportedApps

        // Match against supported apps: by exact package first, then fuzzy name
        // on the filename's leading token (handles "soundcloud_..." → "SoundCloud").
        val matched = packageFromName
            ?.let { pkg -> supportedApps.firstOrNull { it.packageName == pkg } }
            ?: fuzzyMatchSupportedApp(file.name, supportedApps)

        val packageName = packageFromName ?: matched?.packageName.orEmpty()
        val displayName = matched?.displayName
            ?: packageFromName?.substringAfterLast('.', "")
                ?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension

        val versionResolution = if (matched != null && versionFromName != null) {
            resolveVersionStatus(versionFromName, matched)
        } else {
            VersionResolution(VersionStatus.UNKNOWN, null)
        }

        val architectures = FileUtils.extractArchitectures(file)

        Logger.info(
            "Limited-info parse for ${file.name}: package=$packageName, " +
                "version=${versionFromName ?: "unknown"}, matched=${matched?.displayName ?: "none"}"
        )

        return ApkInfo(
            fileName = file.name,
            filePath = file.absolutePath,
            fileSize = file.length(),
            appName = displayName,
            packageName = packageName,
            versionName = versionFromName ?: getString(Res.string.unknown),
            architectures = architectures,
            minSdk = null,
            suggestedVersion = versionResolution.suggestedVersion,
            versionStatus = versionResolution.status,
            checksumStatus = ChecksumStatus.NotConfigured,
            isUnsupportedApp = matched == null,
            hasLimitedInfo = true,
        )
    }

    /**
     * Best-effort package + version extraction from APKMirror-style filenames:
     *   com.google.android.youtube_19.20.30-12345.apk
     *   → ("com.google.android.youtube", "19.20.30")
     *
     * Returns (null, null) when the filename doesn't look like a package_version
     * pattern. The version-only path also tries a generic semver / date regex
     * against the whole filename for files like `soundcloud_2026.04.27.apkm`.
     */
    private fun parseFromApkMirrorFilename(filename: String): Pair<String?, String?> {
        val noExt = filename.substringBeforeLast('.')
        val splitOnUnderscore = noExt.split('_', limit = 2)

        val packageCandidate = splitOnUnderscore.getOrNull(0)
        val afterUnderscore = splitOnUnderscore.getOrNull(1)

        // A package name has at least one dot + only lowercase/digits/underscore in
        // each segment. Filters out "soundcloud" while accepting "com.foo.bar".
        val looksLikePackage = packageCandidate != null &&
            packageCandidate.contains('.') &&
            packageCandidate.split('.').all { segment ->
                segment.isNotEmpty() && segment.all { c -> c.isLowerCase() || c.isDigit() || c == '_' }
            }

        val packageName = if (looksLikePackage) packageCandidate else null

        // Version: prefer the token right after "_" (APKMirror convention), else
        // scan the whole filename for a semver / date pattern.
        val versionAfterUnderscore = afterUnderscore?.substringBefore('-')?.takeIf { it.isNotBlank() }
        val version = versionAfterUnderscore
            ?: Regex("""\d+\.\d+\.\d+(?:-dev\.\d+)?""").find(noExt)?.value
            ?: Regex("""\d+\.\d+(?:\.\d+)?""").find(noExt)?.value

        return packageName to version
    }

    /**
     * Fuzzy-match the filename's leading token against supported apps' display names.
     * Used when APKMirror-style filename inference fails to give us a package name.
     * Examples:
     *   "soundcloud_2026.04.27.apkm" → leading token "soundcloud" → matches "SoundCloud"
     *   "YouTube Music_4.81.apkm"    → leading token "youtube music" → matches "YouTube Music"
     */
    private fun fuzzyMatchSupportedApp(
        filename: String,
        supportedApps: List<SupportedApp>,
    ): SupportedApp? {
        val noExt = filename.substringBeforeLast('.').lowercase()
        val leadingToken = noExt
            .substringBefore('_')
            .substringBefore('-')
            .replace(" ", "")
        if (leadingToken.isBlank()) return null
        return supportedApps.firstOrNull { app ->
            val name = app.displayName.lowercase().replace(" ", "")
            name == leadingToken || name.startsWith(leadingToken) || leadingToken.startsWith(name)
        }
    }

    // TODO: Re-enable checksum verification when checksums are provided via .mpp files
    // private fun verifyChecksum(
    //     file: File, packageName: String, version: String,
    //     architectures: List<String>, recommendedVersion: String?
    // ): app.morphe.gui.util.ChecksumStatus { ... }

}

/**
 * Update guidance for a patched app's detail view: per-source patch-file freshness
 * plus app-version freshness within the channel the user patched on (stable vs
 * experimental). Drives the "newer version available. Re-patch" hints.
 */
data class RecallUpdateInfo(
    val sources: List<SourceUpdate>,
    val appUsedVersion: String,
    val appChannel: AppChannel,
    /** Latest version in [appChannel], or null if unknown. */
    val appSuggestedVersion: String?,
    val appOutdated: Boolean,
    /** Whether the patched app version is still supported by the evaluated patch. */
    val appUsedSupported: Boolean = true,
    /** Latest stable app version the evaluated patch supports, if any. */
    val latestStableVersion: String? = null,
    /** A later STABLE version exists than what was patched (recommended to take,
     *  regardless of which channel the user is on). */
    val stableUpdateAvailable: Boolean = false,
) {
    /**
     * Whether any source that patched this app has published changes to it since.
     * The single condition behind the card's rebuild badge and the dialog's patch
     * update banner — a version bump that says nothing about this app is not one.
     */
    val hasRelevantSourceUpdate: Boolean get() = sources.any { it.hasRelevantChanges }

    data class SourceUpdate(
        /** Stable source id, which outlives a rename of the source's display name. */
        val sourceId: String,
        val name: String,
        /** Version this app was patched with (from the record snapshot). */
        val usedVersion: String,
        val resolvedVersion: String?,
        /** Newest available version (an "Update" would move to this). */
        val latestAvailableVersion: String?,
        /** True when [latestAvailableVersion] is newer than [usedVersion]. */
        val outdated: Boolean,
        /**
         * True when that newer release actually lists changes for this app, per
         * its changelog. Everything that leaves the question open counts as yes.
         */
        val hasRelevantChanges: Boolean = false,
    )

    enum class AppChannel { STABLE, EXPERIMENTAL, UNKNOWN }
}

data class HomeUiState(
    val selectedApk: File? = null,
    val apkInfo: ApkInfo? = null,
    val error: String? = null,
    val isDragHovering: Boolean = false,
    val isReady: Boolean = false,
    val isAnalyzing: Boolean = false,
    // Dynamic patches data
    val isLoadingPatches: Boolean = true,
    val isOffline: Boolean = false,
    val isDefaultSource: Boolean = true,
    val supportedApps: List<SupportedApp> = emptyList(),
    /**
     * Every home card, resolved. The one semantic state the app cards and the
     * installed-app information dialog both read.
     */
    val homeApps: List<HomeAppItem> = emptyList(),
    val appListFilter: AppListFilter = AppListFilter.ALL,
    val sortMode: HomeAppSortMode = HomeAppSortMode.RECOMMENDED,
    /** Whether hidden cards are being shown for the rest of this session. */
    val showHiddenApps: Boolean = false,
    /**
     * Supported versions the user turned down, by original package. Exposed so
     * the information dialog can offer to take the refusal back only while the
     * version it answered is still the one on offer.
     */
    val ignoredVersions: Map<String, String> = emptyMap(),
    /** Tracking key of the app being installed to the device from its patched APK. */
    val installingApp: String? = null,
    /** Tracking key of the app being uninstalled from the device. */
    val uninstallingApp: String? = null,
    val patchesVersion: String? = null,
    val patchesChannel: EnabledSourcesLoader.Channel? = null,
    val patchSourceName: String? = null,
    val patchLoadError: String? = null,
    val updateInfo: UpdateInfo? = null,
    val dismissedUpdateVersion: String? = null,
    val updateBannerSessionDismissed: Boolean = false,
    /** True when more than one source is enabled and the user hasn't dismissed
     *  the one-time multi-source intro hint yet. */
    val showMultiSourceHint: Boolean = false,
    /** True when some patch sources loaded but at least one failed. Drives the
     *  non-blocking "some sources failed" banner. */
    val showSourcesFailedBanner: Boolean = false,
    /** How many sources failed to load (for the banner copy). */
    val failedSourcesCount: Int = 0,
    /** Source ids that failed to load. Drives the red status LED on the home pill. */
    val failedSourceIds: Set<String> = emptySet(),
) {
    /**
     * Show the update banner only when an update was found AND the user hasn't
     * dismissed THAT specific version persistently AND hasn't dismissed it for
     * this session. A newer version invalidates the persistent dismissal.
     */
    val showUpdateBanner: Boolean
        get() = updateInfo != null &&
                updateInfo.latestVersion != dismissedUpdateVersion &&
                !updateBannerSessionDismissed

}

data class ApkInfo(
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int? = null,
    val architectures: List<String> = emptyList(),
    val minSdk: Int? = null,
    val suggestedVersion: String? = null,
    val versionStatus: VersionStatus = VersionStatus.UNKNOWN,
    val checksumStatus: ChecksumStatus = ChecksumStatus.NotConfigured,
    val isUnsupportedApp: Boolean = false,
    /** True when full manifest parsing failed and we fell back to filename heuristics
     *  + fuzzy supported-app matching. Most fields are still populated but may be
     *  less accurate. UI should surface a banner letting the user know they can
     *  still proceed but card info is approximate. */
    val hasLimitedInfo: Boolean = false
) {
    val formattedSize: String
        get() = FormatUtils.formatFileSize(fileSize)
}

data class ApkValidationResult(
    val isValid: Boolean,
    val apkInfo: ApkInfo? = null,
    val errorMessage: String? = null,
    val errorRes: StringResource? = null,
    val errorArgs: List<Any> = emptyList(),
) {
    suspend fun getUserErrorMessage(): String? {
        val res = errorRes ?: return errorMessage
        return getString(res, *errorArgs.toTypedArray())
    }
}
