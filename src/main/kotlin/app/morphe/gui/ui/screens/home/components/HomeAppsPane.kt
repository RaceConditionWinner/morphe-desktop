/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.home.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.gui.ui.components.MorpheBanner
import app.morphe.gui.ui.components.MorpheBannerAction
import app.morphe.gui.ui.components.MorpheBannerText
import app.morphe.gui.ui.components.MorpheDropdown
import app.morphe.gui.ui.components.MorpheDropdownItem
import app.morphe.gui.ui.components.handCursor
import app.morphe.gui.ui.components.morpheScrollbarStyle
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.screens.home.HomeAppItem
import app.morphe.gui.ui.screens.home.HomeAppSortMode
import app.morphe.gui.ui.screens.home.comparator
import app.morphe.gui.ui.screens.home.sortKeys
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheDimens
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.ui.theme.MorpheAccentColors
import app.morphe.gui.ui.theme.MorpheCornerStyle
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Which list the home pane is showing: every supported app, or only the patched ones. */
enum class AppListFilter { ALL, YOURS }

/**
 * The home screen's app list.
 *
 * Every row is one [HomeAppCard] over one [HomeAppItem], whether or not Morphe
 * has a build of that app: the tabs narrow which items are listed, they do not
 * select a different kind of card. Sorting, searching and hiding all read the
 * same items, so a card cannot appear in one order here and another there.
 */
