/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.morphe.engine.model.PatchedAppRecord
import app.morphe.gui.data.repository.PatchPreferencesRepository
import app.morphe.gui.data.repository.PatchSourceManager
import app.morphe.gui.ui.components.MorpheBanners
import app.morphe.gui.ui.components.MorpheErrorBar
import app.morphe.gui.ui.components.SourceLedState
import app.morphe.gui.ui.components.SourceManagementSheet
import app.morphe.gui.ui.components.UpdateBanner
import app.morphe.gui.ui.components.sourceLedState
import app.morphe.gui.ui.screens.home.components.ForgetConfirmDialog
import app.morphe.gui.ui.screens.home.components.FullScreenDropZone
import app.morphe.gui.ui.screens.home.components.HeaderBar
import app.morphe.gui.ui.screens.home.components.MiddleContent
import app.morphe.gui.ui.screens.home.components.MultiSourceHintBanner
import app.morphe.gui.ui.screens.home.components.RepatchMissingApkDialog
import app.morphe.gui.ui.screens.home.components.SourcesFailedBanner
import app.morphe.gui.ui.screens.home.components.SupportedAppsListPane
import app.morphe.gui.ui.screens.home.components.UninstallConfirmDialog
import app.morphe.gui.ui.screens.home.components.VersionWarningDialog
import app.morphe.gui.ui.screens.patches.PatchSelectionScreen
import app.morphe.gui.util.EnabledSourcesLoader
import app.morphe.gui.util.FileUtils
import app.morphe.gui.util.MorpheFilePicker
import app.morphe.gui.util.VersionStatus
import app.morphe.gui.util.sourceChannelMap
import app.morphe.gui.util.sourceErrorMap
import app.morphe.gui.util.sourcePatchCountMap
import app.morphe.gui.util.sourceUpdateAvailableMap
import app.morphe.gui.util.sourceVersionMap
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import java.io.File
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

class HomeScreen : Screen {

    @Composable
    override fun Content() {
        val viewModel = koinScreenModel<HomeViewModel>()
        HomeScreenContent(viewModel = viewModel)
    }
}

