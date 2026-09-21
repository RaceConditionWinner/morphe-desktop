/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.screens.patches

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.morphe.gui.data.repository.CopySelectionCandidate
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * "Copy selection from another app/source" picker — Desktop-native UI for
 * [app.morphe.gui.data.repository.loadCopySelectionCandidates] / `resolveCopySelection`
 * (section 11). Mirrors Manager's `CopySelectionFromBundleDialog` in purpose, not layout —
 * a simple ranked list rather than Manager's search-heavy sheet, since a Desktop install
 * generally has far fewer saved selections to page through than a phone with years of history.
 *
 * [candidates] is already sorted by [loadCopySelectionCandidates] (same source endpoint
 * first, then same package, then largest patch overlap), so this just renders the order
 * it's given.
 */
@Composable
fun CopySelectionDialog(
    candidates: List<CopySelectionCandidate>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPick: (CopySelectionCandidate) -> Unit,
) {
    val font = LocalMorpheFont.current
    val accents = LocalMorpheAccents.current
    val corners = LocalMorpheCorners.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val width = (maxWidth * 0.6f).coerceIn(320.dp, 460.dp)
            val height = (maxHeight * 0.7f).coerceAtMost(520.dp)

            Surface(
                shape = RoundedCornerShape(corners.large),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 20.dp,
                modifier = Modifier.width(width).height(height),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            MorpheIcons.ContentCopy,
                            contentDescription = null,
                            tint = accents.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(Res.string.copy_selection_title),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = font,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(
                                MorpheIcons.Close,
                                contentDescription = stringResource(Res.string.close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(Res.string.copy_selection_subtitle),
                        fontSize = 12.sp,
                        fontFamily = font,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = accents.primary)
                        }
                        candidates.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(Res.string.copy_selection_empty),
                                fontSize = 13.sp,
                                fontFamily = font,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(candidates, key = { it.sourceId + "/" + it.packageName }) { candidate ->
                                CandidateRow(candidate, font, accents.primary, corners.small) { onPick(candidate) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: CopySelectionCandidate,
    font: FontFamily,
    accent: androidx.compose.ui.graphics.Color,
    corner: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(corner))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                candidate.packageDisplayName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    MorpheIcons.Source,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    candidate.sourceName,
                    fontSize = 11.sp,
                    fontFamily = font,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (candidate.isSameSource) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(Res.string.copy_selection_same_source),
                        fontSize = 10.sp,
                        fontFamily = font,
                        color = accent,
                    )
                }
            }
        }
        Text(
            "${candidate.applicableCount}/${candidate.sourcePatchCount}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = font,
            color = if (candidate.applicableCount > 0) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
