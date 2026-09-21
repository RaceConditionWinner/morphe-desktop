/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components.color

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.gui.data.model.AppCardColorDefaults
import app.morphe.gui.data.model.AppCardColorMode
import app.morphe.gui.data.model.AppCardColorResolver
import app.morphe.gui.data.model.AppCardColorStop
import app.morphe.gui.ui.components.AppCard
import app.morphe.gui.ui.components.LocalAppCardInk
import app.morphe.gui.ui.components.MorpheAlertDialog
import app.morphe.gui.ui.components.MorpheChoiceChip
import app.morphe.gui.ui.components.handCursor
import app.morphe.gui.ui.icons.MorpheIcons
import app.morphe.gui.ui.theme.LocalAppCardColorResolver
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.util.toColorOrNull
import app.morphe.gui.util.toHexString
import app.morphe.morphe_desktop.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private const val MODE_COLUMNS = 2

private val MODE_OPTIONS = listOf(
    AppCardColorMode.DEFAULT to MorpheIcons.Apps,
    AppCardColorMode.ACCENT to MorpheIcons.ColorLens,
    AppCardColorMode.GRADIENT to MorpheIcons.Gradient,
    AppCardColorMode.SOLID to MorpheIcons.Circle,
)

/**
 * The universal app card color editor: one configuration, applied to every card.
 *
 * Everything edited here is draft state held by this composable. Nothing reaches the config file
 * until Save, so dragging across the picker does not churn the file, and Cancel genuinely leaves
 * the persisted configuration alone.
 *
 * @param onApply Called with the whole draft on Save: mode first, then the four stored color
 *   values. All four are handed over whatever the mode is, so switching modes and back does not
 *   discard what was picked in the other one.
 */
