/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components.color

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.morphe.gui.ui.components.handCursor
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.util.darken
import app.morphe.gui.util.toArgbInt
import app.morphe.gui.util.toHexString

/**
 * Swatch side. Smaller than the manager's touch target, a pointer needing far less room to hit
 * than a finger, and sized to sit on the same row as the accent swatches above it.
 */
private val SwatchSize = 28.dp

/**
 * One preset color. Selection is carried by the border rather than an overlay, so the swatch keeps
 * showing the color it stands for at full strength.
 */
@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(LocalMorpheCorners.current.small)
    val hex = color.toHexString()

    val borderWidth by animateDpAsState(if (selected) 3.dp else 1.dp, label = "swatch_border_width")
    val borderColor by animateColorAsState(
        if (selected) color.darken(0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        label = "swatch_border_color",
    )

    Box(
        modifier = modifier
            .size(SwatchSize)
            .clip(shape)
            .background(color.copy(alpha = if (enabled) 1f else 0.5f), shape)
            .border(borderWidth, borderColor, shape)
            .handCursor()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                contentDescription = hex
                stateDescription = if (selected) "Selected" else "Not selected"
            },
    )
}

/**
 * Single scrolling row of [colors], for the picker, where every row spent on presets is a row the
 * panel below does not get.
 *
 * The manager also offers a wrapping grid of the same swatches for its accent setting; desktop's
 * accent setting has its own swatch row already, so only the row the picker needs is ported.
 */
@Composable
fun ColorPresetRow(
    colors: List<Color>,
    selected: Color?,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val selectedArgb = selected?.toArgbInt()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        colors.forEach { preset ->
            ColorSwatch(
                color = preset,
                selected = preset.toArgbInt() == selectedArgb,
                onClick = { onSelect(preset) },
                enabled = enabled,
            )
        }
    }
}