@Composable
fun HomeScreenContent(
    viewModel: HomeViewModel
) {
    val navigator = LocalNavigator.currentOrThrow
    val uiState by viewModel.uiState.collectAsState()

    // Device install-state is polled (adb), not streamed.
    LaunchedEffect(Unit) { viewModel.refreshDeviceInfo() }

    val coroutineScope = rememberCoroutineScope()
    val patchSourceManager: PatchSourceManager = koinInject()
    val patchPreferencesRepository: PatchPreferencesRepository = koinInject()
    val allSources by patchSourceManager.allSources.collectAsState()

    var showSourceManagementSheet by rememberSaveable { mutableStateOf(false) }

    // One-click repatch: a patched-app row's "Re-patch" action. Jump straight to
    // patch selection with the input APK + the record's saved selection, using
    // the CURRENT resolved sources (so it repatches against current bundle versions).
    var repatchMissingRecord by remember { mutableStateOf<PatchedAppRecord?>(null) }
    // Launch patch selection for a record with explicit patch files (re-patch uses
    // the current resolved set. Update passes freshly-resolved latest files).
    fun launchPatch(
        record: PatchedAppRecord,
        apkPath: String,
        patchFilePaths: List<String>,
        sourceNames: List<String>,
    ) {
        if (patchFilePaths.isEmpty()) return // patches not loaded yet
        navigator.push(
            PatchSelectionScreen(
                apkPath = apkPath,
                apkName = record.displayName,
                patchesFilePath = patchFilePaths.first(),
                packageName = record.packageName,
                patchesFilePaths = patchFilePaths,
                patchSourceNames = sourceNames,
                initialSelectionByBundle = record.patchSelectionByBundle,
                initialPatchOptions = record.patchOptionValues,
                apkVersion = record.apkVersion,
            )
        )
    }

    fun repatchWithApk(record: PatchedAppRecord, apkPath: String) {
        launchPatch(
            record, apkPath,
            viewModel.getAllResolvedPatchFiles().map { it.absolutePath },
            viewModel.getAllResolvedPatchSourceNames(),
        )
    }
    val onRepatch: (String) -> Unit = onRepatch@{ pkg ->
        val record = viewModel.getPatchedRecord(pkg) ?: return@onRepatch
        // HomeViewModel hands out records whose input path is already resolved to the
        // Morphe-managed original when one exists, so this never prefers a stale copy in
        // the user's own folders. Only when nothing usable is left do we ask for the APK.
        if (File(record.inputApkPath).exists()) {
            repatchWithApk(record, record.inputApkPath)
        } else {
            repatchMissingRecord = record
        }
    }

    // Explicit "Forget" recovery action. Removes a record from the history.
    var forgetConfirm by remember { mutableStateOf<PatchedAppRecord?>(null) }
    val onForget: (String) -> Unit = { pkg -> forgetConfirm = viewModel.getPatchedRecord(pkg) }
    forgetConfirm?.let { record ->
        ForgetConfirmDialog(
            record = record,
            onDismiss = { forgetConfirm = null },
            onConfirm = {
                viewModel.forgetPatchedApp(record.packageName)
                forgetConfirm = null
            },
        )
    }

    // "Uninstall" removes the patched app from the connected device. The dialog
    // offers the keep-history vs delete-history choice via a checkbox.
    var uninstallConfirm by remember { mutableStateOf<PatchedAppRecord?>(null) }
    var uninstallAlsoForget by remember { mutableStateOf(false) }
    val onUninstall: (String) -> Unit = { pkg ->
        uninstallAlsoForget = false
        uninstallConfirm = viewModel.getPatchedRecord(pkg)
    }
    uninstallConfirm?.let { record ->
        UninstallConfirmDialog(
            record = record,
            alsoForget = uninstallAlsoForget,
            onAlsoForgetChange = { uninstallAlsoForget = it },
            onDismiss = { uninstallConfirm = null },
            onConfirm = {
                viewModel.uninstallPatchedApp(record.packageName, alsoForget = uninstallAlsoForget)
                uninstallConfirm = null
            },
        )
    }

    repatchMissingRecord?.let { record ->
        RepatchMissingApkDialog(
            record = record,
            onDismiss = { repatchMissingRecord = null },
            onApkPicked = { path -> repatchWithApk(record, path) },
        )
    }

    // Tap a "Your apps" row to see the Manager-style installed-app info dialog.
    var detailRecord by remember { mutableStateOf<PatchedAppRecord?>(null) }
    val onShowDetail: (PatchedAppRecord) -> Unit = { detailRecord = it }
    detailRecord?.let { record ->
        val updateInfo = remember(record) { viewModel.recallUpdateInfo(record) }
        var mutedSourceIds by remember(record.packageName) { mutableStateOf<Set<String>>(emptySet()) }
        LaunchedEffect(record.packageName) {
            mutedSourceIds = patchSourceManager.mutedSourceIdsForApp(record.packageName)
        }
        InstalledAppInfoDialog(
            record = record,
            state = uiState.patchedStates[record.packageName] ?: PatchedAppState.PATCHED,
            deviceInfo = uiState.deviceAppInfo[record.packageName],
            updateInfo = updateInfo,
            onDismiss = { detailRecord = null },
            onRepatch = { onRepatch(record.packageName) },
            onUpdate = { viewModel.prepareUpdate(record) },
            onForget = { onForget(record.packageName) },
            onOpenFolder = {
                FileUtils.revealInFileManager(File(record.outputApkPath).parentFile)
            },
            onInstall = { viewModel.installPatchedApp(record.packageName) },
            onUninstall = { onUninstall(record.packageName) },
            installing = uiState.installingPackage == record.packageName,
            uninstalling = uiState.uninstallingPackage == record.packageName,
            appIconColorHex = uiState.supportedApps.firstOrNull { it.packageName == record.packageName }?.appIconColor,
            mutedSourceIds = mutedSourceIds,
            onToggleSourceMute = { sourceId ->
                coroutineScope.launch {
                    // record.patchSelectionByBundle's keys are exactly the sources that
                    // applied a patch to this app — real per-app coverage, not just "every
                    // enabled source" — so the precise appsToKeepFrom-based last-source
                    // protection in muteSourceForApp applies rather than its coarser fallback.
                    if (sourceId in mutedSourceIds) {
                        patchSourceManager.unmuteSourceForApp(record.packageName, sourceId)
                    } else {
                        patchSourceManager.muteSourceForApp(
                            packageName = record.packageName,
                            sourceId = sourceId,
                            coveredByThisApp = record.patchSelectionByBundle.keys,
                        )
                    }
                    mutedSourceIds = patchSourceManager.mutedSourceIdsForApp(record.packageName)
                }
            },
            onResetSelections = {
                coroutineScope.launch {
                    // Clears saved patch choices for this app across every source — the
                    // next patch of it starts from each bundle's own .mpp defaults rather
                    // than whatever was picked last time. Does not touch the patched
                    // history record itself (that's "Forget", a separate action) or any
                    // file on disk — just the remembered selection.
                    patchPreferencesRepository.resetForApp(record.packageName)
                }
            },
        )
    }

    val navStackSize = navigator.items.size
    LaunchedEffect(navStackSize) {
        viewModel.refreshPatchesIfNeeded()
    }

    if (showSourceManagementSheet) {
        val snapshot = viewModel.getResolvedSourcesSnapshot()
        SourceManagementSheet(
            sources = allSources,
            sourceVersions = snapshot.sourceVersionMap(),
            sourceChannels = snapshot.sourceChannelMap(),
            sourceErrors = snapshot.sourceErrorMap(),
            sourcePatchCounts = snapshot.sourcePatchCountMap(),
            sourceUpdateAvailable = snapshot.sourceUpdateAvailableMap(),
            isLoading = uiState.isLoadingPatches,
            onToggleEnabled = { id, enabled ->
                coroutineScope.launch {
                    patchSourceManager.setSourceEnabled(id, enabled)
                    // Re-resolve releases + reload patches so badges, versions,
                    // and the union app list reflect the new enabled set.
                    viewModel.retryLoadPatches()
                }
            },
            onAdd = { source ->
                coroutineScope.launch { patchSourceManager.addSource(source) }
            },
            onEdit = { updated ->
                coroutineScope.launch { patchSourceManager.updateSource(updated) }
            },
            onRemove = { id ->
                coroutineScope.launch { patchSourceManager.removeSource(id) }
            },
            onReorder = { orderedIds ->
                coroutineScope.launch {
                    patchSourceManager.reorderSources(orderedIds)
                    // Reload so the union app list + display-name tiebreak reflect
                    // the new source priority.
                    viewModel.retryLoadPatches()
                }
            },
            onDismiss = { showSourceManagementSheet = false },
            onRefresh = { viewModel.retryLoadPatches() },
            enabled = !uiState.isAnalyzing,
        )
    }

    // Full screen drop zone wrapper
    FullScreenDropZone(
        isDragHovering = uiState.isDragHovering,
        onDragHoverChange = { viewModel.setDragHover(it) },
        onFilesDropped = { viewModel.onFilesDropped(it) },
        enabled = !uiState.isAnalyzing
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Single side-by-side layout: APK drop zone on one side, supported-apps
            // list on the other. The window enforces a minimum width wide enough for
            // it (see GuiMain), so there's no narrow/stacked variant to maintain.
            val padding = 24.dp

            // Version warning dialog state
            var showVersionWarningDialog by remember { mutableStateOf(false) }

            if (showVersionWarningDialog && uiState.apkInfo != null) {
                VersionWarningDialog(
                    versionStatus = uiState.apkInfo!!.versionStatus,
                    currentVersion = uiState.apkInfo!!.versionName,
                    suggestedVersion = uiState.apkInfo!!.suggestedVersion ?: "",
                    onConfirm = {
                        showVersionWarningDialog = false
                        val patchesFile = viewModel.getCachedPatchesFile()
                        if (patchesFile != null) {
                            navigator.push(PatchSelectionScreen(
                                apkPath = uiState.apkInfo!!.filePath,
                                apkName = uiState.apkInfo!!.appName,
                                patchesFilePath = patchesFile.absolutePath,
                                packageName = uiState.apkInfo!!.packageName,
                                apkArchitectures = uiState.apkInfo!!.architectures,
                                apkVersion = uiState.apkInfo!!.versionName,
                                patchesFilePaths = viewModel.getAllResolvedPatchFiles().map { it.absolutePath },
                                patchSourceNames = viewModel.getAllResolvedPatchSourceNames(),
                            ))
                        }
                    },
                    onDismiss = { showVersionWarningDialog = false }
                )
            }

            val patchesLoaded = !uiState.isLoadingPatches && viewModel.getCachedPatchesFile() != null
//            val onChangePatchesClick: () -> Unit = {
//                navigator.push(PatchesScreen(
//                    apkPath = uiState.apkInfo?.filePath ?: "",
//                    apkName = uiState.apkInfo?.appName ?: "Select APK first"
//                ))
//            }
            val onRetry: () -> Unit = { viewModel.retryLoadPatches() }
            val onClearClick: () -> Unit = { viewModel.clearSelection() }
            val onChangeClick: () -> Unit = {
                coroutineScope.launch {
                    openFilePicker()?.let { file ->
                        viewModel.onFileSelected(file)
                    }
                }
            }
            val onContinueClick: () -> Unit = {
                handleContinue(uiState, viewModel, navigator) {
                    showVersionWarningDialog = true
                }
            }

            val resolvedSnapshot = viewModel.getResolvedSourcesSnapshot()
//            val versionsBySource: Map<String, String?> = resolvedSnapshot
//                ?.resolved
//                ?.associate { it.source.id to it.resolvedVersion }
//                ?: emptyMap()
            val channelsBySource: Map<String, EnabledSourcesLoader.Channel?> =
                resolvedSnapshot
                    ?.resolved
                    ?.associate { it.source.id to it.channel }
                    ?: emptyMap()
            // Source names whose patches target the currently-selected APK's package.
            // Used by ApkInfoCard's "FROM" row to surface multi-source provenance.
            val patchSourcesForSelectedApk: List<String> = uiState.apkInfo?.let { info ->
                val snapshot = resolvedSnapshot ?: return@let null
                snapshot.guiPatchesBySource.entries
                    .filter { (_, patches) ->
                        patches.any { p -> p.compatiblePackages.any { it.name == info.packageName } }
                    }
                    .mapNotNull { (sourceId, _) ->
                        allSources.firstOrNull { it.id == sourceId }?.name
                    }
            } ?: emptyList()

            // Per-package source attribution map used by the supported-apps cards.
            // Built once per recomposition so each card just looks up its own list.
            val sourceNamesByPackage: Map<String, List<String>> = if (resolvedSnapshot == null) {
                emptyMap()
            } else {
                val sourceIdToName = allSources.associate { it.id to it.name }
                val accum = mutableMapOf<String, MutableList<String>>()
                resolvedSnapshot.guiPatchesBySource.forEach { (sourceId, patches) ->
                    val name = sourceIdToName[sourceId] ?: return@forEach
                    val packages = patches.flatMap { it.compatiblePackages.map { p -> p.name } }
                        .filter { it.isNotBlank() }
                        .toSet()
                    packages.forEach { pkg ->
                        accum.getOrPut(pkg) { mutableListOf() }.add(name)
                    }
                }
                accum
            }
            val sourceStates: List<SourceLedState> = allSources.map { src ->
                sourceLedState(src, channelsBySource[src.id], hasError = src.id in uiState.failedSourceIds)
            }
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ── Pinned header (not scrollable) ──
                    HeaderBar(
                        uiState = uiState,
                        onRetry = onRetry,
                        onUpdateChannelChanged = { viewModel.refreshUpdateCheck() },
                        onManageSourcesClick = { showSourceManagementSheet = true },
                        sourceStates = sourceStates,
                    )

                    // ── Body: drop zone / APK info on one side, supported-apps
                    // list on the other. The list pane owns its own scroll. ──
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (uiState.showUpdateBanner ||
                                uiState.showMultiSourceHint ||
                                uiState.showSourcesFailedBanner
                            ) {
                                MorpheBanners {
                                    if (uiState.showUpdateBanner) {
                                        UpdateBanner(
                                            info = uiState.updateInfo!!,
                                            onDismissForSession = { viewModel.dismissUpdateForSession() },
                                            onDismissForVersion = { viewModel.dismissUpdateForVersion() },
                                        )
                                    }
                                    if (uiState.showMultiSourceHint) {
                                        MultiSourceHintBanner(
                                            onDismiss = { viewModel.dismissMultiSourceHint() },
                                        )
                                    }
                                    if (uiState.showSourcesFailedBanner) {
                                        SourcesFailedBanner(
                                            count = uiState.failedSourcesCount,
                                            onManageSources = { showSourceManagementSheet = true },
                                            onDismiss = { viewModel.dismissSourcesFailedBanner() },
                                        )
                                    }
                                }
                            }
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    // Small cute padding for small cute space
                                    // between the HeaderBar's bottom
                                    // divider and the actual body section.
                                    .padding(
                                        start = 10.dp,
                                        end = padding,
                                        top = 4.dp,
                                        bottom = padding,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                            val bodyViewport = this.maxHeight
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(padding),
                                verticalAlignment = Alignment.Top,
                            ) {
                                // Left: browse/discover supported apps (wizard step 1).
                                SupportedAppsListPane(
                                    supportedApps = uiState.supportedApps,
                                    patchedStates = uiState.patchedStates,
                                    patchedRecords = uiState.patchedRecords,
                                    deviceAppInfo = uiState.deviceAppInfo,
                                    updateInfoByPackage = uiState.updateInfoByPackage,
                                    sortMode = uiState.sortMode,
                                    onSortModeChange = { viewModel.setSortMode(it) },
                                    onShowDetail = onShowDetail,
                                    filter = uiState.appListFilter,
                                    onFilterChange = { viewModel.setAppListFilter(it) },
                                    sourceNamesByPackage = sourceNamesByPackage,
                                    isLoading = uiState.isLoadingPatches,
                                    loadError = uiState.patchLoadError,
                                    onRetry = onRetry,
                                    onManageSources = { showSourceManagementSheet = true },
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .heightIn(max = bodyViewport),
                                )
                                // Right: APK info / drop zone (wizard step 2, pick the
                                // APK you want patched). Content centers vertically when
                                // it fits, scrolls when it doesn't, so the CONTINUE
                                // button is never clipped off the bottom.
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .align(Alignment.CenterVertically)
                                        .heightIn(max = bodyViewport)
                                        .padding(top = 16.dp)
                                        .verticalScroll(rememberScrollState()),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    MiddleContent(
                                        uiState = uiState,
                                        patchesLoaded = patchesLoaded,
                                        onClearClick = onClearClick,
                                        onChangeClick = onChangeClick,
                                        onContinueClick = onContinueClick,
                                        patchSourceNames = patchSourcesForSelectedApk,
                                    )
                                }
                            }
                            }
                    }
                }

                // Error/warning bar, custom Morphe-styled, avoids Material3
                // SnackbarHost (whose internal SnackbarKt invocation path the
                // shadow `minimize` analyzer can't trace, causing runtime
                // NoClassDefFoundError in the packaged jar).
                uiState.error?.let { error ->
                    MorpheErrorBar(
                        message = error,
                        onDismiss = { viewModel.clearError() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                    )
                }

            }
        }
    }
}

private fun handleContinue(
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    navigator: Navigator,
    showWarning: () -> Unit
) {
    val patchesFile = viewModel.getCachedPatchesFile() ?: return
    val versionStatus = uiState.apkInfo?.versionStatus
    if (versionStatus != null && versionStatus != VersionStatus.LATEST_STABLE && versionStatus != VersionStatus.UNKNOWN) {
        showWarning()
    } else {
        uiState.apkInfo?.let { info ->
            navigator.push(PatchSelectionScreen(
                apkPath = info.filePath,
                apkName = info.appName,
                patchesFilePath = patchesFile.absolutePath,
                packageName = info.packageName,
                apkArchitectures = info.architectures,
                apkVersion = info.versionName,
                patchesFilePaths = viewModel.getAllResolvedPatchFiles().map { it.absolutePath },
                patchSourceNames = viewModel.getAllResolvedPatchSourceNames(),
            ))
        }
    }
}

private suspend fun openFilePicker(): File? =
    MorpheFilePicker.pickFile(
        title = "Select APK file",
        extensions = listOf("apk", "apkm", "xapk", "apks"),
    )
