/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.repository.ConfigRepository
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.util.EnabledSourcesLoader
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Layout constants for the responsive card grid. Kept together so the dialog's own
 * width floor and the grid's column-count formula (in [SourceManagementSheet]) are
 * mathematically tied to the same numbers instead of two independently hand-picked
 * breakpoint tables that can silently drift apart.
 */
private val MIN_CARD_WIDTH = 210.dp
private val CARD_SPACING = 10.dp
private const val MAX_COLUMNS = 4
private val DIALOG_CONTENT_PADDING = 20.dp
private val GRID_SCROLLBAR_GUTTER = 14.dp

/**
 * How much of the window this dialog occupies, and its absolute bounds. Deliberately
 * modest (was 0.86f/1080dp) so the dialog reads as a distinct floating panel with real
 * margin around it, rather than nearly the size of the window it sits over — which is
 * also part of why it needs its own border accent below, since a same-size dark panel
 * over a dark home screen has almost nothing to visually separate it otherwise.
 */
private const val DIALOG_WINDOW_SCALE = 0.78f
private val DIALOG_MAX_WIDTH = 960.dp
private val DIALOG_MAX_HEIGHT = 680.dp
private val DIALOG_MIN_HEIGHT = 320.dp

/**
 * How cards in the management sheet behave:
 * - [MULTI_TOGGLE]: each card has an enable Switch. Used by Expert mode where
 *   patches from all enabled sources are unioned.
 * - [SINGLE_SELECT]: each card is a radio. Used by Quick Patch mode where exactly
 *   one source is "active" at a time.
 */
enum class SourceSheetMode { MULTI_TOGGLE, SINGLE_SELECT }

/**
 * A source's persisted [PatchSource] plus its resolved runtime state, projected once
 * per render instead of three separate map lookups (`sourceVersions[id]`,
 * `sourceChannels[id]`, `sourceErrors[id]`) scattered across every card and the details
 * dialog. The ingestion side (ViewModel → these parallel maps, see
 * [EnabledSourcesLoader.sourceVersionMap] and friends) is left alone — this projection
 * is applied at the point it actually removes duplication: rendering.
 */
internal data class PatchSourceUiState(
    val source: PatchSource,
    val version: String?,
    val channel: EnabledSourcesLoader.Channel?,
    val error: String?,
    val isLoading: Boolean,
)

/**
 * Patch-source management: a responsive card grid (see [SourceCard]) with a
 * per-source [SourceDetailsDialog] one level down for everything that doesn't need to
 * be visible at a glance. Summoned from the home header `+` button in Expert mode, and
 * from Quick Patch's source picker in single-select mode.
 *
 * Caller wires actions to [PatchSourceManager][app.morphe.gui.data.repository.PatchSourceManager]
 * / [ConfigRepository] equivalents. Nothing about those semantics changed in this redesign —
 * enabling, adding, editing, removing, reordering and refreshing a source all still go through
 * exactly the same callbacks and the same [ConfigRepository]-backed persistence as before.
 */
