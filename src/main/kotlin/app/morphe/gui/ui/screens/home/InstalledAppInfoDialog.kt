/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.morphe.gui.data.model.AppCardColorDefaults
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.repository.ChangelogRepository
import app.morphe.gui.data.repository.PatchSourceManager
import app.morphe.gui.ui.components.ActionButton
import app.morphe.gui.ui.components.AppCard
import app.morphe.gui.ui.components.LocalAppCardInk
import app.morphe.gui.ui.components.FormattedReleaseNotes
import app.morphe.gui.ui.components.MorpheActionButton
import app.morphe.gui.ui.components.MorpheCardChip
import app.morphe.gui.ui.components.handCursor
import app.morphe.gui.ui.components.morpheScrollbarStyle
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.ui.theme.LocalMorpheMono
import app.morphe.gui.util.ChangelogEntry
import app.morphe.gui.util.ChangelogParser
import app.morphe.gui.util.DeviceInstallState
import app.morphe.gui.util.FormatUtils
import app.morphe.gui.util.currentLocale
import app.morphe.gui.util.readableOn
import app.morphe.gui.util.withVersionPrefix
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Everything Morphe knows about one build it produced, and everything that can
 * be done with it.
 *
 * Where the home card answers "what is this and does it need my attention", this
 * answers "what exactly is happening and what can I do about it". Both read the
 * same [HomeAppItem]: installation state, update state, supported version, clone
 * state, identity, version and accent are resolved once in `HomeViewModel` and
 * never recomputed here, so the two can never disagree in front of the user.
 *
 * The composition follows Manager's landscape dialog — a fixed action rail, a
 * divider, and a scrolling pane of hero, banners and information — with Android's
 * install types, mount and root actions left out rather than faked, and Desktop's
 * own device layer in their place.
 */
@Composable
fun InstalledAppInfoDialog(
    item: HomeAppItem,
    appliedBundles: List<AppliedBundle>,
    installing: Boolean,
    uninstalling: Boolean,
    mutedSourceIds: Set<String>,
    onDismiss: () -> Unit,
    onPatch: () -> Unit,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onForget: () -> Unit,
    onOpenFolder: () -> Unit,
    onResetSelections: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleSourceMute: (String) -> Unit,
    onIgnoreVersion: (() -> Unit)?,
    onStopIgnoringVersion: (() -> Unit)?,
) {
    val record = item.record ?: return
    val corners = LocalMorpheCorners.current

    // The app's own color, straight from the palette its patch bundle declares
    // and the record retains. One accent for the hero, the primary action, the
    // banners and the borders, so the dialog reads as an extension of the card.
    val accent = remember(item.appIconColorHex) {
        AppCardColorDefaults.bundleColors(item.appIconColorHex).first()
    }

    var appliedPatchesOpen by remember(item.id) { mutableStateOf(false) }
    var changelogSourceId by remember(item.id) { mutableStateOf<String?>(null) }

    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(item.id) { entered = true }

    // Rebuilding is offered by a banner whenever there is a reason to rebuild, so
    // the rail must not put a second identical call to action next to it
    val bannerOffersPatch = item.status == HomeAppStatus.ARTIFACT_MISSING ||
        item.status == HomeAppStatus.REPLACED ||
        item.showsRebuildBadge

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
            val dialogWidth = (maxWidth * 0.94f).coerceAtMost(900.dp).coerceAtLeast(560.dp)
            val dialogHeight = (maxHeight * 0.88f).coerceAtMost(660.dp).coerceAtLeast(420.dp)

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
                        // Clicks inside must not reach the scrim that dismisses
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        ActionRail(
                            item = item,
                            accent = accent,
                            showPrimaryPatch = !bannerOffersPatch,
                            installing = installing,
                            uninstalling = uninstalling,
                            entered = entered,
                            onDismiss = onDismiss,
                            onPatch = onPatch,
                            onInstall = onInstall,
                            onUninstall = onUninstall,
                            onOpenFolder = onOpenFolder,
                            onForget = onForget,
                            onResetSelections = onResetSelections,
                            onToggleHidden = onToggleHidden,
                        )

                        VerticalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxHeight(),
                        )

                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val listState = rememberLazyListState()
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().padding(end = 10.dp),
                                contentPadding = PaddingValues(24.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                item(key = "hero") {
                                    StaggeredItem(entered, index = 0) {
                                        AppInfoHero(item = item, accent = accent)
                                    }
                                }
                                item(key = "banners") {
                                    AppInfoBanners(
                                        item = item,
                                        accent = accent,
                                        installing = installing,
                                        entered = entered,
                                        onPatch = onPatch,
                                        onUpdate = onUpdate,
                                        onInstall = onInstall,
                                        onShowChangelog = { sourceId -> changelogSourceId = sourceId },
                                        onIgnoreVersion = onIgnoreVersion,
                                    )
                                }
                                item(key = "info") {
                                    StaggeredItem(entered, index = 2) {
                                        AppInfoSection(
                                            item = item,
                                            appliedBundles = appliedBundles,
                                            onStopIgnoringVersion = onStopIgnoringVersion,
                                            onOpenAppliedPatches = { appliedPatchesOpen = true },
                                        )
                                    }
                                }
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
            item = item,
            bundles = appliedBundles,
            optionValues = record.patchOptionValues,
            accent = accent,
            mutedSourceIds = mutedSourceIds,
            onToggleSourceMute = onToggleSourceMute,
            onDismiss = { appliedPatchesOpen = false },
        )
    }

    changelogSourceId?.let { sourceId ->
        BundleChangelogDialog(
            sourceId = sourceId,
            sinceVersion = item.updateInfo?.sources?.firstOrNull { it.sourceId == sourceId }?.usedVersion,
            onDismiss = { changelogSourceId = null },
        )
    }
}

