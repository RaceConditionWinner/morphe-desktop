/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.morphe.engine.model.PatchedAppRecord
import app.morphe.gui.ui.components.morpheScrollbarStyle
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.components.AppCard
import app.morphe.gui.ui.screens.home.components.PatchedStateBadge
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.ui.theme.LocalMorpheMono
import app.morphe.gui.ui.theme.MorpheAccentColors
import app.morphe.gui.ui.theme.MorpheCornerStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Desktop port of Morphe Manager 1.30.0's landscape `InstalledAppInfoDialog`
 * (`app/src/main/java/app/morphe/manager/ui/screen/home/InstalledAppInfoDialog.kt`).
 *
 * This is a UI-fidelity port, not a 1:1 architecture port: Manager's dialog is
 * backed by Android-only plumbing (PackageManager, Shizuku, root/Magisk mount,
 * WorkManager, Activity Result APIs, Room). None of that exists here — Desktop
 * has its own patch/device/update pipeline (see [HomeViewModel]), so this dialog
 * is wired directly to the existing [PatchedAppRecord] / [PatchedAppState] /
 * [DeviceAppInfo] / [RecallUpdateInfo] data and to the existing Desktop callbacks
 * for repatch / update / install / uninstall / forget / open folder. Manager actions
 * with no Desktop equivalent (launch on device,
 * export-to-arbitrary-location, mount, changelog) are intentionally omitted
 * rather than faked — see the implementation summary for the full mapping.
 *
 * Visual structure mirrors Manager's landscape branch: a fixed-width left action
 * rail, a vertical divider, and a scrollable right content pane with a hero
 * header, status/update banners, an information card, and an "applied patches"
 * entry point that opens its own dialog — matching Manager's AppHeroHeader /
 * banners / InfoSection / AppliedPatchesDialog composition.
 */