@Composable
fun SourceManagementSheet(
    sources: List<PatchSource>,
    onToggleEnabled: (id: String, enabled: Boolean) -> Unit,
    onAdd: (PatchSource) -> Unit,
    onEdit: (PatchSource) -> Unit,
    onRemove: (id: String) -> Unit,
    onDismiss: () -> Unit,
    /** Reload all sources: Re-resolves a folder source to its newest .mpp so a
     *  freshly-built patch is picked up without leaving the screen. */
    onRefresh: () -> Unit = {},
    /** Persist a new source ordering (ids in desired order). Order affects only
     *  the display-name tiebreak + UI presentation, not which patches load. */
    onReorder: (orderedIds: List<String>) -> Unit = {},
    enabled: Boolean = true,
    /** sourceId → resolved version label (e.g. "v1.27.0-dev.2"). Empty when not loaded. */
    sourceVersions: Map<String, String?> = emptyMap(),
    /** sourceId → channel classification of the resolved release. Drives the badge. */
    sourceChannels: Map<String, EnabledSourcesLoader.Channel?> = emptyMap(),
    /** True while patches are being (re)loaded. Drives the per-card spinner shown
     *  in place of the version/badge for enabled sources whose data isn't yet
     *  in [sourceVersions]. */
    isLoading: Boolean = false,
    /** sourceId → load-failure message for sources that failed to load. Drives the per-card
     *  FAILED state, so a partial multi-source failure shows exactly which source broke. */
    sourceErrors: Map<String, String> = emptyMap(),
    /** sourceId → that source's OWN patch count (not the cross-source union). See
     *  [EnabledSourcesLoader.sourcePatchCountMap]. */
    sourcePatchCounts: Map<String, Int> = emptyMap(),
    /** sourceId → newer version available in the source's followed channel, when it
     *  differs from what's resolved. See [EnabledSourcesLoader.sourceUpdateAvailableMap]. */
    sourceUpdateAvailable: Map<String, String> = emptyMap(),
    /** Selection semantics. Defaults to multi-toggle (Expert mode). */
    mode: SourceSheetMode = SourceSheetMode.MULTI_TOGGLE,
    /** sourceId of the currently picked source, used only when [mode] is SINGLE_SELECT. */
    activeSourceId: String? = null,
    /** Called when the user picks a source, used only when [mode] is SINGLE_SELECT. */
    onSelectSingle: (sourceId: String) -> Unit = {},
) {
    val corners = LocalMorpheCorners.current
    val font = LocalMorpheFont.current
    val accents = LocalMorpheAccents.current

    var showAddDialog by remember { mutableStateOf(false) }
    var detailsSourceId by remember { mutableStateOf<String?>(null) }

    // Live ordering the grid renders from. Reseeds only when the actual id sequence
    // from config changes (List equals is structural), so a reorder we just persisted
    // doesn't get clobbered by the next recomposition before the ViewModel round-trips.
    val sourcesById = remember(sources) { sources.associateBy { it.id } }
    var workingOrder by remember { mutableStateOf(sources.map { it.id }) }
    LaunchedEffect(sources.map { it.id }) { workingOrder = sources.map { it.id } }
    val canReorder = enabled && sources.size > 1
    val orderedSources = workingOrder.mapNotNull { sourcesById[it] }

    fun commitMove(id: String, up: Boolean) {
        val i = workingOrder.indexOf(id)
        val target = if (up) i - 1 else i + 1
        if (i < 0 || target !in workingOrder.indices) return
        val next = workingOrder.toMutableList().apply { add(target, removeAt(i)) }
        workingOrder = next
        onReorder(next)
    }

    val uiStates = remember(orderedSources, sourceVersions, sourceChannels, sourceErrors, isLoading) {
        orderedSources.map { s ->
            PatchSourceUiState(
                source = s,
                version = sourceVersions[s.id],
                channel = sourceChannels[s.id],
                error = sourceErrors[s.id],
                isLoading = isLoading && sourceVersions[s.id] == null && sourceErrors[s.id] == null,
            )
        }
    }

    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    // Both dimensions are driven by the window's own size, not a static guess, and
    // both use the same DIALOG_WINDOW_SCALE so the panel scales down proportionally
    // instead of two independently-tuned ratios drifting apart. The width floor is
    // deliberately low (just enough for one MIN_CARD_WIDTH card plus the surface's
    // own padding and the grid's scrollbar gutter) so a genuinely narrow window can
    // actually reach the 1-column layout the grid below computes.
    val dialogWidth = with(density) { (windowSize.width * DIALOG_WINDOW_SCALE).toDp() }
        .coerceIn(MIN_CARD_WIDTH + DIALOG_CONTENT_PADDING * 2 + GRID_SCROLLBAR_GUTTER, DIALOG_MAX_WIDTH)
    val dialogMaxHeight = with(density) { (windowSize.height * DIALOG_WINDOW_SCALE).toDp() }
        .coerceIn(DIALOG_MIN_HEIGHT, DIALOG_MAX_HEIGHT)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MorpheDialogSurface(
            modifier = Modifier
                .width(dialogWidth)
                // Accent border: without it this panel — same dark surface colour as
                // the home screen behind it — has nothing to visually separate it,
                // especially at this reduced scale where more of that background
                // shows around the edges.
                .border(1.dp, accents.primary.copy(alpha = 0.35f), RoundedCornerShape(corners.large)),
            contentModifier = Modifier.heightIn(max = dialogMaxHeight),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Patch sources", fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                // Reload sources, which re-resolves a folder source to its newest .mpp.
                IconButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = MorpheIcons.Refresh,
                        contentDescription = "Reload patches",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.7f else 0.3f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Text(
                text = when {
                    !enabled -> "Disabled while patching"
                    mode == SourceSheetMode.SINGLE_SELECT ->
                        "${sources.size} sources · pick which one Quick Patch uses"
                    else -> "${sources.size} sources · patches from every enabled source are unioned"
                },
                fontSize = 11.sp,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // weight(fill = false): the grid gets whatever's left after the fixed rows
            // below it (Add source, developer section, Done) claim their own full,
            // natural height first — so those can never be pushed out of the dialog's
            // bounded height on a small window. The grid still only takes as much of
            // that leftover space as its own content actually needs (fill = false),
            // so a handful of sources doesn't leave a stretched, mostly-empty box.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                // Derived from actual available width, not a hand-picked breakpoint
                // table: as many MIN_CARD_WIDTH-or-wider columns as fit with
                // CARD_SPACING between them. This is the one place column count is
                // decided, so it can never silently drift out of sync with whatever
                // width the dialog above actually resolves to.
                val columns = (((maxWidth + CARD_SPACING) / (MIN_CARD_WIDTH + CARD_SPACING))
                    .toInt())
                    .coerceIn(1, MAX_COLUMNS)
                val rows = uiStates.chunked(columns)
                val listState = rememberLazyListState()

                LazyColumn(
                    state = listState,
                    // maxHeight here is this Box's own resolved height (already
                    // capped by the weight() above), not a second, independently
                    // guessed number — the same principle as reusing maxWidth for
                    // column count.
                    modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
                    contentPadding = PaddingValues(end = GRID_SCROLLBAR_GUTTER),
                    verticalArrangement = Arrangement.spacedBy(CARD_SPACING),
                ) {
                    items(items = rows, key = { row -> row.joinToString("-") { it.source.id } }) { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CARD_SPACING)) {
                            row.forEach { state ->
                                key(state.source.id) {
                                    SourceCard(
                                        state = state,
                                        patchCount = sourcePatchCounts[state.source.id],
                                        updateAvailableVersion = sourceUpdateAvailable[state.source.id],
                                        mode = mode,
                                        isActiveSelection = state.source.id == activeSourceId,
                                        enabled = enabled,
                                        onToggleEnabled = { newVal -> onToggleEnabled(state.source.id, newVal) },
                                        onPrimaryClick = {
                                            if (mode == SourceSheetMode.SINGLE_SELECT) {
                                                onSelectSingle(state.source.id)
                                            } else {
                                                // Expert mode: the card itself now opens
                                                // this source's details (previously this
                                                // switched to the source and pushed the
                                                // legacy single-source PatchesScreen —
                                                // removed; see HomeScreen.kt).
                                                detailsSourceId = state.source.id
                                            }
                                        },
                                        onOpenDetails = { detailsSourceId = state.source.id },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                if (listState.canScrollBackward || listState.canScrollForward) {
                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
                        adapter = rememberScrollbarAdapter(listState),
                        style = morpheScrollbarStyle(),
                    )
                }
            }

            AddSourceRow(onClick = { showAddDialog = true }, enabled = enabled)

            // Patch-developer extras. Sits under the sources it applies to, and
            // renders only when Developer options are on.
            DeveloperMppExclusionsSection(enabled = enabled)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, shape = RoundedCornerShape(corners.small)) {
                    Text("Done", fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 11.sp)
                }
            }
        }
    }

    if (showAddDialog) {
        AddPatchSourceDialog(
            isQuickMode = mode == SourceSheetMode.SINGLE_SELECT,
            onDismiss = { showAddDialog = false },
            onAdd = { onAdd(it); showAddDialog = false },
        )
    }

    val detailsState = uiStates.find { it.source.id == detailsSourceId }
    if (detailsState != null) {
        val index = orderedSources.indexOfFirst { it.id == detailsState.source.id }
        SourceDetailsDialog(
            state = detailsState,
            patchCount = sourcePatchCounts[detailsState.source.id],
            updateAvailableVersion = sourceUpdateAvailable[detailsState.source.id],
            mode = mode,
            isActiveSelection = detailsState.source.id == activeSourceId,
            enabled = enabled,
            canMoveUp = canReorder && index > 0,
            canMoveDown = canReorder && index < orderedSources.lastIndex,
            onDismiss = { detailsSourceId = null },
            onToggleEnabled = { newVal -> onToggleEnabled(detailsState.source.id, newVal) },
            onSelectSingle = { onSelectSingle(detailsState.source.id); detailsSourceId = null },
            onSave = onEdit,
            onRemove = { onRemove(detailsState.source.id) },
            onRefresh = onRefresh,
            onMoveUp = { commitMove(detailsState.source.id, up = true) },
            onMoveDown = { commitMove(detailsState.source.id, up = false) },
        )
    }
}

