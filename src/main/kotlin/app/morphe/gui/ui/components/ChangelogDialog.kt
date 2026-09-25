/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.morphe.gui.data.constants.AppConstants
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.util.ChangelogMarkdown
import app.morphe.gui.ui.theme.MorpheOutline
import app.morphe.gui.util.Logger
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Reads the app's own bundled CHANGELOG.md from the classpath (packaged into the JAR by
 * `processResources`, see build.gradle.kts) rather than the working directory, which is
 * arbitrary once Morphe is launched from outside the repo — a plain `File("CHANGELOG.md")`
 * silently found nothing outside a dev checkout.
 */
private fun readBundledChangelog(): String? = try {
    Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("CHANGELOG.md")
        ?.bufferedReader()
        ?.use { it.readText() }
} catch (t: Throwable) {
    Logger.error("Failed to read bundled CHANGELOG.md", t)
    null
}

@Composable
fun ChangelogDialog(
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val font = LocalMorpheFont.current
    val corners = LocalMorpheCorners.current
    val accents = LocalMorpheAccents.current

    var showAllReleases by remember { mutableStateOf(false) }

    val notAvailableMsg = stringResource(Res.string.changelog_not_available)
    val sections = remember { readBundledChangelog()?.let(ChangelogMarkdown::parse) }

    // The first version heading starts the "current" release; every section before it
    // (if any) plus everything from the second heading onward is older history.
    val firstVersionIndex = sections?.indexOfFirst { it.heading != null } ?: -1
    val currentRelease = when {
        sections == null -> notAvailableMsg
        firstVersionIndex == -1 -> ChangelogMarkdown.render(sections)
        else -> ChangelogMarkdown.render(sections.subList(0, firstVersionIndex + 1))
    }
    val olderReleases = if (sections != null && firstVersionIndex in sections.indices) {
        ChangelogMarkdown.render(sections.subList(firstVersionIndex + 1, sections.size))
    } else ""

    val changelogScroll = rememberScrollState()

    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val dialogWidth = with(density) { (windowSize.width * 0.72f).toDp() }
        .coerceIn(560.dp, 900.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        MorpheDialogSurface(
            modifier = Modifier.width(dialogWidth),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(Res.string.changelog_dialog_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(changelogScroll)
                        .padding(end = 12.dp)
                ) {
                    FormattedReleaseNotes(markdown = currentRelease)
                
                    if (olderReleases.isNotBlank() && !showAllReleases) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                            thickness = 1.dp
                        )
                        OutlinedButton(
                            onClick = { showAllReleases = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .handCursor(),
                            shape = RoundedCornerShape(corners.small),
                            border = MorpheOutline.neutral(),
                        ) {
                            Text(
                                text = stringResource(Res.string.changelog_show_older_button),
                                fontFamily = font,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else if (showAllReleases) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FormattedReleaseNotes(markdown = olderReleases)
                    }
                }
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbar(
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        adapter = rememberScrollbarAdapter(changelogScroll),
                        style = morpheScrollbarStyle(),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    modifier = Modifier.handCursor(),
                    onClick = {
                        val version = AppConstants.APP_VERSION.removePrefix("v")
                        uriHandler.openUri("https://github.com/MorpheApp/morphe-desktop/releases/tag/v$version")
                    },
                    shape = RoundedCornerShape(corners.small),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.changelog_view_on_github_button),
                        fontFamily = font,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                TextButton(
                    modifier = Modifier.handCursor(),
                    onClick = onDismiss,
                    shape = RoundedCornerShape(corners.small),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.close),
                        fontFamily = font,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
