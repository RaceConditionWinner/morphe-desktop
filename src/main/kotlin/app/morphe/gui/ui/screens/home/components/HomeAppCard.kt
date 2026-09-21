/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.gui.data.model.SupportedApp
import app.morphe.gui.ui.components.AppCard
import app.morphe.gui.ui.components.AppCardInk
import app.morphe.gui.ui.components.LocalAppCardInk
import app.morphe.gui.ui.components.MorpheCardChip
import app.morphe.gui.ui.components.MorpheTooltip
import app.morphe.gui.ui.components.handCursor
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.screens.home.HomeAppItem
import app.morphe.gui.ui.screens.home.HomeAppStatus
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.util.DownloadUrlResolver.openUrlAndFollowRedirects
import app.morphe.gui.util.withVersionPrefix
import app.morphe.morphe_desktop.generated.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * A verdict answered from the resolver's cache lands within a frame, so the
 * pending badge waits rather than flashing on every refresh.
 */
private const val VERIFICATION_BADGE_DELAY_MS = 400L

/**
 * Versions long enough to carry a build stamp would leave the badge no room next
 * to the version the card already shows, so those are badged by word instead.
 */
private const val MAX_BADGE_VERSION_LENGTH = 10

/** Height the compact row reserves, so a badge coming and going never moves the name. */
private val CompactRowHeight = 56.dp

/**
 * One home screen card: an app Morphe has a build of, one of the copies it was
 * cloned into, or an app it supports and has never patched.
 *
 * There is one card, not one per list: everything that differs between a patched
 * and an unpatched app is a difference in [item], not a different composable.
 * The card answers "what is this, and does it need my attention"; everything
 * else belongs in the app information dialog.
 *
 * @param expanded Whether the supported-version body is open. Only ever offered
 *   for an app Morphe has no build of, where picking a version is the next step.
 */
@Composable
internal fun HomeAppCard(
    item: HomeAppItem,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    patchSourceNames: List<String> = emptyList(),
) {
    val corners = LocalMorpheCorners.current
    val font = LocalMorpheFont.current

    // Held here rather than inside the badge row so it survives the row's own
    // recompositions, and reset the moment the verdict lands
    var showsPendingBadge by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, item.isVerificationPending) {
        if (!item.isVerificationPending) {
            showsPendingBadge = false
            return@LaunchedEffect
        }
        delay(VERIFICATION_BADGE_DELAY_MS)
        showsPendingBadge = true
    }

    val status = if (showsPendingBadge || item.status != HomeAppStatus.PENDING) {
        item.status
    } else {
        // The verdict is still out, and saying so this early reads as a problem
        HomeAppStatus.NONE
    }

    val version = remember(item.version) { item.version.withVersionPrefix() }
    val description = homeAppCardDescription(item, status)
    val hoverSource = remember { MutableInteractionSource() }
    val isHovered by hoverSource.collectIsHoveredAsState()

    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(hoverSource)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        cornerRadius = corners.medium,
        appIconColorHex = item.appIconColorHex,
        onClick = onClick,
    ) {
        val ink = LocalAppCardInk.current

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(CompactRowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeAppAvatar(item, ink)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = font,
                        color = ink.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        // Reserved whether a badge is showing or not, so the name
                        // above stays put as state comes and goes
                        modifier = Modifier.height(22.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Says what the card is rather than how its build is doing,
                        // so it leads the row and stays put. Wordless, because the
                        // badges it shares the row with need the width for labels.
                        if (item.isClone) {
                            MorpheCardChip(text = "", icon = MorpheIcons.ContentCopy)
                        }
                        Text(
                            modifier = Modifier.weight(1f),
                            text = cardSubtitle(item, version),
                            fontSize = 11.sp,
                            fontFamily = font,
                            fontWeight = FontWeight.Normal,
                            color = ink.subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        statusBadge(status)?.let { (label, icon) ->
                            MorpheCardChip(text = label, icon = icon)
                        }
                        // Newer patches and a newer supported app version are both
                        // answered by rebuilding, so one badge stands for either
                        // rather than two stacking into a row that already carries
                        // the patched version
                        AnimatedVisibility(
                            visible = item.showsRebuildBadge,
                            enter = expandHorizontally(tween(180)) + fadeIn(tween(180)),
                            exit = shrinkHorizontally(tween(140)) + fadeOut(tween(100)),
                        ) {
                            MorpheCardChip(
                                text = rebuildBadgeLabel(item),
                                icon = MorpheIcons.ArrowUpward,
                            )
                        }
                    }
                }
                // Desktop's answer to Manager's long-press: revealed on hover
                // rather than hidden behind a gesture the mouse cannot perform
                AnimatedVisibility(
                    visible = isHovered,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(120)),
                ) {
                    HideToggle(isHidden = item.isHidden, ink = ink, onClick = onToggleHidden)
                }
            }

            // Only an app with no build yet has versions to go and fetch
            if (item.supportedApp != null && !item.isTracked) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
                ) {
                    SupportedVersionsBody(
                        app = item.supportedApp,
                        patchSourceNames = patchSourceNames,
                    )
                }
            }
        }
    }
}