@Composable
private fun AddSourceRow(onClick: () -> Unit, enabled: Boolean) {
    val corners = LocalMorpheCorners.current
    val font = LocalMorpheFont.current
    val accents = LocalMorpheAccents.current
    val shape = RoundedCornerShape(corners.small)
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accents.primary.copy(alpha = 0.06f * alpha), shape)
            .border(1.dp, accents.primary.copy(alpha = 0.35f * alpha), shape)
            .then(if (enabled) Modifier.handCursor().clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MorpheIcons.Add,
            contentDescription = null,
            tint = accents.primary.copy(alpha = alpha),
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Add source",
            fontFamily = font,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = accents.primary.copy(alpha = alpha),
        )
    }
}

/**
 * Developer-only section at the bottom of the source sheet. Lets a patch developer add
 * glob or plain-word patterns for .mpp files that a folder source should ignore when it
 * auto-loads the newest build. Self-contained, it reads and persists through
 * ConfigRepository the same way the add and edit source dialogs pull developer state.
 * Renders nothing unless Developer options are on.
 */
@Composable
private fun DeveloperMppExclusionsSection(enabled: Boolean) {
    val configRepository = koinInject<ConfigRepository>()
    val scope = rememberCoroutineScope()
    var developerOptions by remember { mutableStateOf(false) }
    var patterns by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = configRepository.loadConfig()
        developerOptions = cfg.developerOptions
        patterns = cfg.excludedMppPatterns
        loaded = true
    }

    if (!loaded || !developerOptions) return

    val font = LocalMorpheFont.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
        )
        Text(
            "Ignored .mpp patterns",
            fontFamily = font,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ExcludedPatternsEditor(
            patterns = patterns,
            onChange = { next ->
                patterns = next
                scope.launch { configRepository.setExcludedMppPatterns(next) }
            },
            enabled = enabled,
        )
    }
}