@Composable
internal fun HomeAppsPane(
    apps: List<HomeAppItem>,
    filter: AppListFilter,
    onFilterChange: (AppListFilter) -> Unit,
    sortMode: HomeAppSortMode,
    onSortModeChange: (HomeAppSortMode) -> Unit,
    showHidden: Boolean,
    onShowHiddenChange: (Boolean) -> Unit,
    sourceNamesByPackage: Map<String, List<String>>,
    isLoading: Boolean,
    loadError: String?,
    onRetry: () -> Unit,
    onManageSources: () -> Unit,
    onOpenInfo: (HomeAppItem) -> Unit,
    onToggleHidden: (HomeAppItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val corners = LocalMorpheCorners.current
    val font = LocalMorpheFont.current
    val accents = LocalMorpheAccents.current

    var searchQuery by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }

    val listed = remember(apps, showHidden) {
        if (showHidden) apps else apps.filterNot { it.isHidden }
    }
    val allCount = listed.size
    val patchedCount = remember(listed) { listed.count { it.isTracked } }
    val hiddenCount = remember(apps) { apps.count { it.isHidden } }

    val inTab = remember(listed, filter) {
        if (filter == AppListFilter.YOURS) listed.filter { it.isTracked } else listed
    }
    val filtered = remember(inTab, searchQuery, sortMode) {
        inTab
            .filter { item ->
                searchQuery.isBlank() ||
                    item.displayName.contains(searchQuery, ignoreCase = true) ||
                    item.packageName.contains(searchQuery, ignoreCase = true)
            }
            .sortedWith(compareBy(sortMode.comparator()) { it.sortKeys() })
    }
    val activeCount = if (filter == AppListFilter.YOURS) patchedCount else allCount

    // Collapse a row that filtered out from under its own expansion
    LaunchedEffect(filtered) {
        if (expandedId != null && filtered.none { it.id == expandedId }) expandedId = null
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val paneMaxHeight = maxHeight
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight().align(Alignment.TopCenter),
        ) {
            // Rebuildable apps are the reason to visit the patched tab, so the
            // count that leads there is taken from the same badge the cards carry
            val rebuildCount = remember(listed) { listed.count { it.showsRebuildBadge } }
            if (filter == AppListFilter.ALL && rebuildCount > 0) {
                PatchedUpdatesBanner(rebuildCount) { onFilterChange(AppListFilter.YOURS) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppListFilterChips(
                    filter = filter,
                    onSelect = onFilterChange,
                    allCount = allCount,
                    yourCount = patchedCount,
                    modifier = Modifier.weight(1f),
                )
                MorpheDropdown(
                    label = sortMode.label,
                    items = HomeAppSortMode.entries.map { mode ->
                        MorpheDropdownItem(mode.label) { onSortModeChange(mode) }
                    },
                    modifier = Modifier.width(170.dp),
                )
            }

            if (hiddenCount > 0) {
                HiddenAppsToggle(
                    hiddenCount = hiddenCount,
                    showHidden = showHidden,
                    font = font,
                    accent = accents.primary,
                    onToggle = { onShowHiddenChange(!showHidden) },
                )
            }

            if (activeCount > 4) {
                Box(modifier = Modifier.fillMaxWidth().padding(end = 12.dp)) {
                    SlimSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        font = font,
                        corners = corners,
                        accents = accents,
                        maxWidth = Dp.Unspecified,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            when {
                // Only the full list waits on patches. The patched tab is drawn from
                // the history, which is on disk and does not need a source to load.
                isLoading && filter == AppListFilter.ALL && apps.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        repeat(4) { index ->
                            SkeletonAppRow(corners = corners, staggerOffsetMs = index * 120)
                        }
                    }
                }

                loadError != null && filter == AppListFilter.ALL && apps.isEmpty() ->
                    LoadFailed(loadError, font, corners, onRetry, onManageSources)

                filtered.isEmpty() -> EmptyHint(
                    filter = filter,
                    searchQuery = searchQuery,
                    hasAnyPatched = patchedCount > 0,
                    font = font,
                )

                else -> {
                    val listState = rememberLazyListState()
                    val headerAllowance = if (activeCount > 4) 68.dp else 22.dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = (paneMaxHeight - headerAllowance).coerceAtLeast(120.dp)),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(items = filtered, key = { it.id }) { item ->
                                HomeAppCard(
                                    item = item,
                                    expanded = expandedId == item.id,
                                    patchSourceNames = sourceNamesByPackage[item.packageName].orEmpty(),
                                    onClick = {
                                        // A build Morphe made has a dialog behind it.
                                        // An app it merely supports has versions to go
                                        // and fetch, which is what the row opens onto.
                                        if (item.isTracked) {
                                            onOpenInfo(item)
                                        } else {
                                            expandedId = if (expandedId == item.id) null else item.id
                                        }
                                    },
                                    onToggleHidden = { onToggleHidden(item) },
                                )
                            }
                        }
                        Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                            VerticalScrollbar(
                                modifier = Modifier.fillMaxHeight(),
                                adapter = rememberScrollbarAdapter(listState),
                                style = morpheScrollbarStyle(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Segmented filter at the top of the apps pane: ALL APPS · YOUR APPS. The "Your
 * apps" tab carries a count so the history is discoverable before it is selected.
 */
@Composable
private fun AppListFilterChips(
    filter: AppListFilter,
    onSelect: (AppListFilter) -> Unit,
    allCount: Int,
    yourCount: Int,
    modifier: Modifier = Modifier,
) {
    val font = LocalMorpheFont.current
    val accents = LocalMorpheAccents.current
    val corners = LocalMorpheCorners.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        FilterChip(
            label = stringResource(Res.string.home_filter_all_apps),
            count = allCount.takeIf { it > 0 },
            selected = filter == AppListFilter.ALL,
            accent = accents.primary,
            font = font,
            corner = corners.small,
            onClick = { onSelect(AppListFilter.ALL) },
        )
        FilterChip(
            label = stringResource(Res.string.home_filter_your_apps),
            count = yourCount.takeIf { it > 0 },
            selected = filter == AppListFilter.YOURS,
            accent = accents.primary,
            font = font,
            corner = corners.small,
            onClick = { onSelect(AppListFilter.YOURS) },
        )
    }
}

/**
 * On-open notice shown above the list when one or more patched apps can be
 * rebuilt. Jumps to the patched tab, where each is badged.
 */
@Composable
private fun PatchedUpdatesBanner(count: Int, onView: () -> Unit) {
    MorpheBanner(
        modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
        icon = MorpheIcons.Refresh,
    ) {
        MorpheBannerText(
            text = pluralStringResource(Res.plurals.home_banner_update_available, count, count),
            modifier = Modifier.weight(1f),
        )
        MorpheBannerAction(label = stringResource(Res.string.home_banner_view), onClick = onView)
    }
}

@Composable
private fun FilterChip(
    label: String,
    count: Int?,
    selected: Boolean,
    accent: Color,
    font: FontFamily,
    corner: Dp,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    val border by animateColorAsState(
        when {
            selected -> accent.copy(alpha = 0.6f)
            isHovered -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        },
        tween(150), label = "chip",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(corner))
            .border(1.dp, border, RoundedCornerShape(corner))
            .background(
                if (selected) accent.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .hoverable(hover)
            .handCursor()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = font,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = font,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The way back to cards the user hid. Only offered once there are some. */
@Composable
private fun HiddenAppsToggle(
    hiddenCount: Int,
    showHidden: Boolean,
    font: FontFamily,
    accent: Color,
    onToggle: () -> Unit,
) {
    val label = if (showHidden) {
        stringResource(Res.string.home_hidden_hide)
    } else {
        stringResource(Res.string.home_hidden_show, hiddenCount)
    }
    Row(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(LocalMorpheCorners.current.small))
            .handCursor()
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (showHidden) MorpheIcons.VisibilityOff else MorpheIcons.Visibility,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(12.dp),
        )
        Text(text = label, fontSize = 10.sp, fontFamily = font, color = accent)
    }
}

@Composable
private fun LoadFailed(
    loadError: String,
    font: FontFamily,
    corners: MorpheCornerStyle,
    onRetry: () -> Unit,
    onManageSources: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
        Text(
            text = stringResource(Res.string.home_list_load_failed),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = font,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = loadError,
            fontSize = 11.sp,
            fontFamily = font,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(corners.small)) {
                Text(stringResource(Res.string.retry), fontFamily = font, fontSize = 11.sp)
            }
            // Always offer a way to the source manager here: when a bundle is broken
            // (e.g. it needs a newer patcher), fixing it means removing or
            // re-pointing that source, so it must be reachable from the error.
            OutlinedButton(onClick = onManageSources, shape = RoundedCornerShape(corners.small)) {
                Text(
                    stringResource(Res.string.home_header_manage_sources_button),
                    fontFamily = font,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(
    filter: AppListFilter,
    searchQuery: String,
    hasAnyPatched: Boolean,
    font: FontFamily,
) {
    val (title, subtitle) = when {
        searchQuery.isNotBlank() -> stringResource(Res.string.no_matches) to
            stringResource(Res.string.home_your_apps_no_matches_subtitle, searchQuery)
        filter == AppListFilter.YOURS && !hasAnyPatched ->
            stringResource(Res.string.home_your_apps_empty_title) to
                stringResource(Res.string.home_your_apps_empty_subtitle)
        else -> stringResource(Res.string.home_list_no_supported_apps) to ""
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = font,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Slim, elongated search field. Built on BasicTextField so it can drop below the
 * 56dp minimum height Material 3's OutlinedTextField enforces internally.
 */
@Composable
internal fun SlimSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    font: FontFamily,
    corners: MorpheCornerStyle,
    accents: MorpheAccentColors,
    maxWidth: Dp = 340.dp,
) {
    val dimens = LocalMorpheDimens.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor by animateColorAsState(
        if (isFocused) MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(150),
        label = "slimSearchBorder",
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        interactionSource = interactionSource,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontFamily = font,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(accents.primary),
        modifier = Modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .height(dimens.controlHeight)
            .clip(RoundedCornerShape(corners.small))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, borderColor, RoundedCornerShape(corners.small)),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MorpheIcons.Search,
                    contentDescription = null,
                    tint = muted.copy(alpha = 0.55f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.filter_apps_hint),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontFamily = font,
                            color = muted.copy(alpha = 0.4f),
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(corners.small))
                            .clickable { onValueChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            MorpheIcons.Clear,
                            contentDescription = stringResource(Res.string.clear),
                            tint = muted.copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Loading skeleton. Carries the geometry of a real [HomeAppCard] so the list does
 * not re-lay-out the moment the apps resolve.
 */
@Composable
internal fun SkeletonAppRow(corners: MorpheCornerStyle, staggerOffsetMs: Int) {
    val infinite = rememberInfiniteTransition(label = "skeletonPulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, delayMillis = staggerOffsetMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    val baseColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val cardBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corners.medium))
            .background(cardBg)
            .border(1.dp, outline, RoundedCornerShape(corners.medium))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(corners.small))
                .background(baseColor),
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .height(12.dp)
                    .width(140.dp)
                    .clip(RoundedCornerShape(corners.small))
                    .background(baseColor),
            )
            Box(
                modifier = Modifier
                    .height(10.dp)
                    .width(90.dp)
                    .clip(RoundedCornerShape(corners.small))
                    .background(baseColor.copy(alpha = alpha * 0.6f)),
            )
        }
    }
}