/**
 * The card's app mark. A placeholder derived from the app's own name and colors
 * today; the single place a real APK-derived icon will be drawn once there is
 * one, so adding icons later changes this composable and nothing around it.
 */
@Composable
private fun HomeAppAvatar(item: HomeAppItem, ink: AppCardInk) {
    val corners = LocalMorpheCorners.current
    val font = LocalMorpheFont.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(corners.small))
            .border(1.dp, ink.outline, RoundedCornerShape(corners.small))
            .background(ink.chipContent.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = item.displayName.firstOrNull()?.uppercase() ?: "?",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = font,
            color = ink.title,
        )
    }
}

@Composable
private fun HideToggle(isHidden: Boolean, ink: AppCardInk, onClick: () -> Unit) {
    val label = stringResource(if (isHidden) Res.string.home_card_unhide else Res.string.home_card_hide)
    MorpheTooltip(text = label) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(LocalMorpheCorners.current.small))
                .background(ink.chipContent.copy(alpha = 0.10f))
                .handCursor()
                .clickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isHidden) MorpheIcons.Visibility else MorpheIcons.VisibilityOff,
                contentDescription = null,
                tint = ink.chipContent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * The line under the app's name: the version the card is about, plus what the
 * card is when there is no build of it to describe.
 */
@Composable
private fun cardSubtitle(item: HomeAppItem, version: String): String {
    if (item.isTracked) return version
    val notPatched = stringResource(Res.string.home_card_not_patched_yet)
    val onDevice = stringResource(Res.string.home_card_on_device)
    return buildString {
        if (version.isNotEmpty()) append(version).append(" • ")
        append(notPatched)
        if (item.isOnDevice) append(" • ").append(onDevice)
    }
}

/** Label and icon of the one state badge a card carries, or null when it carries none. */
@Composable
private fun statusBadge(status: HomeAppStatus): Pair<String, ImageVector>? = when (status) {
    HomeAppStatus.NONE -> null
    HomeAppStatus.PENDING ->
        stringResource(Res.string.home_badge_pending) to MorpheIcons.Refresh
    HomeAppStatus.ARTIFACT_MISSING ->
        stringResource(Res.string.home_app_row_apk_missing_badge) to MorpheIcons.Delete
    HomeAppStatus.ARTIFACT_MODIFIED ->
        stringResource(Res.string.home_app_row_modified_badge) to MorpheIcons.Warning
    HomeAppStatus.REPLACED ->
        stringResource(Res.string.home_badge_replaced) to MorpheIcons.AutoAwesome
    HomeAppStatus.UNVERIFIED ->
        stringResource(Res.string.home_badge_unverified) to MorpheIcons.Info
    HomeAppStatus.UNINSTALLED ->
        stringResource(Res.string.home_badge_uninstalled) to MorpheIcons.Delete
}

/**
 * What the rebuild badge prints: the version a rebuild would land on when it is
 * short enough to leave the row its width, and the plain word otherwise. The
 * dialog can print both versions in full, so nothing is lost by shortening here.
 */
@Composable
private fun rebuildBadgeLabel(item: HomeAppItem): String {
    val update = stringResource(Res.string.home_badge_update)
    val supported = item.versionStatus
        ?.takeIf { item.showsVersionBadge && it.supportedVersion.length <= MAX_BADGE_VERSION_LENGTH }
        ?.supportedVersion
    return supported?.withVersionPrefix() ?: update
}

/**
 * What the card reads out. The states the badges stand for are described whether
 * their badges had room to show or not, so nothing important is visual-only.
 */
