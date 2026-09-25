/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.gui.data.repository.ChangelogLoad
import app.morphe.gui.data.repository.ChangelogRepository
import app.morphe.gui.data.repository.ChangelogRequest
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.util.ChangelogEntry
import app.morphe.gui.util.withVersionPrefix
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * One request's changelog, as it stands for whoever asked for it: nothing fetched yet, a fetch
 * in flight, or the outcome of the last one. A `refreshKey` change means "fetch again" — used
 * for both a first request (null → some request) and an explicit retry (the same request, a
 * bumped key).
 */
private sealed interface SectionState {
    data object Idle : SectionState
    data object Loading : SectionState
    data class Done(val load: ChangelogLoad) : SectionState
}

/**
 * Loads [request] through [repository] exactly once per (request, refreshKey) pair. Passing
 * `null` for [request] means "not asked for yet" (the lazy, collapsed case) and reports [SectionState.Idle]
 * without touching the repository — a source detail row the user never expands must never fetch.
 */
@Composable
private fun rememberChangelogState(
    repository: ChangelogRepository,
    request: ChangelogRequest?,
    refreshKey: Int,
): SectionState {
    var state by remember(request, refreshKey) {
        mutableStateOf<SectionState>(if (request == null) SectionState.Idle else SectionState.Loading)
    }
    LaunchedEffect(request, refreshKey) {
        if (request != null) {
            state = SectionState.Loading
            state = SectionState.Done(repository.load(request))
        }
    }
    return state
}

/**
 * Renders one request's changelog inline: a loading row, a retry row on failure (with nothing
 * cached), a stale banner over cached content on a failed refresh, an empty message when the
 * fetch succeeded but there is genuinely nothing to show, or the entries themselves via
 * [FormattedReleaseNotes] — one version heading per entry when [showVersionHeadings], for a
 * multi-release view; omitted for a single-source expansion that already names its version
 * elsewhere.
 *
 * [request] is nullable so a caller can defer fetching until the user expands a row; passing
 * `null` renders nothing.
 */
@Composable
fun ChangelogSection(
    repository: ChangelogRepository,
    request: ChangelogRequest?,
    showVersionHeadings: Boolean = false,
    /** Overrides the "nothing to show" message — e.g. "no changes specific to this app". */
    emptyMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val font = LocalMorpheFont.current
    var refreshKey by remember(request) { mutableStateOf(0) }
    val state = rememberChangelogState(repository, request, refreshKey)
    val empty = emptyMessage ?: stringResource(Res.string.changelog_empty)

    Column(modifier = modifier) {
        when (state) {
            SectionState.Idle -> Unit
            SectionState.Loading -> LoadingRow(font)
            is SectionState.Done -> when (val load = state.load) {
                is ChangelogLoad.Unavailable -> EmptyRow(empty, font)
                is ChangelogLoad.Failed -> RetryRow(font) { refreshKey++ }
                is ChangelogLoad.Entries -> {
                    if (load.stale) StaleBanner(font)
                    if (load.entries.isEmpty()) {
                        EmptyRow(empty, font)
                    } else {
                        load.entries.forEachIndexed { index, entry ->
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            if (showVersionHeadings) VersionHeading(entry, font)
                            FormattedReleaseNotes(markdown = entry.content)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A "show older releases" affordance beneath [ChangelogSection]: collapsed until tapped, then
 * fetches once via [ChangelogRepository.loadOlder] and stays expanded. [shownVersions] is every
 * version already visible above it, so the older list never repeats them.
 */
@Composable
fun OlderChangelogExpander(
    repository: ChangelogRepository,
    request: ChangelogRequest?,
    shownVersions: Set<String>,
    modifier: Modifier = Modifier,
) {
    val font = LocalMorpheFont.current
    var expanded by remember(request) { mutableStateOf(false) }
    var refreshKey by remember(request) { mutableStateOf(0) }

    var state by remember(request, expanded, refreshKey) {
        mutableStateOf<SectionState>(SectionState.Idle)
    }
    LaunchedEffect(request, expanded, refreshKey) {
        if (expanded && request != null) {
            state = SectionState.Loading
            state = SectionState.Done(repository.loadOlder(request, shownVersions))
        }
    }

    Column(modifier = modifier) {
        if (!expanded) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
            )
            Text(
                text = stringResource(Res.string.changelog_show_older_button),
                modifier = Modifier.handCursor().clickable { expanded = true },
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = font,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            Spacer(Modifier.height(8.dp))
            when (val current = state) {
                SectionState.Idle -> Unit
                SectionState.Loading -> LoadingRow(font, stringResource(Res.string.changelog_loading_older))
                is SectionState.Done -> when (val load = current.load) {
                    is ChangelogLoad.Unavailable -> EmptyRow(stringResource(Res.string.changelog_older_empty), font)
                    is ChangelogLoad.Failed -> RetryRow(font, stringResource(Res.string.changelog_older_failed)) { refreshKey++ }
                    is ChangelogLoad.Entries -> {
                        if (load.stale) StaleBanner(font)
                        if (load.entries.isEmpty()) {
                            EmptyRow(stringResource(Res.string.changelog_older_empty), font)
                        } else {
                            load.entries.forEachIndexed { index, entry ->
                                if (index > 0) Spacer(Modifier.height(8.dp))
                                VersionHeading(entry, font)
                                FormattedReleaseNotes(markdown = entry.content)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionHeading(entry: ChangelogEntry, font: FontFamily) {
    Text(
        text = entry.version.withVersionPrefix(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = font,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun LoadingRow(font: FontFamily, label: String = stringResource(Res.string.source_details_loading_changelog)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(label, fontSize = 11.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyRow(text: String, font: FontFamily) {
    Text(text, fontSize = 11.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StaleBanner(font: FontFamily) {
    Text(
        text = stringResource(Res.string.changelog_stale),
        fontSize = 10.sp,
        fontFamily = font,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun RetryRow(font: FontFamily, label: String = stringResource(Res.string.changelog_load_failed), onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.handCursor().clickable(onClick = onRetry),
    ) {
        Icon(MorpheIcons.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(label, fontSize = 11.sp, fontFamily = font, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(Res.string.retry),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = font,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