/**
 * Editor for extra .mpp exclusion patterns. Type a pattern and add it. Each saved pattern
 * shows as a removable row. A plain word matches any file that contains it, and a pattern
 * with a wildcard is treated as a glob. The build classifiers *-sources.mpp and
 * *-javadoc.mpp are always excluded and are not listed here.
 */
@Composable
private fun ExcludedPatternsEditor(
    patterns: List<String>,
    onChange: (List<String>) -> Unit,
    enabled: Boolean,
) {
    val font = LocalMorpheFont.current
    val accents = LocalMorpheAccents.current
    val corners = LocalMorpheCorners.current
    var draft by remember { mutableStateOf("") }

    fun commitDraft() {
        val p = draft.trim()
        if (p.isNotEmpty() && patterns.none { it.equals(p, ignoreCase = true) }) {
            onChange(patterns + p)
        }
        draft = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Only affects folder sources, which auto-load the newest .mpp in a folder. When " +
                "picking that newest build, files whose name matches a pattern here are skipped. A " +
                "plain word matches any file containing it (e.g. debug). Use * for globs (e.g. " +
                "*-debug.mpp). *-sources.mpp and *-javadoc.mpp are always ignored",
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = font,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SlimTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = "e.g. debug or *-debug.mpp",
            font = font,
            accents = accents,
            corners = corners,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            trailing = {
                IconButton(
                    onClick = { commitDraft() },
                    enabled = enabled && draft.isNotBlank(),
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = MorpheIcons.Add,
                        contentDescription = "Add pattern",
                        modifier = Modifier.size(16.dp),
                        tint = if (draft.isNotBlank()) accents.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            },
        )
        patterns.forEach { pat ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = pat,
                    fontFamily = font,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { onChange(patterns.filterNot { it == pat }) },
                    enabled = enabled,
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        imageVector = MorpheIcons.Close,
                        contentDescription = "Remove pattern",
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}
