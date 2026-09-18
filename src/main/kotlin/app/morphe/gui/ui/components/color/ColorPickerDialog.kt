/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components.color

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.gui.ui.components.MorpheAlertDialog
import app.morphe.gui.ui.components.MorpheChoiceChip
import app.morphe.gui.ui.components.settings.SettingToggleRow
import app.morphe.gui.ui.components.SlimTextField
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.ui.theme.THEME_PRESET_COLORS
import app.morphe.gui.util.requiresLightContent
import app.morphe.gui.util.toColorOrNull
import app.morphe.gui.util.toHexString
import app.morphe.gui.util.toHsv

/**
 * Switch offered above the picker controls for colors that can follow a value computed elsewhere.
 * While it is on the picker returns [token] instead of a hex value and the manual controls are
 * disabled, because there is nothing to pick.
 *
 * @param previewColor    Color the token currently resolves to, used to seed the manual controls
 *   when the switch is turned back off.
 * @param previewGradient Shown instead of [previewColor] when the token stands for a value that
 *   varies rather than a single color; needs at least two colors to render.
 */
data class ColorPickerToggle(
    val label: String,
    val description: String,
    val token: String,
    val previewColor: Color,
    val previewGradient: List<Color> = emptyList(),
)

/**
 * Color picker dialog for custom color selection.
 *
 * Works in hue, saturation and value rather than in red, green and blue: those are the axes a
 * color is actually chosen along, and two of them fit one panel. Hex stays as the way to carry a
 * color in and out, and as the way to type an exact one.
 *
 * Laid out in two columns throughout. The manager splits on orientation and reaches for its
 * side-by-side arrangement in landscape; a desktop window is wider than it is tall in every
 * useful case, so that is the arrangement, without the branch.
 *
 * @param presets Offered above the panel for reaching a common color in one click. Pass an empty
 *   list where a palette would only be noise.
 */
@Composable
fun ColorPickerDialog(
    title: String,
    currentColor: String,
    onColorSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    toggle: ColorPickerToggle? = null,
    presets: List<Color> = THEME_PRESET_COLORS,
) {
    val font = LocalMorpheFont.current
    val accents = LocalMorpheAccents.current
    val corners = LocalMorpheCorners.current

    val initialToggle = toggle?.takeIf {
        currentColor.trim().equals(it.token, ignoreCase = true)
    }

    var useToggle by remember(currentColor, toggle?.token) { mutableStateOf(initialToggle != null) }

    // A color following the toggle has no hex of its own, so the manual controls open on the
    // value it currently resolves to instead of on black
    val initial = remember(currentColor, toggle?.previewColor) {
        initialToggle?.previewColor ?: currentColor.toColorOrNull() ?: Color.Black
    }

    // Plain `remember`, not the manager's saveable state: it saves the half-chosen color across
    // an Android configuration change, and a desktop window has no equivalent to survive
    var hsv by remember { mutableStateOf(initial.toHsv().let { HsvColor(it.first, it.second, it.third) }) }
    var hexInput by remember { mutableStateOf(initial.toHexString()) }
    var isHexError by remember { mutableStateOf(false) }

    /** Moving on the panel or the strip is what the hex readout follows, never the other way. */
    fun moveTo(updated: HsvColor) {
        hsv = updated
        hexInput = updated.color.toHexString()
        isHexError = false
    }

    val activeToggle = toggle?.takeIf { useToggle }
    val enabled = activeToggle == null
    val previewColor = activeToggle?.previewColor ?: hsv.color
    val previewGradient = activeToggle?.previewGradient?.takeIf { it.size > 1 }

    fun commit() = onColorSelected(activeToggle?.token ?: hexInput)

    MorpheAlertDialog(
        onDismiss = onDismiss,
        maxWidth = 640.dp,
        // Enter saves and Escape cancels, the two keys a desktop dialog is expected to answer.
        // Previewed rather than handled, so the hex field does not swallow Enter first
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.Enter, Key.NumPadEnter -> { commit(); true }
                Key.Escape -> { onDismiss(); true }
                else -> false
            }
        },
        title = {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // The panel needs the drag itself and so can never give way to a scroll. Side by
                // side it keeps its height, and everything that can scroll sits in the other column
                AnimatedVisibility(
                    visible = enabled,
                    modifier = Modifier.weight(1f),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    SaturationValuePanel(
                        hsv = hsv,
                        onChange = { saturation, value ->
                            moveTo(hsv.copy(saturation = saturation, value = value))
                        },
                        height = 190.dp,
                        contentDescription = "Shade",
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ColorPreview(
                        color = previewColor,
                        gradient = previewGradient,
                        label = activeToggle?.label ?: hexInput,
                        height = 48.dp,
                        font = font,
                        cornerRadius = corners.small,
                    )

                    if (toggle != null) {
                        SettingToggleRow(
                            label = toggle.label,
                            description = toggle.description,
                            checked = useToggle,
                            onCheckedChange = { useToggle = it },
                            accentColor = accents.primary,
                            font = font,
                        )
                    }

                    // The manual controls have nothing to offer while the color follows the
                    // toggle, and leaving them greyed out in place would only hold the room
                    AnimatedVisibility(
                        visible = enabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ColorPresetRow(
                                colors = presets,
                                selected = hsv.color,
                                onSelect = { preset ->
                                    val (hue, saturation, value) = preset.toHsv()
                                    moveTo(HsvColor(hue, saturation, value))
                                },
                            )
                            HueSlider(
                                hue = hsv.hue,
                                onChange = { moveTo(hsv.copy(hue = it)) },
                                contentDescription = "Hue",
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SlimTextField(
                            value = hexInput,
                            onValueChange = { input ->
                                hexInput = input
                                val parsed = input.toColorOrNull()
                                if (parsed != null) {
                                    // A grey types as hue 0, which would swing the panel back to
                                    // red, so a color that carries no hue of its own keeps the
                                    // one already on screen
                                    val (hue, saturation, value) = parsed.toHsv()
                                    hsv = HsvColor(if (saturation == 0f) hsv.hue else hue, saturation, value)
                                    isHexError = false
                                } else {
                                    isHexError = input.isNotEmpty()
                                }
                            },
                            placeholder = "#RRGGBB",
                            font = font,
                            accents = accents,
                            corners = corners,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // A half-typed hex is the normal state of this field, so an invalid one
                        // is said rather than acted on: the color on screen is the last good value
                        if (isHexError && enabled) {
                            Text(
                                text = "Not a color. Use #RRGGBB or #AARRGGBB.",
                                fontSize = 10.sp,
                                fontFamily = font,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            MorpheChoiceChip("Cancel", active = false, font = font, onClick = onDismiss)
        },
        confirmButton = {
            MorpheChoiceChip("Save", active = true, font = font, onClick = { commit() })
        },
    )
}

/**
 * The color as it will be used, captioned with what it is. A gradient carries its own meaning and
 * is labeled by the switch under it, so the caption only makes sense for a single picked color.
 */
@Composable
private fun ColorPreview(
    color: Color,
    gradient: List<Color>?,
    label: String,
    height: Dp,
    font: androidx.compose.ui.text.font.FontFamily,
    cornerRadius: Dp,
) {
    val animated by animateColorAsState(color, label = "color_preview")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (gradient != null) {
                    Modifier.background(Brush.horizontalGradient(gradient))
                } else {
                    Modifier.background(animated)
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (gradient == null) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = font,
                color = if (animated.requiresLightContent()) Color.White else Color.Black,
            )
        }
    }
}