@Composable
fun InstalledAppInfoDialog(
    record: PatchedAppRecord,
    state: PatchedAppState,
    deviceInfo: DeviceAppInfo?,
    updateInfo: RecallUpdateInfo?,
    onDismiss: () -> Unit,
    onRepatch: () -> Unit,
    onUpdate: () -> Unit,
    onForget: () -> Unit,
    onOpenFolder: () -> Unit,
    onInstall: () -> Unit = {},
    onUninstall: () -> Unit = {},
    installing: Boolean = false,
    uninstalling: Boolean = false,
    appIconColorHex: String? = null,
    /** Source ids currently kept from offering patches to this app (see SourceMuteRepository). */
    mutedSourceIds: Set<String> = emptySet(),
    /** Toggles muting for one source id, scoped to this app only. */
    onToggleSourceMute: (String) -> Unit = {},
    /** Clears this app's saved patch selection (every source) — a future patch starts
     *  from each bundle's own defaults again. Does not touch the patched-app record. */
    onResetSelections: () -> Unit = {},
) {
    val font = LocalMorpheFont.current
    val mono = LocalMorpheMono.current
    val accents = LocalMorpheAccents.current
    val corners = LocalMorpheCorners.current

    val patchCount = record.patchSelectionByBundle.values.sumOf { it.size }
    val hasUpdate = updateInfo != null && (updateInfo.appOutdated || updateInfo.sources.any { it.outdated })
    val installPending = deviceInfo?.installPending == true
    val isDeleted = state == PatchedAppState.APK_MISSING
    val isModified = state == PatchedAppState.MODIFIED_EXTERNALLY
    // Mirrors Manager: the primary "Patch" action is hidden once a banner already
    // carries a CTA that does the same job (isAppDeleted / showsUpdateBanner).
    val showPrimaryAction = !isDeleted && !hasUpdate

    var appliedPatchesOpen by remember { mutableStateOf(false) }

    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            val dialogWidth = (maxWidth * 0.94f).coerceAtMost(880.dp).coerceAtLeast(560.dp)
            val dialogHeight = (maxHeight * 0.88f).coerceAtMost(620.dp).coerceAtLeast(420.dp)

            AnimatedVisibility(
                visibleState = visibleState,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.95f, animationSpec = tween(180)),
            ) {
                Surface(
                    shape = RoundedCornerShape(corners.large),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .width(dialogWidth)
                        .height(dialogHeight)
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        // ── Left action rail ──
                        ActionRail(
                            record = record,
                            deviceInfo = deviceInfo,
                            showPrimaryAction = showPrimaryAction,
                            installPending = installPending,
                            installing = installing,
                            uninstalling = uninstalling,
                            font = font,
                            accents = accents,
                            corners = corners,
                            onDismiss = onDismiss,
                            onRepatch = onRepatch,
                            onInstall = onInstall,
                            onUninstall = onUninstall,
                            onOpenFolder = onOpenFolder,
                            onForget = onForget,
                            onResetSelections = onResetSelections,
                        )

                        VerticalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxHeight(),
                        )

                        // ── Right content pane ──
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val listState = rememberLazyListState()
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().padding(end = 10.dp),
                                contentPadding = PaddingValues(24.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                item(key = "hero") {
                                    AppInfoHeroHeader(
                                        record = record,
                                        state = state,
                                        deviceInfo = deviceInfo,
                                        appIconColorHex = appIconColorHex,
                                        font = font,
                                        mono = mono,
                                        accents = accents,
                                        corners = corners,
                                    )
                                }
                                item(key = "banners") {
                                    AppInfoBanners(
                                        isDeleted = isDeleted,
                                        isModified = isModified,
                                        installPending = installPending,
                                        installing = installing,
                                        hasUpdate = hasUpdate,
                                        record = record,
                                        deviceInfo = deviceInfo,
                                        updateInfo = updateInfo,
                                        font = font,
                                        accents = accents,
                                        corners = corners,
                                        onRepatch = { onDismiss(); onRepatch() },
                                        onInstall = onInstall,
                                        onUpdate = { onDismiss(); onUpdate() },
                                    )
                                }
                                item(key = "info") {
                                    AppInfoSection(
                                        record = record,
                                        patchCount = patchCount,
                                        updateInfo = updateInfo,
                                        font = font,
                                        mono = mono,
                                        accents = accents,
                                        corners = corners,
                                        onOpenAppliedPatches = { appliedPatchesOpen = true },
                                    )
                                }
                                item(key = "bottom-space") { Spacer(Modifier.height(2.dp)) }
                            }
                            VerticalScrollbar(
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                adapter = rememberScrollbarAdapter(listState),
                                style = morpheScrollbarStyle(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (appliedPatchesOpen) {
        AppliedPatchesDialog(
            record = record,
            font = font,
            mono = mono,
            accents = accents,
            corners = corners,
            mutedSourceIds = mutedSourceIds,
            onToggleSourceMute = onToggleSourceMute,
            onDismiss = { appliedPatchesOpen = false },
        )
    }
}

// ============================================================================
// LEFT ACTION RAIL — mirrors Manager's action column (primary / secondary / destructive)
// ============================================================================

@Composable
private fun ActionRail(
    record: PatchedAppRecord,
    deviceInfo: DeviceAppInfo?,
    showPrimaryAction: Boolean,
    installPending: Boolean,
    installing: Boolean,
    uninstalling: Boolean,
    font: FontFamily,
    accents: MorpheAccentColors,
    corners: MorpheCornerStyle,
    onDismiss: () -> Unit,
    onRepatch: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onOpenFolder: () -> Unit,
    onForget: () -> Unit,
    onResetSelections: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .width(196.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(vertical = 20.dp, horizontal = 14.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showPrimaryAction) {
                RailPrimaryButton(
                    label = "Repatch",
                    icon = MorpheIcons.AutoAwesome,
                    color = accents.primary,
                    font = font,
                    corner = corners.small,
                    onClick = { onDismiss(); onRepatch() },
                )
                Spacer(Modifier.height(2.dp))
            }
            if (installPending) {
                RailTileButton(
                    label = if (installing) "Installing…" else "Install",
                    icon = MorpheIcons.Download,
                    destructive = false,
                    enabled = !installing,
                    font = font,
                    corner = corners.small,
                    onClick = onInstall,
                )
            }
            if (deviceInfo?.installed == true) {
                RailTileButton(
                    label = if (uninstalling) "Uninstalling…" else "Uninstall",
                    icon = MorpheIcons.Delete,
                    destructive = true,
                    enabled = !uninstalling,
                    font = font,
                    corner = corners.small,
                    onClick = { onDismiss(); onUninstall() },
                )
            }
            RailTileButton(
                label = "Open folder",
                icon = MorpheIcons.FolderOpen,
                destructive = false,
                enabled = true,
                font = font,
                corner = corners.small,
                onClick = onOpenFolder,
            )
            RailTileButton(
                label = "Forget",
                icon = MorpheIcons.PlaylistRemove,
                destructive = true,
                enabled = true,
                font = font,
                corner = corners.small,
                onClick = { onDismiss(); onForget() },
            )
            RailTileButton(
                label = "Reset saved patches",
                icon = MorpheIcons.Undo,
                destructive = true,
                enabled = true,
                font = font,
                corner = corners.small,
                onClick = { onDismiss(); onResetSelections() },
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        Spacer(Modifier.height(10.dp))
        RailCloseButton(font = font, corner = corners.small, onClick = onDismiss)
    }
}

@Composable
private fun RailPrimaryButton(
    label: String,
    icon: ImageVector,
    color: Color,
    font: FontFamily,
    corner: Dp,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(color.copy(alpha = if (isHovered) 1f else 0.92f))
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = font, color = Color.White)
    }
}

@Composable
private fun RailTileButton(
    label: String,
    icon: ImageVector,
    destructive: Boolean,
    enabled: Boolean,
    font: FontFamily,
    corner: Dp,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    val baseColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val bgAlpha = when {
        !enabled -> 0.04f
        isHovered -> if (destructive) 0.18f else 0.12f
        else -> if (destructive) 0.10f else 0.06f
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(baseColor.copy(alpha = bgAlpha))
            .hoverable(hover, enabled = enabled)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = baseColor.copy(alpha = if (enabled) (if (destructive) 1f else 0.85f) else 0.4f),
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = font,
            color = baseColor.copy(alpha = if (enabled) 0.95f else 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RailCloseButton(font: FontFamily, corner: Dp, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = if (isHovered) 0.5f else 0.3f),
                shape = RoundedCornerShape(corner),
            )
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "Close",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = font,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================================
// HERO HEADER — app icon, name, package, version + status chips
// ============================================================================

@Composable
private fun AppInfoHeroHeader(
    record: PatchedAppRecord,
    state: PatchedAppState,
    deviceInfo: DeviceAppInfo?,
    appIconColorHex: String?,
    font: FontFamily,
    mono: FontFamily,
    accents: MorpheAccentColors,
    corners: MorpheCornerStyle,
) {
    Row(verticalAlignment = Alignment.Top) {
        // No real APK-icon extraction pipeline exists in Desktop (unlike Manager's
        // PackageManager-backed AppIcon), so — same as the "Your apps" row/card —
        // we reuse the existing gradient AppCard treatment as a graceful, on-brand
        // fallback avatar instead of inventing a new icon-loading subsystem.
        AppCard(
            modifier = Modifier.size(72.dp),
            cornerRadius = corners.medium,
            appIconColorHex = appIconColorHex,
            interactive = false,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = record.displayName.firstOrNull()?.uppercase() ?: "?",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = font,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.displayName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = font,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state != PatchedAppState.NEVER_PATCHED) {
                    Spacer(Modifier.width(10.dp))
                    PatchedStateBadge(state, font)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = record.packageName,
                fontSize = 12.sp,
                fontFamily = mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!record.currentPackageName.isNullOrBlank() && record.currentPackageName != record.packageName) {
                Text(
                    text = "→ ${record.currentPackageName}",
                    fontSize = 12.sp,
                    fontFamily = mono,
                    color = accents.primary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeroChip(text = "v${record.apkVersion.removePrefix("v")}", color = accents.secondary, font = font)
                HeroChip(text = "Patched ${relativeOrShortDate(record.patchedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant, font = font)
                if (deviceInfo != null) {
                    val (label, color) = deviceChipLabelAndColor(deviceInfo, accents)
                    HeroChip(text = label, color = color, icon = MorpheIcons.PhoneAndroid, font = font)
                }
            }
        }
    }
}

private fun deviceChipLabelAndColor(info: DeviceAppInfo, accents: MorpheAccentColors): Pair<String, Color> = when {
    !info.installed -> "Not on this device" to Color(0xFF8A8A8A)
    info.signedByMorphe == false -> "Not Morphe-signed" to Color(0xFFE0504D)
    else -> "On device" + (info.installedVersion?.let { " · v${it.removePrefix("v")}" } ?: "") to accents.secondary
}

@Composable
private fun HeroChip(text: String, color: Color, font: FontFamily, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFamily = font, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ============================================================================
// BANNERS — status / update notices, mirroring Manager's warning-banner treatment
// ============================================================================

@Composable
private fun AppInfoBanners(
    isDeleted: Boolean,
    isModified: Boolean,
    installPending: Boolean,
    installing: Boolean,
    hasUpdate: Boolean,
    record: PatchedAppRecord,
    deviceInfo: DeviceAppInfo?,
    updateInfo: RecallUpdateInfo?,
    font: FontFamily,
    accents: MorpheAccentColors,
    corners: MorpheCornerStyle,
    onRepatch: () -> Unit,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
) {
    val banners = buildList<@Composable () -> Unit> {
        if (isDeleted) {
            add {
                WarningBanner(
                    icon = MorpheIcons.Warning,
                    tone = MaterialTheme.colorScheme.error,
                    title = "Output APK missing",
                    message = "The patched APK is no longer on disk. Repatch to restore it.",
                    buttonText = "Repatch",
                    font = font,
                    corner = corners.small,
                    onClick = onRepatch,
                )
            }
        } else if (isModified) {
            add {
                Notice(
                    icon = MorpheIcons.Warning,
                    tone = accents.warning,
                    text = "This output APK was modified outside Morphe since it was patched.",
                    font = font,
                    corner = corners.small,
                )
            }
        }
        if (!isDeleted && installPending) {
            val sub = if (deviceInfo?.installed == true) {
                "v${record.apkVersion.removePrefix("v")} ready · device on v${deviceInfo.installedVersion?.removePrefix("v") ?: "?"}"
            } else {
                "v${record.apkVersion.removePrefix("v")} ready — no repatch needed"
            }
            add {
                WarningBanner(
                    icon = MorpheIcons.Download,
                    tone = accents.secondary,
                    title = if (installing) "Installing…" else "Ready to install",
                    message = sub,
                    buttonText = if (installing) "Installing…" else "Install",
                    enabled = !installing,
                    font = font,
                    corner = corners.small,
                    onClick = onInstall,
                )
            }
        }
        if (!isDeleted && hasUpdate && updateInfo != null) {
            add {
                WarningBanner(
                    icon = MorpheIcons.Update,
                    tone = accents.primary,
                    title = "Update available",
                    message = updateSummary(updateInfo) ?: "A newer patch or app version is available.",
                    buttonText = "Update",
                    font = font,
                    corner = corners.small,
                    onClick = onUpdate,
                )
            }
        }
    }
    if (banners.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            banners.forEach { it() }
        }
    }
}

@Composable
private fun WarningBanner(
    icon: ImageVector,
    tone: Color,
    title: String,
    message: String,
    buttonText: String,
    font: FontFamily,
    corner: Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(tone.copy(alpha = 0.10f))
            .border(1.dp, tone.copy(alpha = 0.30f), RoundedCornerShape(corner))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = font, color = MaterialTheme.colorScheme.onSurface)
            Text(
                message,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        BannerActionButton(buttonText, tone, font, corner, enabled, onClick)
    }
}

@Composable
private fun BannerActionButton(label: String, color: Color, font: FontFamily, corner: Dp, enabled: Boolean, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(corner))
            .background(color.copy(alpha = if (!enabled) 0.3f else if (isHovered) 1f else 0.9f))
            .hoverable(hover, enabled = enabled)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = font, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun Notice(icon: ImageVector, tone: Color, text: String, font: FontFamily, corner: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .background(tone.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.sp, fontFamily = font, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One-line summary of what an UPDATE will move to (patch + app versions). Same
 *  formatting the old Desktop detail dialog used, kept for consistent messaging. */
private fun updateSummary(u: RecallUpdateInfo): String? {
    val parts = mutableListOf<String>()
    val outdated = u.sources.filter { it.outdated && it.latestAvailableVersion != null }
    outdated.firstOrNull()?.let { s ->
        val more = outdated.size - 1
        parts += "→ patches v${s.latestAvailableVersion!!.removePrefix("v")}" + if (more > 0) " +$more" else ""
    }
    if (u.appOutdated && u.appSuggestedVersion != null) {
        parts += "app v${u.appSuggestedVersion.removePrefix("v")}"
    }
    return parts.joinToString(" · ").ifBlank { null }
}

// ============================================================================
// INFORMATION SECTION — mirrors Manager's InfoSection card of key/value rows
// ============================================================================

@Composable
private fun AppInfoSection(
    record: PatchedAppRecord,
    patchCount: Int,
    updateInfo: RecallUpdateInfo?,
    font: FontFamily,
    mono: FontFamily,
    accents: MorpheAccentColors,
    corners: MorpheCornerStyle,
    onOpenAppliedPatches: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corners.medium))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), RoundedCornerShape(corners.medium))
            .padding(vertical = 6.dp),
    ) {
        SectionLabel("Information", font)
        InfoRow(MorpheIcons.Apps, "Package", record.packageName, font, mono)
        if (!record.currentPackageName.isNullOrBlank() && record.currentPackageName != record.packageName) {
            InfoRow(MorpheIcons.DeployedCode, "Installs as", record.currentPackageName, font, mono)
        }
        InfoRow(MorpheIcons.Info, "App version", "v${record.apkVersion.removePrefix("v")}", font, mono)
        InfoRow(MorpheIcons.Info, "Patched", fullDate(record.patchedAt), font, mono)
        InfoRow(MorpheIcons.DeployedCode, "Patched with", "Morphe ${record.patchedWithMorpheVersion}", font, mono)
        InfoRow(MorpheIcons.Save, "Output size", humanSize(record.outputApkSize), font, mono)
        record.outputApkSha256?.let {
            InfoRow(MorpheIcons.Key, "Integrity", "${it.take(16)}…", font, mono)
        }
        InfoRow(MorpheIcons.FolderOpen, "Location", record.outputApkPath, font, mono, wrap = true)

        val sourceRows = updateInfo?.sources
        if (!sourceRows.isNullOrEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f), modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel("Sources", font)
            sourceRows.forEach { s ->
                val value = if (s.outdated && s.latestAvailableVersion != null) {
                    "v${s.usedVersion.removePrefix("v")} → v${s.latestAvailableVersion.removePrefix("v")} available"
                } else {
                    "v${s.usedVersion.removePrefix("v")}"
                }
                InfoRow(MorpheIcons.Route, s.name, value, font, mono)
            }
        } else if (record.sourcesSnapshot.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f), modifier = Modifier.padding(vertical = 4.dp))
            SectionLabel("Sources", font)
            record.sourcesSnapshot.forEach { src ->
                InfoRow(MorpheIcons.Route, src.sourceName, "v${src.version.removePrefix("v")}", font, mono)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f), modifier = Modifier.padding(vertical = 4.dp))
        ActionableInfoRow(
            icon = MorpheIcons.DoneAll,
            label = "Applied patches",
            value = "$patchCount across ${record.patchSelectionByBundle.size} bundle" +
                if (record.patchSelectionByBundle.size == 1) "" else "s",
            font = font,
            mono = mono,
            onClick = onOpenAppliedPatches,
        )
    }
}

