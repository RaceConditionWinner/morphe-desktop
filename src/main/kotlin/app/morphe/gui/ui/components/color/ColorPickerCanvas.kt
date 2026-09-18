/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morphe.gui.ui.components.handCursor
import app.morphe.gui.util.toHexString

/** Where the color sits, in the terms the two controls below are laid out in. */
@Immutable
data class HsvColor(val hue: Float, val saturation: Float, val value: Float) {
    val color: Color get() = Color.hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
}

private val MarkerRadius = 10.dp
private val MarkerStroke = 3.dp

/**
 * Exactly the handle's own diameter, so it fills the strip end to end. A ring is stroked centered
 * on its radius, which puts only half the width outside it.
 */
private val HueStripHeight = (MarkerRadius + MarkerStroke / 2) * 2

/** The hue wheel walked in even steps, which a sweep of stops approximates closely enough. */
private val HueStops = List(13) { Color.hsv(it * 30f, 1f, 1f) }

/** Arrow-key step, and the larger step the same key takes with shift held. */
private const val FINE_STEP = 0.02f
private const val COARSE_STEP = 0.10f
private const val HUE_FINE_STEP = 2f
private const val HUE_COARSE_STEP = 15f

/**
 * Saturation across, value down, over a background of the pure [hsv] hue. Both a click and a drag
 * report continuously, the handle being drawn from the value rather than held as state of its own.
 *
 * Desktop additions over the manager's panel, none of which change what a drag does: the control
 * takes focus, so the arrow keys walk it for anyone not using a mouse, and a focus ring says so.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SaturationValuePanel(
    hsv: HsvColor,
    onChange: (saturation: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    contentDescription: String? = null,
) {
    var size by remember { mutableStateOf(Size.Zero) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    fun report(position: Offset) {
        if (size == Size.Zero) return
        onChange(
            (position.x / size.width).coerceIn(0f, 1f),
            1f - (position.y / size.height).coerceIn(0f, 1f),
        )
    }

    fun nudge(dSaturation: Float, dValue: Float) = onChange(
        (hsv.saturation + dSaturation).coerceIn(0f, 1f),
        (hsv.value + dValue).coerceIn(0f, 1f),
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                stateDescription = hsv.color.toHexString()
            }
            .handCursor()
            .pointerInput(Unit) {
                detectTapGestures { report(it) }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { report(it) }) { change, _ ->
                    change.consume()
                    report(change.position)
                }
            }
            // The wheel is the one gesture a desktop pointer has that a finger does not, and
            // value is what it most naturally stands for: away from the user, brighter
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (delta != 0f) nudge(0f, -delta * FINE_STEP)
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = if (event.isShiftPressed) COARSE_STEP else FINE_STEP
                when (event.key) {
                    Key.DirectionLeft -> nudge(-step, 0f)
                    Key.DirectionRight -> nudge(step, 0f)
                    Key.DirectionUp -> nudge(0f, step)
                    Key.DirectionDown -> nudge(0f, -step)
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(interactionSource = interactionSource),
    ) {
        size = this.size

        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hsv.hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

        // Straight off the value, so the handle is under the cursor during a drag and already in
        // place when the dialog opens, rather than arriving from wherever it started
        drawMarker(
            center = Offset(hsv.saturation * size.width, (1f - hsv.value) * size.height),
            fill = hsv.color,
            radius = MarkerRadius.toPx(),
        )

        if (focused) drawFocusRing()
    }
}

/**
 * The hue wheel laid out flat. Saturation and value stay where they are, so the strip always shows
 * fully saturated colors and reads as a spectrum rather than as a slice of the current color.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HueSlider(
    hue: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = HueStripHeight,
    contentDescription: String? = null,
) {
    var width by remember { mutableFloatStateOf(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    fun report(x: Float) {
        if (width <= 0f) return
        onChange((x / width).coerceIn(0f, 1f) * 360f)
    }

    // Hue is a wheel, so a step off either end comes back round rather than sticking
    fun nudge(degrees: Float) = onChange((hue + degrees + 360f) % 360f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                progressBarRangeInfo = ProgressBarRangeInfo(hue, 0f..360f)
            }
            .handCursor()
            .pointerInput(Unit) {
                detectTapGestures { report(it.x) }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { report(it.x) }) { change, _ ->
                    change.consume()
                    report(change.position.x)
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (delta != 0f) nudge(delta * HUE_FINE_STEP)
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = if (event.isShiftPressed) HUE_COARSE_STEP else HUE_FINE_STEP
                when (event.key) {
                    Key.DirectionLeft -> nudge(-step)
                    Key.DirectionRight -> nudge(step)
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(interactionSource = interactionSource),
    ) {
        width = size.width

        // Rounded by the draw rather than by a clip, which would take the handle with it
        drawRoundRect(
            brush = Brush.horizontalGradient(HueStops),
            cornerRadius = CornerRadius(size.height / 2f),
        )

        drawMarker(
            center = Offset((hue / 360f) * size.width, size.height / 2f),
            fill = Color.hsv(hue, 1f, 1f),
            radius = MarkerRadius.toPx(),
        )

        if (focused) drawFocusRing(CornerRadius(size.height / 2f))
    }
}

/**
 * The handle both controls share: the picked color ringed in white over black, which stays visible
 * on any part of either gradient. Kept a full ring inside the bounds, since a clipped handle reads
 * as a rendering fault rather than as a value at the end of its range.
 */
private fun DrawScope.drawMarker(center: Offset, fill: Color, radius: Float) {
    val stroke = MarkerStroke.toPx()
    val outer = radius + stroke / 2f
    val clamped = Offset(
        center.x.coerceIn(outer, (size.width - outer).coerceAtLeast(outer)),
        center.y.coerceIn(outer, (size.height - outer).coerceAtLeast(outer)),
    )

    drawCircle(Color.Black.copy(alpha = 0.35f), outer, clamped)
    drawCircle(fill, radius, clamped)
    drawCircle(Color.White, radius, clamped, style = Stroke(stroke))
}

/**
 * Says the control has the keyboard. Drawn inside the bounds rather than around them, both
 * controls being clipped or rounded to their own edge.
 */
private fun DrawScope.drawFocusRing(cornerRadius: CornerRadius = CornerRadius(8.dp.toPx())) {
    val stroke = 2.dp.toPx()
    drawRoundRect(
        color = Color.White.copy(alpha = 0.9f),
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = cornerRadius,
        style = Stroke(stroke),
    )
}