// ============================================================================
// ACTION RAIL — primary / secondary / destructive, in that order
// ============================================================================

/** One offer in the rail. Kept as data so the ordering is visible in one place. */
private data class DialogAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

@Composable
private fun ActionRail(
    item: HomeAppItem,
    accent: Color,
    showPrimaryPatch: Boolean,
    installing: Boolean,
    uninstalling: Boolean,
    entered: Boolean,
    onDismiss: () -> Unit,
    onPatch: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onOpenFolder: () -> Unit,
    onForget: () -> Unit,
    onResetSelections: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    val font = LocalMorpheFont.current
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val error = MaterialTheme.colorScheme.error

    val secondary = buildList {
        if (item.installPending) {
            add(
                DialogAction(
                    label = if (installing) stringResource(Res.string.home_action_installing)
                    else if (item.isInstalledOnDevice) stringResource(Res.string.installed_info_action_reinstall)
                    else stringResource(Res.string.home_action_install),
                    icon = MorpheIcons.Download,
                    enabled = !installing,
                    onClick = onInstall,
                )
            )
        }
        if (item.patchedApk != null) {
            add(DialogAction(stringResource(Res.string.open_folder), MorpheIcons.FolderOpen, onOpenFolder))
        }
        add(
            DialogAction(
                label = stringResource(
                    if (item.isHidden) Res.string.home_card_unhide else Res.string.home_card_hide
                ),
                icon = if (item.isHidden) MorpheIcons.Visibility else MorpheIcons.VisibilityOff,
                onClick = onToggleHidden,
            )
        )
    }

    val destructive = buildList {
        // Only Morphe's own build may be uninstalled from here: a package whose
        // identity is unconfirmed is somebody else's app until proven otherwise
        if (item.isInstalledOnDevice) {
            add(
                DialogAction(
                    label = if (uninstalling) stringResource(Res.string.home_action_uninstalling)
                    else stringResource(Res.string.home_dialog_uninstall_button),
                    icon = MorpheIcons.Delete,
                    enabled = !uninstalling,
                    destructive = true,
                    onClick = onUninstall,
                )
            )
        }
        add(
            DialogAction(
                stringResource(Res.string.home_dialog_forget_button),
                MorpheIcons.PlaylistRemove,
                onForget,
                destructive = true,
            )
        )
        add(
            DialogAction(
                stringResource(Res.string.installed_info_reset_saved_patches),
                MorpheIcons.Undo,
                onResetSelections,
                destructive = true,
            )
        )
    }

    Column(
        modifier = Modifier
            .width(210.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(vertical = 20.dp, horizontal = 14.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showPrimaryPatch) {
                StaggeredItem(entered, index = 1) {
                    MorpheActionButton(
                        label = stringResource(Res.string.installed_info_action_patch),
                        icon = MorpheIcons.AutoAwesome,
                        accent = accent,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onPatch,
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
            (secondary + destructive).forEachIndexed { index, action ->
                StaggeredItem(entered, index = index + 2) {
                    ActionButton(
                        label = action.label,
                        icon = action.icon,
                        font = font,
                        borderColor = if (action.destructive) error.copy(alpha = 0.35f) else outline,
                        contentColor = if (action.destructive) error else MaterialTheme.colorScheme.onSurfaceVariant,
                        enabled = action.enabled,
                        onClick = action.onClick,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        Spacer(Modifier.height(10.dp))
        ActionButton(
            label = stringResource(Res.string.close),
            icon = MorpheIcons.Close,
            font = font,
            borderColor = outline,
            onClick = onDismiss,
        )
    }
}

// ============================================================================
// HERO — identity, at the accent the card carries
// ============================================================================

@Composable
private fun AppInfoHero(item: HomeAppItem, accent: Color) {
    val font = LocalMorpheFont.current
    val mono = LocalMorpheMono.current
    val corners = LocalMorpheCorners.current
    val record = item.record

    val heroFill = accent.copy(alpha = 0.15f)
    val onHero = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corners.medium))
            .background(heroFill)
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(corners.medium))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The same mark the card draws, so the dialog opens on what was clicked.
            // A real APK-derived icon will replace the initial here and on the card
            // together; nothing else in either surface has to change for it.
            AppCard(
                modifier = Modifier.size(64.dp),
                cornerRadius = corners.medium,
                appIconColorHex = item.appIconColorHex,
                interactive = false,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = item.displayName.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = font,
                        color = LocalAppCardInk.current.title,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = font,
                    color = onHero,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.installedPackageName,
                    fontSize = 12.sp,
                    fontFamily = mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.version.withVersionPrefix(),
                    fontSize = 13.sp,
                    fontFamily = font,
                    color = onHero.copy(alpha = 0.6f),
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.isClone) {
                HeroChip(stringResource(Res.string.installed_info_badge_copy), accent, MorpheIcons.ContentCopy)
            }
            record?.patchedAt?.let {
                HeroChip(
                    text = stringResource(Res.string.installed_info_patched_when, relativeOrShortDate(it)),
                    accent = accent,
                    icon = MorpheIcons.Update,
                )
            }
            HeroChip(
                text = deviceChipLabel(item),
                accent = accent,
                icon = MorpheIcons.PhoneAndroid,
            )
        }
    }
}

/** What the attached device says about this build, in the words the card used. */
@Composable
private fun deviceChipLabel(item: HomeAppItem): String = when (item.deviceState) {
    DeviceInstallState.NO_DEVICE -> stringResource(Res.string.installed_info_no_device)
    DeviceInstallState.NOT_INSTALLED -> stringResource(Res.string.home_app_row_not_on_device)
    DeviceInstallState.REPLACED -> stringResource(Res.string.installed_info_not_morphe_signed)
    DeviceInstallState.UNVERIFIED -> stringResource(Res.string.home_badge_unverified)
    DeviceInstallState.INSTALLED ->
        item.deviceVersion?.let {
            stringResource(Res.string.home_app_row_on_device_with_version, it.withVersionPrefix())
        } ?: stringResource(Res.string.home_app_row_on_device)
}

@Composable
private fun HeroChip(text: String, accent: Color, icon: ImageVector) {
    val font = LocalMorpheFont.current
    val fill = accent.copy(alpha = 0.18f)
    val ink = accent.readableOn(fill, MaterialTheme.colorScheme.surface)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFamily = font, color = ink, maxLines = 1)
    }
}