@Composable
private fun SectionLabel(text: String, font: FontFamily) {
    Text(
        text = text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        fontFamily = font,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, font: FontFamily, mono: FontFamily, wrap: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = font,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = mono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (wrap) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActionableInfoRow(icon: ImageVector, label: String, value: String, font: FontFamily, mono: FontFamily, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isHovered) 0.05f else 0f))
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = font,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            fontFamily = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            MorpheIcons.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp).rotate(-90f),
        )
    }
}

// ============================================================================
// APPLIED PATCHES DIALOG — mirrors Manager's dedicated applied-patches popup
// ============================================================================

@Composable
private fun AppliedPatchesDialog(
    record: PatchedAppRecord,
    font: FontFamily,
    mono: FontFamily,
    accents: MorpheAccentColors,
    corners: MorpheCornerStyle,
    onDismiss: () -> Unit,
    mutedSourceIds: Set<String> = emptySet(),
    onToggleSourceMute: (String) -> Unit = {},
) {
    var search by remember { mutableStateOf("") }
    val patchCount = record.patchSelectionByBundle.values.sumOf { it.size }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            val width = (maxWidth * 0.6f).coerceIn(360.dp, 520.dp)
            val height = (maxHeight * 0.75f).coerceAtMost(560.dp)
            Surface(
                shape = RoundedCornerShape(corners.large),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 20.dp,
                modifier = Modifier.width(width).height(height).pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(MorpheIcons.DoneAll, contentDescription = null, tint = accents.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Applied patches",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = font,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "$patchCount",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = font,
                            color = accents.primary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (patchCount > 5) {
                        AppliedPatchesSearchField(search, { search = it }, font, corners.small, accents.primary)
                        Spacer(Modifier.height(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        record.patchSelectionByBundle.forEach { (bundle, patches) ->
                            val shown = (if (search.isBlank()) patches else patches.filter { it.contains(search, ignoreCase = true) }).sorted()
                            if (shown.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                                    Icon(MorpheIcons.Route, contentDescription = null, tint = accents.secondary, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        bundle,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = font,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${shown.size}",
                                        fontSize = 11.sp,
                                        fontFamily = font,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    val isMuted = bundle in mutedSourceIds
                                    IconButton(
                                        onClick = { onToggleSourceMute(bundle) },
                                        modifier = Modifier.size(22.dp),
                                    ) {
                                        Icon(
                                            if (isMuted) MorpheIcons.VisibilityOff else MorpheIcons.Visibility,
                                            contentDescription = if (isMuted) {
                                                "Offer patches from this source for this app again"
                                            } else {
                                                "Stop offering patches from this source for this app"
                                            },
                                            tint = if (isMuted) {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                                shown.forEach { uid ->
                                    Text(
                                        text = uid,
                                        fontSize = 12.sp,
                                        fontFamily = mono,
                                        fontWeight = FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 19.dp, top = 2.dp),
                                    )
                                }
                            }
                        }
                        if (record.patchOptionValues.isNotEmpty() && search.isBlank()) {
                            Text(
                                "OPTIONS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                fontFamily = font,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                            )
                            record.patchOptionValues.forEach { (k, v) ->
                                Text(
                                    text = "$k = $v",
                                    fontSize = 12.sp,
                                    fontFamily = mono,
                                    fontWeight = FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    RailCloseButton(font = font, corner = corners.small, onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun AppliedPatchesSearchField(value: String, onValueChange: (String) -> Unit, font: FontFamily, corner: Dp, accent: Color) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 12.sp, fontFamily = font, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(corner))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(corner))
                    .padding(horizontal = 10.dp),
            ) {
                Icon(MorpheIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            "Search patches…",
                            fontSize = 12.sp,
                            fontFamily = font,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    inner()
                }
            }
        },
    )
}

// ============================================================================
// Small formatting helpers (kept local/private — same formatting the previous
// Desktop dialog used, so behaviour/wording doesn't regress).
// ============================================================================

private fun fullDate(millis: Long): String =
    SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(Date(millis))

private fun relativeOrShortDate(millis: Long): String {
    val now = System.currentTimeMillis()
    val days = ((now - millis) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(millis))
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "-"
    val mb = bytes / 1_048_576.0
    return if (mb >= 1) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
}
