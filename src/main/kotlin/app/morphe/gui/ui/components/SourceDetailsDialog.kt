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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import app.morphe.gui.data.repository.ChangelogRepository
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.util.ChangelogEntry
import org.koin.compose.koinInject
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

/**
 * Level-2 "source details" view, opened from a [SourceCard]'s details affordance.
 * Everything that doesn't need to be visible at a glance in the overview grid lives
 * here: full repository identity, patch/version info, the version's changelog (fetched
 * lazily, only once asked for), pre-release/experimental toggles, and the source
 * management actions (disable, refresh, reorder, edit, delete) that used to be a row of
 * small icons on every card.
 *
 * All actions reuse the exact same callbacks [SourceManagementSheet] already had —
 * nothing about *what* enabling, refreshing, editing or deleting a source does has
 * changed, only where the controls for it live.
 */
@Composable
internal fun SourceDetailsDialog(
    state: PatchSourceUiState,
    patchCount: Int?,
    updateAvailableVersion: String?,
    mode: SourceSheetMode,
    isActiveSelection: Boolean,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectSingle: () -> Unit,
    onSave: (PatchSource) -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val source = state.source
    val font = LocalMorpheFont.current
    val accents = LocalMorpheAccents.current
    val uriHandler = LocalUriHandler.current
    var showEditDialog by remember { mutableStateOf(false) }
    val isQuickMode = mode == SourceSheetMode.SINGLE_SELECT

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MorpheDialogSurface(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 440.dp)
                // Same accent border as the overview grid (SourceManagementSheet) —
                // this dialog sits over the same dark home screen and would blend
                // into it identically without one.
                .border(1.dp, accents.primary.copy(alpha = 0.35f), RoundedCornerShape(LocalMorpheCorners.current.large)),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Fixed header: always visible regardless of scroll position, so closing
            // the dialog never requires scrolling first.
            Row(verticalAlignment = Alignment.Top) {
                SourceAvatar(source = source, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(source.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = font, color = MaterialTheme.colorScheme.onSurface)
                        TypeChip(source.type, font)
                    }
                    Text(sourceSubtitle(source), fontSize = 12.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(MorpheIcons.Close, contentDescription = stringResource(Res.string.close), modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Scrollable middle: the only section whose height genuinely varies (an
            // expanded changelog can be arbitrarily long), capped to a fraction of the
            // window so this dialog can never exceed the usable viewport. Bounded to a
            // window-relative range rather than a fixed number, so it behaves on both
            // a small laptop screen and a large monitor.
            val density = LocalDensity.current
            val windowSize = LocalWindowInfo.current.containerSize
            val scrollMaxHeight = with(density) { (windowSize.height * 0.5f).toDp() }.coerceIn(200.dp, 380.dp)
            val scrollState = rememberScrollState()

            Box(Modifier.fillMaxWidth().heightIn(max = scrollMaxHeight)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (isQuickMode) {
                        QuickSelectRow(isActiveSelection = isActiveSelection, enabled = enabled, onSelectSingle = onSelectSingle, accentColor = accents.primary, font = font)
                    }

                    InfoRow(
                        label = stringResource(Res.string.source_details_patches_label),
                        value = patchCount?.let { pluralStringResource(Res.plurals.patch_selection_group_patch_count, it, it) } ?: "—",
                        icon = MorpheIcons.Apps,
                        font = font,
                    )

                    VersionRow(
                        state = state,
                        updateAvailableVersion = updateAvailableVersion,
                        font = font,
                        accentColor = accents.primary,
                    )

                    if (source.type != PatchSourceType.LOCAL) {
                        val url = source.url
                        if (url != null) {
                            MorpheButton(
                                label = stringResource(Res.string.source_details_open_in_browser),
                                icon = MorpheIcons.OpenInNew,
                                variant = MorpheButtonVariant.GHOST,
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { uriHandler.openUri(url) },
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

                        ToggleRow(
                            title = stringResource(Res.string.patch_source_dialog_pre_release_title),
                            subtitle = stringResource(Res.string.patch_source_dialog_pre_release_hint),
                            checked = source.usePreRelease,
                            enabled = enabled,
                            accentColor = accents.primary,
                            font = font,
                            onCheckedChange = { onSave(source.copy(usePreRelease = it)) },
                        )
                        if (isQuickMode) {
                            ToggleRow(
                                title = stringResource(Res.string.patch_source_dialog_experimental_title),
                                subtitle = stringResource(Res.string.patch_source_dialog_experimental_hint),
                                checked = source.useExperimentalVersions,
                                enabled = enabled,
                                accentColor = accents.primary,
                                font = font,
                                onCheckedChange = { onSave(source.copy(useExperimentalVersions = it)) },
                            )
                        }
                    }
                }

                if (scrollState.canScrollForward || scrollState.canScrollBackward) {
                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(scrollState),
                        style = morpheScrollbarStyle(),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // Fixed action footer: pinned below the scroll region, so it's always
            // reachable no matter how long the middle section (changelog included)
            // has grown.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (mode == SourceSheetMode.MULTI_TOGGLE) {
                        MorpheButton(
                            label = if (source.enabled) stringResource(Res.string.source_details_disable) else stringResource(Res.string.source_details_enable),
                            variant = MorpheButtonVariant.GHOST,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            onClick = { onToggleEnabled(!source.enabled) },
                        )
                    }
                    MorpheButton(
                        label = stringResource(Res.string.patches_refresh_description),
                        icon = MorpheIcons.Refresh,
                        variant = MorpheButtonVariant.GHOST,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = onRefresh,
                    )
                }
                if (canMoveUp || canMoveDown) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        MorpheButton(
                            label = stringResource(Res.string.source_sheet_move_up_description),
                            icon = MorpheIcons.KeyboardArrowUp,
                            variant = MorpheButtonVariant.GHOST,
                            enabled = enabled && canMoveUp,
                            modifier = Modifier.weight(1f),
                            onClick = onMoveUp,
                        )
                        MorpheButton(
                            label = stringResource(Res.string.source_sheet_move_down_description),
                            icon = MorpheIcons.KeyboardArrowDown,
                            variant = MorpheButtonVariant.GHOST,
                            enabled = enabled && canMoveDown,
                            modifier = Modifier.weight(1f),
                            onClick = onMoveDown,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MorpheButton(
                        label = stringResource(Res.string.source_sheet_edit_description),
                        icon = MorpheIcons.Edit,
                        variant = MorpheButtonVariant.GHOST,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { showEditDialog = true },
                    )
                    if (source.deletable) {
                        MorpheButton(
                            label = stringResource(Res.string.delete),
                            icon = MorpheIcons.Delete,
                            variant = MorpheButtonVariant.DANGER,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            onClick = { onRemove(); onDismiss() },
                        )
                    }
                }
            }
        }
    }

    if (showEditDialog) {
        EditPatchSourceDialog(
            source = source,
            isQuickMode = isQuickMode,
            onDismiss = { showEditDialog = false },
            onSave = { onSave(it); showEditDialog = false },
        )
    }
}

@Composable
private fun QuickSelectRow(
    isActiveSelection: Boolean,
    enabled: Boolean,
    onSelectSingle: () -> Unit,
    accentColor: Color,
    font: FontFamily,
) {
    if (isActiveSelection) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(accentColor.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(MorpheIcons.CheckCircle, contentDescription = null, tint = accentColor, modifier = Modifier.size(15.dp))
            Text(stringResource(Res.string.source_card_selected_quick_patch), fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = font, color = accentColor)
        }
    } else {
        MorpheButton(
            label = stringResource(Res.string.source_details_use_this_source),
            variant = MorpheButtonVariant.PRIMARY,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            onClick = onSelectSingle,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, icon: ImageVector, font: FontFamily) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Column {
            Text(label, fontSize = 10.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = font, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * The "Version" info row. Tapping it lazily fetches and expands that release's
 * changelog via [ChangelogRepository] — never on first render, only on request, so
 * opening source details never triggers a network call by itself.
 */
@Composable
private fun VersionRow(
    state: PatchSourceUiState,
    updateAvailableVersion: String?,
    font: FontFamily,
    accentColor: Color,
) {
    val source = state.source
    val canExpand = source.type != PatchSourceType.LOCAL && state.version != null
    var expanded by remember(source.id) { mutableStateOf(false) }
    var entries by remember(source.id) { mutableStateOf<List<ChangelogEntry>?>(null) }
    var loading by remember(source.id) { mutableStateOf(false) }
    val changelogRepository = koinInject<ChangelogRepository>()

    val corners = LocalMorpheCorners.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corners.small))
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            .then(if (canExpand) Modifier.handCursor().clickable {
                expanded = !expanded
                if (expanded && entries == null && !loading) {
                    loading = true
                }
            } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(MorpheIcons.Update, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.home_apk_info_version_label), fontSize = 10.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        state.version ?: (state.error?.let { stringResource(Res.string.source_details_version_unavailable) } ?: "—"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = font,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.version != null) ChannelBadge(channel = state.channel, font = font)
                }
                if (updateAvailableVersion != null) {
                    Text(stringResource(Res.string.source_details_update_available, updateAvailableVersion), fontSize = 11.sp, fontFamily = font, color = accentColor)
                }
            }
            if (canExpand) {
                Icon(
                    if (expanded) MorpheIcons.KeyboardArrowUp else MorpheIcons.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) Res.string.source_details_hide_changelog else Res.string.source_details_show_changelog
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (expanded) {
            // Fetch exactly once per (source, expand) — not on every recomposition —
            // by gating on `entries == null`, which flips to a real (possibly empty)
            // list after the first successful or failed attempt.
            LaunchedEffect(source.id, loading) {
                if (loading) {
                    entries = changelogRepository.entriesFor(source, prerelease = source.usePreRelease)
                    loading = false
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            Spacer(Modifier.height(10.dp))
            val loadedEntries = entries
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.source_details_loading_changelog), fontSize = 11.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                loadedEntries.isNullOrEmpty() -> Text(
                    stringResource(Res.string.source_details_no_changelog),
                    fontSize = 11.sp,
                    fontFamily = font,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> FormattedReleaseNotes(markdown = loadedEntries.first().content)
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    accentColor: Color,
    font: FontFamily,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = font, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }
        MorpheSwitch(checked = checked, onCheckedChange = onCheckedChange, accentColor = accentColor, enabled = enabled)
    }
}