// ============================================================================
// BANNERS — what needs attention, and the one action that answers it
// ============================================================================

@Composable
private fun AppInfoBanners(
    item: HomeAppItem,
    accent: Color,
    installing: Boolean,
    entered: Boolean,
    onPatch: () -> Unit,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onShowChangelog: (String) -> Unit,
    onIgnoreVersion: (() -> Unit)?,
) {
    val error = MaterialTheme.colorScheme.error
    val patchLabel = stringResource(Res.string.installed_info_action_patch)

    // The source whose changelog explains the pending patch update. Only a source
    // that is still around has one to read, so the offer disappears with it.
    val changelogSource = item.updateInfo?.sources
        ?.firstOrNull { it.hasRelevantChanges }
        ?.sourceId

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BannerSlot(visible = item.status == HomeAppStatus.ARTIFACT_MISSING, entered = entered, index = 1) {
            AttentionBanner(
                icon = MorpheIcons.Warning,
                tone = error,
                title = stringResource(Res.string.installed_info_apk_missing_title),
                message = stringResource(Res.string.installed_info_apk_missing_message),
                actionLabel = patchLabel,
                onAction = onPatch,
            )
        }
        BannerSlot(visible = item.status == HomeAppStatus.REPLACED, entered = entered, index = 1) {
            AttentionBanner(
                icon = MorpheIcons.AutoAwesome,
                tone = accent,
                title = stringResource(Res.string.installed_info_not_patched_title),
                message = stringResource(Res.string.installed_info_not_patched_description),
                actionLabel = patchLabel,
                onAction = onPatch,
            )
        }
        BannerSlot(visible = item.status == HomeAppStatus.UNVERIFIED, entered = entered, index = 1) {
            Notice(
                icon = MorpheIcons.Info,
                tone = LocalMorpheAccents.current.warning,
                text = stringResource(Res.string.installed_info_unverified_notice),
            )
        }
        BannerSlot(visible = item.status == HomeAppStatus.ARTIFACT_MODIFIED, entered = entered, index = 1) {
            Notice(
                icon = MorpheIcons.Warning,
                tone = LocalMorpheAccents.current.warning,
                text = stringResource(Res.string.installed_info_modified_notice),
            )
        }
        // The patched build is here and the device is not carrying it, which is
        // answered by installing rather than by patching again
        BannerSlot(visible = item.installPending, entered = entered, index = 2) {
            AttentionBanner(
                icon = MorpheIcons.Download,
                tone = LocalMorpheAccents.current.secondary,
                title = stringResource(Res.string.installed_info_uninstalled_title),
                message = stringResource(Res.string.installed_info_uninstalled_description),
                actionLabel = if (installing) stringResource(Res.string.home_action_installing)
                else stringResource(Res.string.home_action_install),
                enabled = !installing,
                onAction = onInstall,
            )
        }
        // Newer patches and a newer supported app version are both answered by
        // rebuilding, so they share a banner rather than stacking two of them
        BannerSlot(visible = item.showsRebuildBadge, entered = entered, index = 2) {
            AttentionBanner(
                icon = MorpheIcons.Update,
                tone = accent,
                title = stringResource(
                    if (item.showsVersionBadge) Res.string.installed_info_app_update_title
                    else Res.string.installed_info_patch_update_title
                ),
                message = stringResource(
                    if (item.showsVersionBadge) Res.string.installed_info_app_update_description
                    else Res.string.installed_info_patch_update_description
                ),
                versions = item.versionStatus
                    ?.takeIf { item.showsVersionBadge }
                    ?.let { it.patchedVersion.withVersionPrefix() to it.supportedVersion.withVersionPrefix() },
                actionLabel = stringResource(Res.string.home_action_update),
                onAction = onUpdate,
                secondaryActions = buildList {
                    changelogSource?.let { sourceId ->
                        add(
                            DialogAction(
                                stringResource(Res.string.installed_info_whats_new),
                                MorpheIcons.Article,
                            ) { onShowChangelog(sourceId) }
                        )
                    }
                    onIgnoreVersion?.let {
                        add(
                            DialogAction(
                                stringResource(Res.string.installed_info_ignore_version),
                                MorpheIcons.VisibilityOff,
                                it,
                            )
                        )
                    }
                },
            )
        }
    }
}