@Composable
fun AppCardColorDialog(
    mode: AppCardColorMode,
    accentColorHex: String,
    startColorHex: String,
    middleColorHex: String,
    endColorHex: String,
    solidColorHex: String,
    onApply: (AppCardColorMode, String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val font = LocalMorpheFont.current
    val accents = LocalMorpheAccents.current

    var editingStop by remember { mutableStateOf<AppCardColorStop?>(null) }

    val defaultGradientHex = remember {
        AppCardColorDefaults.defaultGradientColors.map { it.toHexString() }
    }
    val defaultSolidHex = remember(defaultGradientHex) {
        defaultGradientHex.getOrElse(1) { defaultGradientHex.first() }
    }

    var draftMode by remember(mode) { mutableStateOf(mode) }
    var draftStartColorHex by remember(startColorHex, defaultGradientHex) {
        mutableStateOf(startColorHex.ifBlank { defaultGradientHex[0] })
    }
    var draftMiddleColorHex by remember(middleColorHex, defaultGradientHex) {
        mutableStateOf(middleColorHex.ifBlank { defaultGradientHex[1] })
    }
    var draftEndColorHex by remember(endColorHex, defaultGradientHex) {
        mutableStateOf(endColorHex.ifBlank { defaultGradientHex[2] })
    }
    var draftSolidColorHex by remember(solidColorHex, defaultSolidHex) {
        mutableStateOf(solidColorHex.ifBlank { defaultSolidHex })
    }

    // Restores the whole configuration, not one field: the mode and all four stored colors go
    // back to what a fresh install has
    val resetDraft = {
        draftMode = AppCardColorMode.DEFAULT
        draftStartColorHex = defaultGradientHex[0]
        draftMiddleColorHex = defaultGradientHex[1]
        draftEndColorHex = defaultGradientHex[2]
        draftSolidColorHex = defaultSolidHex
    }

    // Bundle-bound stops have no single color of their own, so previews resolve them against the
    // default palette
    val previewBundleColor = AppCardColorDefaults.defaultGradientColors[0]
    val gradientColors = remember(draftStartColorHex, draftMiddleColorHex, draftEndColorHex) {
        AppCardColorDefaults.gradientColors(
            startHex = draftStartColorHex,
            middleHex = draftMiddleColorHex,
            endHex = draftEndColorHex,
            bundleColor = previewBundleColor,
        )
    }
    val solidColors = remember(draftSolidColorHex) {
        AppCardColorDefaults.solidColors(draftSolidColorHex, previewBundleColor)
    }

    // Null keeps the preview card on the default palette, matching how DEFAULT leaves every card
    // on the colors declared by its bundle
    val previewColors = when (draftMode) {
        AppCardColorMode.DEFAULT -> null
        AppCardColorMode.ACCENT -> AppCardColorDefaults.accentColors(
            accentColorHex.toColorOrNull() ?: accents.primary
        )
        AppCardColorMode.GRADIENT -> gradientColors
        AppCardColorMode.SOLID -> solidColors
    }

    fun commit() {
        onApply(
            draftMode,
            draftStartColorHex,
            draftMiddleColorHex,
            draftEndColorHex,
            draftSolidColorHex,
        )
        onDismiss()
    }

    MorpheAlertDialog(
        onDismiss = onDismiss,
        maxWidth = 720.dp,
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                // While a stop picker is open it owns these keys, and closing it is what
                // Escape should do first
                Key.Enter, Key.NumPadEnter -> if (editingStop == null) { commit(); true } else false
                Key.Escape -> if (editingStop == null) { onDismiss(); true } else false
                else -> false
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.app_card_dialog_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = font,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                MorpheChoiceChip(
                    text = stringResource(Res.string.settings_dialog_reset_button),
                    active = false,
                    font = font,
                    icon = MorpheIcons.Refresh,
                    onClick = resetDraft,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppCardColorPreview(colors = previewColors, font = font)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MODE_OPTIONS.chunked(MODE_COLUMNS).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                row.forEach { (optionMode, icon) ->
                                    ModeOptionCard(
                                        selected = draftMode == optionMode,
                                        onClick = { draftMode = optionMode },
                                        icon = icon,
                                        label = optionMode.label,
                                        font = font,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        Text(
                            text = draftMode.description,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = font,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // One crossfade, so the dialog height does not jump twice when the stop
                    // groups swap
                    AnimatedContent(
                        targetState = draftMode,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        modifier = Modifier.weight(1f),
                        label = "app_card_color_pickers",
                    ) { activeMode ->
                        when (activeMode) {
                            AppCardColorMode.GRADIENT -> Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AppCardColorItem(
                                    title = AppCardColorStop.START.title,
                                    stop = AppCardColorStop.START,
                                    color = gradientColors[0],
                                    followsBundle = AppCardColorDefaults.isBundleColor(draftStartColorHex),
                                    font = font,
                                    onClick = { editingStop = AppCardColorStop.START },
                                )
                                AppCardColorItem(
                                    title = AppCardColorStop.MIDDLE.title,
                                    stop = AppCardColorStop.MIDDLE,
                                    color = gradientColors[1],
                                    followsBundle = AppCardColorDefaults.isBundleColor(draftMiddleColorHex),
                                    font = font,
                                    onClick = { editingStop = AppCardColorStop.MIDDLE },
                                )
                                AppCardColorItem(
                                    title = AppCardColorStop.END.title,
                                    stop = AppCardColorStop.END,
                                    color = gradientColors[2],
                                    followsBundle = AppCardColorDefaults.isBundleColor(draftEndColorHex),
                                    font = font,
                                    onClick = { editingStop = AppCardColorStop.END },
                                )
                            }

                            AppCardColorMode.SOLID -> AppCardColorItem(
                                title = AppCardColorStop.SOLID.title,
                                stop = AppCardColorStop.SOLID,
                                color = solidColors[0],
                                followsBundle = AppCardColorDefaults.isBundleColor(draftSolidColorHex),
                                font = font,
                                onClick = { editingStop = AppCardColorStop.SOLID },
                            )

                            AppCardColorMode.DEFAULT, AppCardColorMode.ACCENT -> Spacer(Modifier)
                        }
                    }
                }
            }
        },
        dismissButton = {
            MorpheChoiceChip(stringResource(Res.string.cancel), active = false, font = font, onClick = onDismiss)
        },
        confirmButton = {
            MorpheChoiceChip(stringResource(Res.string.save), active = true, font = font, onClick = { commit() })
        },
    )

    editingStop?.let { stop ->
        val color = when (stop) {
            AppCardColorStop.START -> gradientColors[0]
            AppCardColorStop.MIDDLE -> gradientColors[1]
            AppCardColorStop.END -> gradientColors[2]
            AppCardColorStop.SOLID -> solidColors[0]
        }
        val storedHex = when (stop) {
            AppCardColorStop.START -> draftStartColorHex
            AppCardColorStop.MIDDLE -> draftMiddleColorHex
            AppCardColorStop.END -> draftEndColorHex
            AppCardColorStop.SOLID -> draftSolidColorHex
        }
        ColorPickerDialog(
            title = stop.title,
            currentColor = storedHex,
            toggle = ColorPickerToggle(
                label = stringResource(Res.string.app_card_dialog_follow_app),
                description = stringResource(Res.string.app_card_dialog_follow_app_desc),
                token = AppCardColorDefaults.BUNDLE_COLOR_TOKEN,
                previewColor = color,
                previewGradient = remember(stop) { AppCardColorDefaults.bundleStopPreview(stop) },
            ),
            onColorSelected = { selectedColor ->
                when (stop) {
                    AppCardColorStop.START -> draftStartColorHex = selectedColor
                    AppCardColorStop.MIDDLE -> draftMiddleColorHex = selectedColor
                    AppCardColorStop.END -> draftEndColorHex = selectedColor
                    AppCardColorStop.SOLID -> draftSolidColorHex = selectedColor
                }
                editingStop = null
            },
            onDismiss = { editingStop = null },
        )
    }
}

/**
 * A strip of [colors] at the size of a swatch, for standing in where one color will not do.
 */
@Composable
fun AppCardColorMiniPreview(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    width: Dp = 44.dp,
    height: Dp = 28.dp,
) {
    val shape = RoundedCornerShape(LocalMorpheCorners.current.small)
    val safeColors = remember(colors) {
        when {
            colors.isEmpty() -> listOf(Color.Transparent, Color.Transparent)
            colors.size == 1 -> listOf(colors.first(), colors.first())
            else -> colors
        }
    }

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(shape)
            .drawWithCache {
                val brush = Brush.linearGradient(
                    colors = safeColors,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
                onDrawBehind { drawRect(brush) }
            }
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape = shape,
            )
    )
}

