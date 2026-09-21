/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.ui.theme.channelColor
import app.morphe.gui.util.EnabledSourcesLoader
import java.io.File
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

/**
 * One patch source's card in the Level-1 overview grid. Answers "what source is this,
 * and what is its current state?" at a glance — avatar, identity, repository, resolved
 * version/channel, and per-source patch count — without turning into a row of tiny
 * buttons. Every other action (edit, delete, reorder, refresh, toggles) lives one level
 * down in [SourceDetailsDialog], reached via the small details affordance in the corner.
 *
 * The card's own body click preserves exactly the behavior the old row had: it opens
 * that source's patches in Expert mode, or picks it as the active source in Quick
 * Patch's single-select mode. Nothing about that interaction changed — only where the
 * secondary actions live.
 */
@Composable
internal fun SourceCard(
    state: PatchSourceUiState,
    patchCount: Int?,
    updateAvailableVersion: String?,
    mode: SourceSheetMode,
    isActiveSelection: Boolean,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onPrimaryClick: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = state.source
    val corners = LocalMorpheCorners.current
    val accents = LocalMorpheAccents.current
    val font = LocalMorpheFont.current
    val hoverInteraction = remember(source.id) { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    val canInteract = enabled
    val isHighlighted = if (mode == SourceSheetMode.SINGLE_SELECT) isActiveSelection else source.enabled

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val animatedBorder by animateColorAsState(
        targetValue = when {
            isHovered && canInteract -> accents.primary.copy(alpha = if (isHighlighted) 0.7f else 0.45f)
            isHighlighted -> accents.primary.copy(alpha = 0.35f)
            else -> borderColor
        },
        animationSpec = tween(150),
    )
    val animatedBg by animateColorAsState(
        targetValue = when {
            isHovered && canInteract -> accents.primary.copy(alpha = if (isHighlighted) 0.10f else 0.04f)
            isHighlighted -> accents.primary.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
    )
    val baseBg = if (isHighlighted) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp).copy(alpha = 0.5f)
    }

    val a11ySummary = state.error?.let {
        stringResource(Res.string.source_card_a11y_summary_failed, source.name, sourceTypeLabel(source.type), it)
    } ?: stringResource(Res.string.source_card_a11y_summary, source.name, sourceTypeLabel(source.type))

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corners.medium))
            .border(1.dp, animatedBorder, RoundedCornerShape(corners.medium))
            .background(baseBg)
            .background(animatedBg)
            .hoverable(hoverInteraction)
            .then(if (canInteract) Modifier.handCursor().clickable(onClick = onPrimaryClick) else Modifier)
            .semantics {
                contentDescription = a11ySummary
            }
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                SourceAvatar(source = source, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = source.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = font,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TypeChip(source.type, font)
                    }
                    Text(
                        text = sourceSubtitle(source),
                        fontSize = 11.sp,
                        fontFamily = font,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // In Expert mode the whole card already opens Details on click (see
                // SourceManagementSheet), so a second identical affordance here would
                // be pure redundancy. Quick Patch's card click is spoken for by
                // selecting the source, so it still needs this as the only way in.
                if (mode == SourceSheetMode.SINGLE_SELECT) {
                    DetailsButton(onClick = onOpenDetails, enabled = enabled)
                }
            }

            StatusRow(state = state, updateAvailableVersion = updateAvailableVersion, font = font, accentColor = accents.primary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = when {
                        patchCount != null -> pluralStringResource(Res.plurals.patch_selection_group_patch_count, patchCount, patchCount)
                        state.error != null -> ""
                        else -> "…"
                    },
                    fontSize = 11.sp,
                    fontFamily = font,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (mode) {
                    SourceSheetMode.MULTI_TOGGLE -> MorpheSwitch(
                        checked = source.enabled,
                        onCheckedChange = onToggleEnabled,
                        accentColor = accents.primary,
                        enabled = enabled,
                        modifier = Modifier.scale(0.8f),
                    )
                    SourceSheetMode.SINGLE_SELECT -> SelectedRadio(
                        selected = isActiveSelection,
                        enabled = enabled,
                        onClick = onPrimaryClick,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsButton(onClick: () -> Unit, enabled: Boolean) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(stringResource(Res.string.source_card_view_details), fontSize = 11.sp) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = MorpheIcons.KeyboardArrowRight,
                contentDescription = stringResource(Res.string.source_card_view_details_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 0.6f else 0.3f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun StatusRow(
    state: PatchSourceUiState,
    updateAvailableVersion: String?,
    font: FontFamily,
    accentColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            state.error != null -> {
                Icon(
                    imageVector = MorpheIcons.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = stringResource(Res.string.source_error_failed_to_load),
                    fontSize = 11.sp,
                    fontFamily = font,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.source.enabled && state.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = accentColor)
                Text(stringResource(Res.string.source_sheet_status_resolving), fontSize = 11.sp, fontFamily = font, fontWeight = FontWeight.Medium, color = accentColor)
            }
            state.source.enabled && state.version != null -> {
                Text(state.version, fontSize = 11.sp, fontFamily = font, fontWeight = FontWeight.Medium, color = accentColor)
                ChannelBadge(channel = state.channel, font = font)
                if (updateAvailableVersion != null) {
                    Icon(
                        imageVector = MorpheIcons.Update,
                        contentDescription = stringResource(Res.string.source_card_update_available_description, updateAvailableVersion),
                        tint = accentColor,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            else -> {
                Text(
                    text = stringResource(Res.string.source_card_status_disabled),
                    fontSize = 11.sp,
                    fontFamily = font,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
internal fun TypeChip(type: PatchSourceType, font: FontFamily) {
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(
            text = sourceTypeLabel(type),
            fontSize = 9.5.sp,
            fontFamily = font,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SelectedRadio(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(containerColor)
            .then(if (enabled) Modifier.handCursor().clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = MorpheIcons.Check,
                contentDescription = stringResource(Res.string.source_card_selected_quick_patch),
                tint = contentColor,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** Shared by the card and the details dialog so the badge always agrees. */
@Composable
internal fun ChannelBadge(channel: EnabledSourcesLoader.Channel?, font: FontFamily) {
    val corners = LocalMorpheCorners.current
    // UNKNOWN/null must never read as "Latest Stable" — that's a load failure or an
    // unresolved state, not a healthy one, and the badge is the only place a source's
    // channel is surfaced, so getting this wrong actively hides a real problem.
    val isUnknown = channel == null || channel == EnabledSourcesLoader.Channel.UNKNOWN
    val label = when (channel) {
        EnabledSourcesLoader.Channel.STABLE_LATEST -> stringResource(Res.string.version_label_latest_stable)
        EnabledSourcesLoader.Channel.STABLE_OLDER -> stringResource(Res.string.source_sheet_channel_older_stable)
        EnabledSourcesLoader.Channel.DEV_LATEST -> stringResource(Res.string.version_label_latest_dev)
        EnabledSourcesLoader.Channel.DEV_OLDER -> stringResource(Res.string.source_sheet_channel_older_dev)
        EnabledSourcesLoader.Channel.LOCAL -> stringResource(Res.string.source_sheet_local_label)
        EnabledSourcesLoader.Channel.UNKNOWN, null -> stringResource(Res.string.unknown)
    }
    // channelColor() maps null/UNKNOWN to the same tint as STABLE_LATEST (see
    // ChannelColors.kt) — appropriate for the sources-pill LED this card doesn't use,
    // but wrong for a badge whose whole job is to state the channel plainly. Use a
    // neutral tint for that one case instead of a shared color that would contradict
    // the "Unknown" label right next to it.
    val color = if (isUnknown) MaterialTheme.colorScheme.onSurfaceVariant else channelColor(channel)
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(corners.small))
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(corners.small))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text = label, fontSize = 10.sp, fontFamily = font, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
internal fun sourceTypeLabel(type: PatchSourceType): String = when (type) {
    PatchSourceType.DEFAULT -> stringResource(Res.string.source_sheet_type_preinstalled)
    PatchSourceType.GITHUB, PatchSourceType.GITLAB -> stringResource(Res.string.source_sheet_remote_label)
    PatchSourceType.LOCAL -> stringResource(Res.string.source_sheet_local_label)
}

@Composable
internal fun sourceSubtitle(source: PatchSource): String = when (source.type) {
    PatchSourceType.DEFAULT -> source.url?.removePrefix("https://github.com/") ?: stringResource(Res.string.source_sheet_builtin)
    PatchSourceType.GITHUB -> source.url?.removePrefix("https://github.com/") ?: stringResource(Res.string.github_label)
    PatchSourceType.GITLAB -> source.url?.removePrefix("https://gitlab.com/") ?: stringResource(Res.string.source_sheet_gitlab_label)
    PatchSourceType.LOCAL -> source.filePath?.let { File(it).name } ?: stringResource(Res.string.patch_source_dialog_local_file_label)
}