/** One banner's place in the group, animated in and out of it. */
@Composable
private fun BannerSlot(
    visible: Boolean,
    entered: Boolean,
    index: Int,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(200), expandFrom = Alignment.Top) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(160), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
    ) {
        StaggeredItem(entered = entered, index = index, content = content)
    }
}

@Composable
private fun AttentionBanner(
    icon: ImageVector,
    tone: Color,
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
    versions: Pair<String, String>? = null,
    secondaryActions: List<DialogAction> = emptyList(),
) {
    val font = LocalMorpheFont.current
    val corners = LocalMorpheCorners.current
    val fill = tone.copy(alpha = 0.12f)
    val ink = tone.readableOn(fill, MaterialTheme.colorScheme.surface)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corners.medium))
            .background(fill)
            .border(1.dp, tone.copy(alpha = 0.30f), RoundedCornerShape(corners.medium))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = font,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    message,
                    fontSize = 11.sp,
                    fontFamily = font,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                versions?.let { (from, to) ->
                    Spacer(Modifier.height(4.dp))
                    VersionTransition(from = from, to = to, ink = ink)
                }
            }
            Spacer(Modifier.width(12.dp))
            MorpheActionButton(
                label = actionLabel,
                accent = tone,
                enabled = enabled,
                onClick = onAction,
            )
        }
        // Side by side, because the banner's own button is the one meant to stand
        // out and a column of full-width buttons under it reads as equal offers
        if (secondaryActions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                secondaryActions.forEach { action ->
                    Box(modifier = Modifier.weight(1f)) {
                        ActionButton(
                            label = action.label,
                            icon = action.icon,
                            font = font,
                            borderColor = tone.copy(alpha = 0.30f),
                            contentColor = ink,
                            onClick = action.onClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The move a banner is offering, from the version patched to the one a rebuild
 * would land on. The banner's own wording says which is which, so the line is
 * unlabeled on screen and read out labeled instead.
 */
@Composable
private fun VersionTransition(from: String, to: String, ink: Color) {
    val font = LocalMorpheFont.current
    val readOut = stringResource(Res.string.installed_info_version_move, from, to)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = readOut },
    ) {
        Text(from, fontSize = 11.sp, fontFamily = font, color = ink.copy(alpha = 0.7f))
        Icon(MorpheIcons.ArrowForward, contentDescription = null, tint = ink.copy(alpha = 0.5f), modifier = Modifier.size(12.dp))
        Text(to, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = font, color = ink)
    }
}

@Composable
private fun Notice(icon: ImageVector, tone: Color, text: String) {
    val font = LocalMorpheFont.current
    val corners = LocalMorpheCorners.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corners.medium))
            .background(tone.copy(alpha = 0.10f))
            .border(1.dp, tone.copy(alpha = 0.25f), RoundedCornerShape(corners.medium))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================================================================
// INFORMATION — what the build is, where it came from, what went into it
// ============================================================================

@Composable
private fun AppInfoSection(
    item: HomeAppItem,
    appliedBundles: List<AppliedBundle>,
    onStopIgnoringVersion: (() -> Unit)?,
    onOpenAppliedPatches: () -> Unit,
) {
    val record = item.record ?: return
    val font = LocalMorpheFont.current
    val mono = LocalMorpheMono.current
    val corners = LocalMorpheCorners.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corners.medium))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), RoundedCornerShape(corners.medium))
            .padding(vertical = 6.dp),
    ) {
        SectionLabel(stringResource(Res.string.installed_info_section_information), font)

        InfoRow(MorpheIcons.Apps, stringResource(Res.string.installed_info_label_package), item.installedPackageName, font, mono)
        if (record.isRenamed) {
            InfoRow(MorpheIcons.Category, stringResource(Res.string.installed_info_original_package), record.packageName, font, mono)
        }
        InfoRow(MorpheIcons.Info, stringResource(Res.string.installed_info_patched_version), item.version.withVersionPrefix(), font, mono)
        item.deviceVersion?.takeIf { it != item.version }?.let {
            InfoRow(MorpheIcons.PhoneAndroid, stringResource(Res.string.installed_info_device_version), it.withVersionPrefix(), font, mono)
        }

        // Kept in place even while a banner above says the same thing, so the
        // version the sources cover can be looked up rather than only met as a
        // warning. A version that was turned down is where the offer is resumed.
        item.supportedVersion?.let { supported ->
            if (onStopIgnoringVersion != null) {
                InfoRow(
                    icon = MorpheIcons.VisibilityOff,
                    label = stringResource(Res.string.installed_info_newest_supported_version),
                    value = supported.withVersionPrefix(),
                    font = font,
                    mono = mono,
                    action = stringResource(Res.string.installed_info_stop_ignoring_version) to onStopIgnoringVersion,
                )
            } else {
                InfoRow(MorpheIcons.Update, stringResource(Res.string.installed_info_newest_supported_version), supported.withVersionPrefix(), font, mono)
            }
        }

        InfoRow(MorpheIcons.Save, stringResource(Res.string.home_detail_output_size_label), humanSize(record.outputApkSize), font, mono)
        record.outputApkSha256?.let {
            InfoRow(MorpheIcons.Key, stringResource(Res.string.installed_info_label_integrity), "${it.take(16)}…", font, mono)
        }
        InfoRow(MorpheIcons.FolderOpen, stringResource(Res.string.installed_info_label_location), record.outputApkPath, font, mono, wrap = true)
        // Only while the device is actually holding it: a path read on a previous
        // connection says nothing about the device in front of the user now
        item.deviceApkPath?.takeIf { item.isOnDevice }?.let {
            InfoRow(MorpheIcons.PhoneAndroid, stringResource(Res.string.installed_info_device_apk_path), it, font, mono, wrap = true)
        }
        InfoRow(
            MorpheIcons.DeployedCode,
            stringResource(Res.string.installed_info_label_patched_with),
            "${stringResource(Res.string.app_name)} ${record.patchedWithMorpheVersion}",
            font,
            mono,
        )
        InfoRow(MorpheIcons.Update, stringResource(Res.string.home_your_apps_status_patched), fullDate(record.patchedAt), font, mono)

        if (appliedBundles.isNotEmpty()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            SectionLabel(stringResource(Res.string.installed_info_section_sources), font)
            appliedBundles.forEach { bundle ->
                val update = item.updateInfo?.sources?.firstOrNull { it.sourceId == bundle.sourceId }
                val value = when {
                    update?.outdated == true && update.latestAvailableVersion != null -> stringResource(
                        Res.string.installed_info_source_update_available,
                        bundle.version.orEmpty().withVersionPrefix(),
                        update.latestAvailableVersion.withVersionPrefix(),
                    )
                    bundle.version != null -> bundle.version.withVersionPrefix()
                    else -> stringResource(Res.string.unknown)
                }
                InfoRow(
                    icon = MorpheIcons.Route,
                    label = if (bundle.available) bundle.title else {
                        "${bundle.title} · ${stringResource(Res.string.installed_info_source_removed)}"
                    },
                    value = value,
                    font = font,
                    mono = mono,
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            InfoRow(
                icon = MorpheIcons.DoneAll,
                label = stringResource(Res.string.installed_info_applied_patches),
                value = stringResource(
                    Res.string.installed_info_applied_patches_value,
                    item.appliedPatchCount,
                    pluralStringResource(
                        Res.plurals.installed_info_bundle_count,
                        appliedBundles.size,
                        appliedBundles.size,
                    ),
                ),
                font = font,
                mono = mono,
                onClick = onOpenAppliedPatches,
            )
        }
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

/**
 * One label/value line. [onClick] turns the whole row into a way in to more
 * detail; [action] hangs a single named affordance off the end of it. A row is
 * never given both, so there is only ever one thing a click can mean.
 */
@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    font: FontFamily,
    mono: FontFamily,
    wrap: Boolean = false,
    onClick: (() -> Unit)? = null,
    action: Pair<String, () -> Unit>? = null,
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isHovered) 0.05f else 0f))
                        .hoverable(hover)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = if (wrap) Alignment.Top else Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontFamily = font,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(124.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontFamily = mono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (wrap) 3 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.let { (actionLabel, onAction) ->
            Spacer(Modifier.width(8.dp))
            MorpheCardChip(text = actionLabel, onCard = false, onClick = onAction)
        }
        if (onClick != null) {
            Icon(
                MorpheIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp).rotate(-90f),
            )
        }
    }
}