/**
 * Renders a real app card so the preview matches the rest of the application exactly, down to the
 * glass fill, the border and the adaptive content colors. [colors] is provided the same way the
 * theme provides it at runtime; null falls back to the bundle palette, which is what
 * [AppCardColorMode.DEFAULT] leaves every card on.
 */
@Composable
private fun AppCardColorPreview(colors: List<Color>?, font: FontFamily) {
    val resolver = remember(colors) { colors?.let { fixed -> AppCardColorResolver { fixed } } }

    CompositionLocalProvider(LocalAppCardColorResolver provides resolver) {
        AppCard(
            modifier = Modifier.fillMaxWidth().height(76.dp),
            interactive = false,
        ) {
            // Reads the ink the card itself resolved, so the preview shows the contrast the real
            // cards will have rather than assuming white
            val ink = LocalAppCardInk.current
            val corners = LocalMorpheCorners.current

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(corners.small))
                        .border(1.dp, ink.outline, RoundedCornerShape(corners.small))
                        .background(ink.chipContent.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "M",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = font,
                        color = ink.title,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.card_fill_example_app_name),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = font,
                        color = ink.title,
                    )
                    Text(
                        text = "com.example.app",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = font,
                        color = ink.subtitle,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(corners.small))
                        .background(ink.chipContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.home_your_apps_status_patched),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = font,
                        color = ink.chipContent,
                    )
                }
            }
        }
    }
}

/**
 * One editable stop. A bundle-bound stop has no single color to swatch, so it previews as the hue
 * sweep the picker shows for it.
 */
@Composable
private fun AppCardColorItem(
    title: String,
    stop: AppCardColorStop,
    color: Color,
    followsBundle: Boolean,
    font: FontFamily,
    onClick: () -> Unit,
) {
    val corners = LocalMorpheCorners.current
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(corners.small)

    val previewColors = remember(stop, color, followsBundle) {
        if (followsBundle) {
            AppCardColorDefaults.bundleStopPreview(stop)
        } else {
            listOf(color, color)
        }
    }
    val subtitle = if (followsBundle) stringResource(Res.string.app_card_dialog_follows_app) else color.toHexString()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                1.dp,
                if (isHovered) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                },
                shape,
            )
            .hoverable(hover)
            .handCursor()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = subtitle
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppCardColorMiniPreview(colors = previewColors, width = 34.dp, height = 34.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeOptionCard(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    font: FontFamily,
    modifier: Modifier = Modifier,
) {
    val corners = LocalMorpheCorners.current
    val accents = LocalMorpheAccents.current
    val hover = remember { MutableInteractionSource() }
    val isHovered by hover.collectIsHoveredAsState()
    val shape = RoundedCornerShape(corners.small)
    val selectionDescription = stringResource(
        if (selected) Res.string.a11y_state_selected else Res.string.a11y_state_not_selected
    )
    val border = when {
        selected -> accents.primary
        isHovered -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .background(if (selected) accents.primary.copy(alpha = 0.10f) else Color.Transparent)
            .hoverable(hover)
            .handCursor()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                stateDescription = selectionDescription
            }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) accents.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = font,
            color = if (selected) accents.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