@Composable
private fun homeAppCardDescription(item: HomeAppItem, status: HomeAppStatus): String {
    val clone = stringResource(Res.string.home_badge_clone)
    val versionLabel = stringResource(Res.string.home_card_semantics_version, item.version)
    val supportedLabel = item.versionStatus
        ?.takeIf { item.showsVersionBadge }
        ?.let { stringResource(Res.string.home_card_semantics_supported_version, it.supportedVersion) }
    val updateLabel = stringResource(Res.string.home_card_semantics_update_available)
    val patched = stringResource(Res.string.home_your_apps_status_patched)
    val notPatched = stringResource(Res.string.home_card_not_patched_yet)
    val statusLabel = statusBadge(status)?.first

    return buildString {
        append(item.displayName)
        if (item.isClone) append(", ").append(clone)
        if (item.version.isNotEmpty()) append(", ").append(versionLabel)
        append(", ").append(
            when {
                !item.isTracked -> notPatched
                statusLabel != null -> statusLabel
                else -> patched
            }
        )
        if (item.showsUpdateBadge) append(", ").append(updateLabel)
        supportedLabel?.let { append(", ").append(it) }
    }
}

/**
 * Versions an app with no build yet can be patched at, with the sources that
 * bring it. Kept off the patched cards: a build already made is described by the
 * version it was made at, which the compact row above already carries.
 */
@Composable
private fun SupportedVersionsBody(
    app: SupportedApp,
    patchSourceNames: List<String>,
) {
    val font = LocalMorpheFont.current
    val ink = LocalAppCardInk.current
    val uriHandler = LocalUriHandler.current
    val maxPills = 16
    val otherStable = remember(app) { app.supportedVersions.filter { it != app.recommendedVersion } }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VersionCardChip(
                channelLabel = stringResource(Res.string.version_label_latest_stable),
                version = app.recommendedVersion,
                downloadUrl = app.apkDownloadUrl,
                nullLabel = stringResource(Res.string.home_app_row_any),
            )
            VersionCardChip(
                channelLabel = stringResource(Res.string.home_app_row_latest_experimental),
                version = app.experimentalVersions.firstOrNull(),
                downloadUrl = app.experimentalDownloadUrl,
                nullLabel = stringResource(Res.string.home_app_row_na),
            )
        }

        if (patchSourceNames.isNotEmpty()) {
            CardSection(stringResource(Res.string.home_app_row_section_patches_from), ink.chipContent, font) {
                patchSourceNames.forEach { MorpheCardChip(text = it) }
            }
        }

        if (otherStable.isNotEmpty()) {
            CardSection(stringResource(Res.string.version_label_stable), ink.chipContent, font) {
                otherStable.take(maxPills).forEach { version ->
                    // A pure function of package + version, so it is computed per
                    // pill rather than pre-stored on the model
                    val url = remember(app.packageName, version) {
                        SupportedApp.getDownloadUrl(app.packageName, version)
                    }
                    MorpheCardChip(
                        text = version,
                        icon = if (url != null) MorpheIcons.OpenInNew else null,
                        onClick = url?.let { { uriHandler.openUri(it) } },
                    )
                }
                OverflowCount(otherStable.size - maxPills, font)
            }
        }

        if (app.experimentalVersions.isNotEmpty()) {
            CardSection(stringResource(Res.string.version_label_experimental), ink.chipContent, font) {
                app.experimentalVersions.take(maxPills).forEach { version ->
                    val url = remember(app.packageName, version) {
                        SupportedApp.getDownloadUrl(app.packageName, version)
                    }
                    MorpheCardChip(
                        text = version,
                        icon = if (url != null) MorpheIcons.OpenInNew else null,
                        onClick = url?.let { { uriHandler.openUri(it) } },
                    )
                }
                OverflowCount(app.experimentalVersions.size - maxPills, font)
            }
        }
    }
}

@Composable
private fun CardSection(
    title: String,
    titleColor: Color,
    font: FontFamily,
    content: @Composable FlowRowScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = font,
            color = titleColor,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
private fun OverflowCount(remaining: Int, font: FontFamily) {
    if (remaining <= 0) return
    Text(
        text = "+$remaining",
        fontSize = 11.sp,
        fontFamily = font,
        color = LocalAppCardInk.current.subtitle,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun VersionCardChip(
    channelLabel: String,
    version: String?,
    downloadUrl: String?,
    nullLabel: String,
) {
    val uriHandler = LocalUriHandler.current
    val shown = version?.withVersionPrefix() ?: nullLabel
    MorpheCardChip(
        text = "$channelLabel · $shown",
        icon = if (downloadUrl != null) MorpheIcons.OpenInNew else null,
        onClick = downloadUrl?.let {
            { openUrlAndFollowRedirects(it) { resolved -> uriHandler.openUri(resolved) } }
        },
    )
}
