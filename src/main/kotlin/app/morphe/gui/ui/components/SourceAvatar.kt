/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.gui.data.model.PatchSource
import app.morphe.gui.data.model.PatchSourceType
import app.morphe.gui.data.repository.AvatarRepository
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.theme.LocalThemeState
import app.morphe.gui.ui.theme.ThemePreference
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import org.jetbrains.compose.resources.stringResource

/**
 * A patch source's visual identity: the built-in Morphe wordmark for [PatchSourceType.DEFAULT],
 * a deterministic file glyph for [PatchSourceType.LOCAL] (there is no "owner" to fetch an
 * avatar for), and a real owner avatar — with an immediate, deterministic initials fallback —
 * for [PatchSourceType.GITHUB]/[PatchSourceType.GITLAB].
 *
 * Always renders something instantly and at a fixed [size], so a source card never waits on
 * network before it can lay out, and swapping in a loaded avatar never shifts anything else
 * on screen (see [RemoteSourceAvatar]).
 */
@Composable
fun SourceAvatar(source: PatchSource, size: Dp, modifier: Modifier = Modifier) {
    when (source.type) {
        PatchSourceType.DEFAULT -> DefaultSourceAvatar(size, modifier)
        PatchSourceType.LOCAL -> LocalSourceAvatar(size, modifier)
        PatchSourceType.GITHUB, PatchSourceType.GITLAB -> RemoteSourceAvatar(source, size, modifier)
    }
}

@Composable
private fun AvatarShell(size: Dp, modifier: Modifier, background: Color, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun DefaultSourceAvatar(size: Dp, modifier: Modifier) {
    val themeState = LocalThemeState.current
    val isDark = when (themeState.current) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        else -> themeState.current.isDark()
    }
    AvatarShell(size, modifier, MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)) {
        Image(
            painter = painterResource(if (isDark) Res.drawable.morphe_dark else Res.drawable.morphe_light),
            contentDescription = stringResource(Res.string.app_name),
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

@Composable
private fun LocalSourceAvatar(size: Dp, modifier: Modifier) {
    AvatarShell(size, modifier, MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)) {
        Icon(
            imageVector = MorpheIcons.Description,
            contentDescription = stringResource(Res.string.patch_source_dialog_local_file_label),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun RemoteSourceAvatar(source: PatchSource, size: Dp, modifier: Modifier) {
    val avatarRepository = koinInject<AvatarRepository>()
    // Keyed on id: switching which source this composable instance represents (e.g. a
    // reordered/recycled row) must drop a stale bitmap rather than flash the old owner's
    // face under the new name for a frame.
    var bitmap by remember(source.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(source.id, source.url) {
        bitmap = avatarRepository.load(source)
    }

    val (initials, tint) = remember(source.id) { initialsAndColor(source) }

    AvatarShell(size, modifier, tint.copy(alpha = 0.22f)) {
        // Crossfade keeps the circle's size fixed across both states, so the swap from
        // initials to the loaded image never shifts card layout.
        Crossfade(targetState = bitmap, label = "avatar") { loaded ->
            if (loaded != null) {
                Image(
                    painter = BitmapPainter(loaded),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Text(
                    text = initials,
                    color = tint,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.38f).sp,
                    modifier = Modifier.padding(1.dp),
                )
            }
        }
    }
}

/** Fixed, pleasant palette so a generated initials avatar looks intentional, not random. */
private val INITIALS_PALETTE = listOf(
    Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFB74D),
    Color(0xFFBA68C8), Color(0xFF4DB6AC), Color(0xFFF06292), Color(0xFF9575CD),
    Color(0xFFA1887F), Color(0xFF4FC3F7),
)

/**
 * Deterministic initials + accent color for a remote source, derived from its repo owner
 * (falling back to the source name), so the same source always renders the same fallback
 * across sessions — stable identity even when it's never successfully fetched an avatar.
 */
private fun initialsAndColor(source: PatchSource): Pair<String, Color> {
    val owner = source.url
        ?.removePrefix("https://")?.removePrefix("http://")
        ?.removePrefix("github.com/")?.removePrefix("gitlab.com/")
        ?.substringBefore('/')
        ?.takeIf { it.isNotBlank() }
    val label = owner ?: source.name
    val initials = label.trim().take(2).uppercase().ifBlank { "?" }
    val color = INITIALS_PALETTE[(source.id.hashCode() and Int.MAX_VALUE) % INITIALS_PALETTE.size]
    return initials to color
}