// ============================================================================
// APPLIED PATCHES — what actually went into the build, by the names it used
// ============================================================================

@Composable
private fun AppliedPatchesDialog(
    item: HomeAppItem,
    bundles: List<AppliedBundle>,
    optionValues: Map<String, String>,
    accent: Color,
    mutedSourceIds: Set<String>,
    onToggleSourceMute: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val font = LocalMorpheFont.current
    val mono = LocalMorpheMono.current
    val corners = LocalMorpheCorners.current
    var search by remember { mutableStateOf("") }
    val totalPatches = bundles.sumOf { it.patchCount }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            val width = (maxWidth * 0.6f).coerceIn(380.dp, 560.dp)
            val height = (maxHeight * 0.75f).coerceAtMost(600.dp)
            Surface(
                shape = RoundedCornerShape(corners.large),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 20.dp,
                modifier = Modifier.width(width).height(height).pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(MorpheIcons.DoneAll, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(Res.string.installed_info_applied_patches),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = font,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                item.displayName,
                                fontSize = 11.sp,
                                fontFamily = font,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "$totalPatches",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = font,
                            color = accent,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    if (totalPatches > 5) {
                        PatchSearchField(search, { search = it }, font, corners.small, accent)
                        Spacer(Modifier.height(8.dp))
                    }
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        bundles.forEach { bundle ->
                            AppliedBundleSection(
                                bundle = bundle,
                                search = search,
                                muted = bundle.sourceId in mutedSourceIds,
                                accent = accent,
                                font = font,
                                mono = mono,
                                onToggleMute = { onToggleSourceMute(bundle.sourceId) },
                            )
                        }
                        if (optionValues.isNotEmpty() && search.isBlank()) {
                            SectionLabel(stringResource(Res.string.installed_info_section_patch_options), font)
                            optionValues.forEach { (key, value) ->
                                Text(
                                    text = "$key = $value",
                                    fontSize = 12.sp,
                                    fontFamily = mono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ActionButton(
                        label = stringResource(Res.string.close),
                        icon = MorpheIcons.Close,
                        font = font,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppliedBundleSection(
    bundle: AppliedBundle,
    search: String,
    muted: Boolean,
    accent: Color,
    font: FontFamily,
    mono: FontFamily,
    onToggleMute: () -> Unit,
) {
    fun matches(name: String) = search.isBlank() || name.contains(search, ignoreCase = true)
    val resolved = bundle.patchNames.filter(::matches)
    val unresolved = bundle.unresolvedNames.filter(::matches)
    if (resolved.isEmpty() && unresolved.isEmpty()) return

    Column(modifier = Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Icon(MorpheIcons.Route, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = bundle.version?.let { "${bundle.title} ${it.withVersionPrefix()}" } ?: bundle.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${resolved.size + unresolved.size}",
                fontSize = 11.sp,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleMute, modifier = Modifier.size(22.dp)) {
                Icon(
                    if (muted) MorpheIcons.VisibilityOff else MorpheIcons.Visibility,
                    contentDescription = stringResource(
                        if (muted) Res.string.installed_info_unmute_source_description
                        else Res.string.installed_info_mute_source_description
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (muted) 0.5f else 1f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (!bundle.available) {
            Text(
                text = stringResource(Res.string.installed_info_source_unavailable_note),
                fontSize = 10.sp,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 19.dp, bottom = 4.dp),
            )
        }
        resolved.forEach { PatchNameRow(it, mono, dimmed = false) }
        unresolved.forEach { PatchNameRow(it, mono, dimmed = true) }
    }
}

@Composable
private fun PatchNameRow(name: String, mono: FontFamily, dimmed: Boolean) {
    Text(
        text = name,
        fontSize = 12.sp,
        fontFamily = mono,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dimmed) 0.6f else 1f),
        modifier = Modifier.padding(start = 19.dp, top = 2.dp),
    )
}

@Composable
private fun PatchSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    font: FontFamily,
    corner: Dp,
    accent: Color,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 12.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurface),
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
                Icon(
                    MorpheIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(Res.string.patches_search_hint),
                            fontSize = 12.sp,
                            fontFamily = font,
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
// WHAT'S NEW — the source's own changelog, since the version this build used
// ============================================================================

/**
 * What a source has published about this app since the build was patched.
 *
 * Reuses the changelog Morphe already fetches to decide whether to badge an
 * update at all, so opening this never asks a question the badge did not already
 * ask, and reuses [FormattedReleaseNotes] rather than rendering markdown twice.
 */
@Composable
private fun BundleChangelogDialog(
    sourceId: String,
    sinceVersion: String?,
    onDismiss: () -> Unit,
) {
    val font = LocalMorpheFont.current
    val corners = LocalMorpheCorners.current
    val changelogRepository = koinInject<ChangelogRepository>()
    val sources by koinInject<PatchSourceManager>().allSources.collectAsState()
    val source: PatchSource? = sources.firstOrNull { it.id == sourceId }

    var entries by remember(sourceId) { mutableStateOf<List<ChangelogEntry>?>(null) }
    var loading by remember(sourceId) { mutableStateOf(true) }

    LaunchedEffect(sourceId, source?.usePreRelease) {
        loading = true
        entries = source?.let { changelogRepository.entriesFor(it, prerelease = it.usePreRelease) }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            val width = (maxWidth * 0.66f).coerceIn(420.dp, 720.dp)
            Surface(
                shape = RoundedCornerShape(corners.large),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 20.dp,
                modifier = Modifier
                    .width(width)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(
                            Res.string.installed_info_changelog_title,
                            source?.name ?: stringResource(Res.string.installed_info_source_removed),
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = font,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
                        val scroll = rememberScrollState()
                        Column(modifier = Modifier.verticalScroll(scroll)) {
                            val loaded = entries
                            when {
                                loading -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Text(
                                        stringResource(Res.string.source_details_loading_changelog),
                                        fontSize = 11.sp,
                                        fontFamily = font,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                loaded.isNullOrEmpty() -> Text(
                                    stringResource(Res.string.source_details_no_changelog),
                                    fontSize = 11.sp,
                                    fontFamily = font,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> {
                                    val newer = ChangelogParser
                                        .entriesNewerThan(loaded, sinceVersion)
                                        .ifEmpty { loaded.take(1) }
                                    newer.forEach { entry ->
                                        Text(
                                            text = entry.version.withVersionPrefix(),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = font,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                        )
                                        FormattedReleaseNotes(markdown = entry.content)
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(scroll),
                            style = morpheScrollbarStyle(),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    ActionButton(
                        label = stringResource(Res.string.close),
                        icon = MorpheIcons.Close,
                        font = font,
                        borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

// ============================================================================
// Entrance animation + formatting helpers
// ============================================================================

/**
 * Wraps content in a staggered entrance. One progress float drives alpha and
 * offset together, so each item has one recomposition subscriber rather than
 * three. Each item arrives [index] * 60ms after the dialog does.
 */
@Composable
private fun StaggeredItem(entered: Boolean, index: Int, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 280, delayMillis = index * 60, easing = EaseOutCubic),
        label = "installedAppInfoItem$index",
    )
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress
            translationY = lerp(24f, 0f, progress)
        }
    ) {
        content()
    }
}

@Composable
private fun fullDate(millis: Long): String = FormatUtils.formatDateTime(millis, currentLocale())

@Composable
private fun relativeOrShortDate(millis: Long): String {
    val now = System.currentTimeMillis()
    val days = ((now - millis) / 86_400_000L).toInt()
    return when {
        days <= 0 -> stringResource(Res.string.home_date_today)
        days == 1 -> stringResource(Res.string.home_date_yesterday)
        days < 7 -> stringResource(Res.string.home_date_days_ago, days)
        else -> FormatUtils.formatShortDate(millis, currentLocale())
    }
}

@Composable
private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    return FormatUtils.formatFileSize(bytes, currentLocale())
}
