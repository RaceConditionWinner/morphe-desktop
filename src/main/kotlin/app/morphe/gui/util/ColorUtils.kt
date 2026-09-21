/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.awt.Color as AwtColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The canonical color mathematics for Morphe Desktop, ported from the manager so both
 * applications judge contrast, shading and hex the same way.
 *
 * Nothing here is Compose-aware on purpose: every function is a pure transform, which is
 * what lets the card resolver run outside composition and the tests run without a UI.
 */

/**
 * Hue in degrees, saturation and value in 0..1, the axes a color picker actually offers.
 *
 * Gray has no hue to speak of and the conversion reports 0 for it, which would swing the picker
 * back to red the moment a color loses its saturation, so callers keep the hue they were showing.
 *
 * The manager reads this off `android.graphics.Color`; the JDK's own HSB conversion is the same
 * transform with hue normalised to 0..1, so it is scaled back to degrees here.
 */
fun Color.toHsv(): Triple<Float, Float, Float> {
    val hsb = AwtColor.RGBtoHSB(
        (red * 255f).roundToInt().coerceIn(0, 255),
        (green * 255f).roundToInt().coerceIn(0, 255),
        (blue * 255f).roundToInt().coerceIn(0, 255),
        null,
    )
    return Triple(hsb[0] * 360f, hsb[1], hsb[2])
}

/** Even blend of these colors, for judging what a gradient reads as overall. */
fun List<Color>.blend(): Color = when {
    isEmpty() -> Color.Transparent
    size == 1 -> first()
    else -> Color(
        red = sumOf { it.red.toDouble() }.toFloat() / size,
        green = sumOf { it.green.toDouble() }.toFloat() / size,
        blue = sumOf { it.blue.toDouble() }.toFloat() / size,
        alpha = sumOf { it.alpha.toDouble() }.toFloat() / size,
    )
}

/**
 * Composites this color at [alpha] over [background] and returns the opaque result.
 */
fun Color.compositeOver(background: Color, alpha: Float = this.alpha): Color = Color(
    red = background.red * (1f - alpha) + red * alpha,
    green = background.green * (1f - alpha) + green * alpha,
    blue = background.blue * (1f - alpha) + blue * alpha,
)

/**
 * Returns true if this color, when used as a background, requires light (white) content for contrast.
 * Uses WCAG relative luminance threshold.
 */
fun Color.requiresLightContent(): Boolean = luminance() < 0.5f

/** WCAG contrast ratio against [background], from 1 for identical colors to 21 for black on white. */
fun Color.contrastAgainst(background: Color): Float {
    val content = luminance()
    val behind = background.luminance()
    return (max(content, behind) + 0.05f) / (min(content, behind) + 0.05f)
}

/**
 * This color when it suits a [fill] drawn over [surface], or plain black or white when it does not.
 * Any transparency of its own is carried over, so a dimmed color stays dimmed.
 *
 * A palette pairs each container with an on-color meant for that container drawn opaque. Tinted,
 * the fill becomes a blend the pairing never described, and a light container blends dark while
 * its on-color stays dark. Contrast alone passes that, so polarity is weighed alongside [minRatio].
 */
fun Color.readableOn(fill: Color, surface: Color, minRatio: Float = 3f): Color {
    val background = fill.compositeOver(surface)
    val wantsLightContent = background.requiresLightContent()

    val agreesWithFill = wantsLightContent != requiresLightContent()
    if (agreesWithFill && contrastAgainst(background) >= minRatio) return this

    val replacement = if (wantsLightContent) Color.White else Color.Black
    return replacement.copy(alpha = alpha)
}

/**
 * Lighten a color by mixing with white
 */
fun Color.lighten(factor: Float): Color = Color(
    red = red + (1f - red) * factor,
    green = green + (1f - green) * factor,
    blue = blue + (1f - blue) * factor,
    alpha = alpha,
)

/**
 * Darken a color by mixing with black
 */
fun Color.darken(factor: Float): Color = Color(
    red = red * (1f - factor),
    green = green * (1f - factor),
    blue = blue * (1f - factor),
    alpha = alpha,
)

fun Color.toHexString(includeAlpha: Boolean = false): String {
    val argb = toArgbInt()
    return if (includeAlpha) {
        "#%08X".format(argb)
    } else {
        "#%06X".format(argb and 0xFFFFFF)
    }
}

/**
 * Packed 0xAARRGGBB for this color.
 *
 * Compose Desktop offers `toArgb()`, but it rounds by truncation; the icon studio and the
 * persisted config both round to nearest, so a color written out and read back lands on the
 * value it started from.
 */
fun Color.toArgbInt(): Int {
    fun channel(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (channel(alpha) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}

/**
 * The color this hex string names, or null when it names none.
 *
 * Accepts `RRGGBB` and `AARRGGBB`, with or without a leading `#`. Anything else — an empty
 * field mid-edit, a stray character, the bundle token — is not a color, and saying so is what
 * lets the picker keep the last good value instead of jumping to black.
 */
fun String?.toColorOrNull(): Color? {
    val value = this?.trim().orEmpty().removePrefix("#")
    if (value.isEmpty()) return null
    val parsed = value.toLongOrNull(16) ?: return null
    return when (value.length) {
        6 -> Color(parsed or 0xFF000000L)
        8 -> Color(parsed)
        else -> null
    }
}
