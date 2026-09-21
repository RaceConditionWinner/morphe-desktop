/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.morphe.gui.util.darken
import app.morphe.gui.util.lighten
import app.morphe.gui.util.requiresLightContent
import app.morphe.gui.util.toColorOrNull
import app.morphe.morphe_desktop.generated.resources.*
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * How every app card in the application is colored. One universal choice, not a choice per app:
 * the card a given app ends up with follows from this mode plus that app's own bundle colors.
 *
 * The labels are string resources, resolved where they are drawn through [label] and
 * [description]; only the constant name is persisted.
 */
@Serializable
enum class AppCardColorMode(val labelRes: StringResource, val descriptionRes: StringResource) {
    /** Cards keep the per-app colors declared by their patch bundle. */
    DEFAULT(Res.string.card_fill_mode_default, Res.string.app_card_mode_default_desc),

    /** Cards follow the accent color, spread into a gradient so they keep their depth. */
    ACCENT(Res.string.card_fill_mode_accent, Res.string.app_card_mode_accent_desc),

    /** A three-stop gradient, each stop either a fixed color or bound to the bundle. */
    GRADIENT(Res.string.card_fill_mode_gradient, Res.string.app_card_mode_gradient_desc),

    /** One flat color, either fixed or bound to the bundle. */
    SOLID(Res.string.card_fill_mode_solid, Res.string.app_card_mode_solid_desc);

    val label: String
        @Composable
        get() = stringResource(labelRes)

    val description: String
        @Composable
        get() = stringResource(descriptionRes)
}

/**
 * A position in the card's palette a color can be bound to. [SOLID] is the single stop of
 * [AppCardColorMode.SOLID] rather than a fourth gradient stop.
 */
enum class AppCardColorStop(val titleRes: StringResource) {
    START(Res.string.app_card_stop_start),
    MIDDLE(Res.string.app_card_stop_middle),
    END(Res.string.app_card_stop_end),
    SOLID(Res.string.app_card_stop_solid);

    val title: String
        @Composable
        get() = stringResource(titleRes)
}

/**
 * The four colors the editor keeps, as stored: a hex value, an empty string for "not chosen
 * yet", or [AppCardColorDefaults.BUNDLE_COLOR_TOKEN] for a stop that follows the app's bundle.
 *
 * All four are kept whatever the active mode is, so switching modes back and forth does not
 * discard what was picked in the other one.
 */
data class AppCardColorValues(
    val startHex: String = "",
    val middleHex: String = "",
    val endHex: String = "",
    val solidHex: String = "",
)

/**
 * The universal app card color configuration: the one authoritative answer to "what color are app
 * cards", held in memory as a unit so the mode and the colors it reads can never be a frame apart.
 *
 * Persisted as two fields on the config, applied through [AppCardColorDefaults.resolver].
 */
data class AppCardColorConfig(
    val mode: AppCardColorMode = AppCardColorMode.DEFAULT,
    val values: AppCardColorValues = AppCardColorValues(),
)

/**
 * Resolves the palette of a single card. 'bundleColors' are the colors declared by that app's
 * bundle, so stops bound to the bundle can differ from card to card.
 */
fun interface AppCardColorResolver {
    fun resolve(bundleColors: List<Color>): List<Color>
}

object AppCardColorDefaults {
    private const val COLOR_VALUES_SEPARATOR = "|"

    /** Stored in place of a hex value when a stop follows the color declared by the app bundle. */
    const val BUNDLE_COLOR_TOKEN = "bundle"

    /** Shared Morphe brand gradient tail, matching the manager's `KnownApps`. */
    val GRADIENT_MID = Color(0xFF1E5AA8)
    val GRADIENT_END = Color(0xFF00AFAE)
    val DEFAULT_BASE_COLOR = Color(0xFF0E3F6E)

    /** Default gradient for packages with no bundle-declared color. */
    val defaultGradientColors: List<Color> =
        listOf(DEFAULT_BASE_COLOR, GRADIENT_MID, GRADIENT_END)

    val defaultSolidColor: Color get() = GRADIENT_MID

    // Hue sweep standing in for the bundle colors of apps the user may install, wide enough that
    // the shading of a stop stays visible across both light and dark source colors
    private val BUNDLE_PREVIEW_HUES = listOf(
        Color(0xFFFF3B30),
        Color(0xFFFF9500),
        Color(0xFFFFCC00),
        Color(0xFF34C759),
        Color(0xFF007AFF),
        Color(0xFFAF52DE),
    )

    fun isBundleColor(hex: String): Boolean =
        hex.trim().equals(BUNDLE_COLOR_TOKEN, ignoreCase = true)

    /**
     * The palette a card's own bundle declares, from the `appIconColor` its patches ship.
     *
     * The bundle declares one color; the other two are the shared brand tail, exactly as the
     * manager derives them in `BundleAppMetadata.gradientColors`. A package with no declared
     * color falls back to [defaultGradientColors].
     */
    fun bundleColors(appIconColorHex: String?): List<Color> =
        appIconColorHex.toColorOrNull()
            ?.let { listOf(it, GRADIENT_MID, GRADIENT_END) }
            ?: defaultGradientColors

    /**
     * Card color resolver for [mode], or `null` when cards keep the per-app colors declared by
     * their bundle. [accentFallback] is the accent of the active theme, used by
     * [AppCardColorMode.ACCENT] when the user has not picked a custom accent color.
     */
    fun resolver(
        mode: AppCardColorMode,
        accentHex: String,
        accentFallback: Color,
        values: AppCardColorValues,
    ): AppCardColorResolver? = when (mode) {
        AppCardColorMode.DEFAULT -> null

        AppCardColorMode.ACCENT -> {
            val accentPalette = accentColors(accentHex.toColorOrNull() ?: accentFallback)
            AppCardColorResolver { accentPalette }
        }

        // Hex stops are parsed once here rather than on every resolve, because only the stops
        // bound to the bundle depend on the card being drawn
        AppCardColorMode.GRADIENT -> {
            val start = fixedStop(values.startHex, defaultGradientColors[0])
            val middle = fixedStop(values.middleHex, defaultGradientColors[1])
            val end = fixedStop(values.endHex, defaultGradientColors[2])

            if (start != null && middle != null && end != null) {
                val palette = listOf(start, middle, end)
                AppCardColorResolver { palette }
            } else {
                AppCardColorResolver { bundleColors ->
                    val bundleColor = bundleColors.bundleColor()
                    listOf(
                        start ?: bundleShade(AppCardColorStop.START, bundleColor),
                        middle ?: bundleShade(AppCardColorStop.MIDDLE, bundleColor),
                        end ?: bundleShade(AppCardColorStop.END, bundleColor),
                    )
                }
            }
        }

        AppCardColorMode.SOLID -> {
            val fixed = fixedStop(values.solidHex, defaultSolidColor)

            if (fixed != null) {
                val palette = listOf(fixed, fixed, fixed)
                AppCardColorResolver { palette }
            } else {
                AppCardColorResolver { bundleColors ->
                    val color = bundleShade(AppCardColorStop.SOLID, bundleColors.bundleColor())
                    listOf(color, color, color)
                }
            }
        }
    }

    /**
     * Card colors for [mode] without a specific app in context, used by previews. Bundle-bound
     * stops resolve against [previewBundleColors]. Null keeps cards on their own bundle colors.
     */
    fun colors(
        mode: AppCardColorMode,
        accentHex: String,
        accentFallback: Color,
        values: AppCardColorValues,
        previewBundleColors: List<Color> = defaultGradientColors,
    ): List<Color>? = resolver(mode, accentHex, accentFallback, values)
        ?.resolve(previewBundleColors)

    fun encodeColorValues(
        startHex: String,
        middleHex: String,
        endHex: String,
        solidHex: String,
    ): String = listOf(startHex, middleHex, endHex, solidHex)
        .joinToString(COLOR_VALUES_SEPARATOR)

    fun encodeColorValues(values: AppCardColorValues): String = encodeColorValues(
        startHex = values.startHex,
        middleHex = values.middleHex,
        endHex = values.endHex,
        solidHex = values.solidHex,
    )

    fun decodeColorValues(value: String): AppCardColorValues {
        if (value.isBlank()) return AppCardColorValues()

        val parts = value.split(COLOR_VALUES_SEPARATOR)
        return AppCardColorValues(
            startHex = parts.getOrNull(0).orEmpty(),
            middleHex = parts.getOrNull(1).orEmpty(),
            endHex = parts.getOrNull(2).orEmpty(),
            solidHex = parts.getOrNull(3).orEmpty(),
        )
    }

    /** Spreads [accent] into a three-stop gradient so single-color cards keep their depth. */
    fun accentColors(accent: Color): List<Color> = listOf(
        accent.darken(if (accent.requiresLightContent()) 0.16f else 0.46f),
        accent,
        if (accent.requiresLightContent()) accent.lighten(0.22f) else accent.darken(0.18f),
    )

    fun gradientColors(
        startHex: String,
        middleHex: String,
        endHex: String,
        bundleColor: Color = defaultGradientColors[0],
    ): List<Color> = listOf(
        stopColor(startHex, AppCardColorStop.START, bundleColor, defaultGradientColors[0]),
        stopColor(middleHex, AppCardColorStop.MIDDLE, bundleColor, defaultGradientColors[1]),
        stopColor(endHex, AppCardColorStop.END, bundleColor, defaultGradientColors[2]),
    )

    fun solidColors(colorHex: String, bundleColor: Color = defaultSolidColor): List<Color> {
        val color = stopColor(colorHex, AppCardColorStop.SOLID, bundleColor, defaultSolidColor)
        return listOf(color, color, color)
    }

    /** Color of [stop]: either its fixed [hex] value or a shade derived from [bundleColor]. */
    private fun stopColor(
        hex: String,
        stop: AppCardColorStop,
        bundleColor: Color,
        fallback: Color,
    ): Color = fixedStop(hex, fallback) ?: bundleShade(stop, bundleColor)

    /** Fixed color of a stop, or `null` when it follows the bundle and needs a card to resolve. */
    private fun fixedStop(hex: String, fallback: Color): Color? =
        if (isBundleColor(hex)) null else (hex.toColorOrNull() ?: fallback)

    /**
     * Stand-in shown where a bundle-bound [stop] would otherwise preview as one color: a hue
     * sweep shaded the way the stop shades a real bundle color, so the preview reads as varying
     * per app instead of as a color the user picked.
     */
    fun bundleStopPreview(stop: AppCardColorStop): List<Color> =
        BUNDLE_PREVIEW_HUES.map { bundleShade(stop, it) }

    /**
     * Shade of [bundleColor] used by a stop bound to the bundle. The start stop keeps the color
     * untouched so a card still opens on the app's own color the way [AppCardColorMode.DEFAULT]
     * does, while the later stops drift away from it to keep the gradient from reading flat.
     */
    private fun bundleShade(stop: AppCardColorStop, bundleColor: Color): Color = when (stop) {
        AppCardColorStop.START, AppCardColorStop.SOLID -> bundleColor

        AppCardColorStop.MIDDLE -> if (bundleColor.requiresLightContent()) {
            bundleColor.lighten(0.18f)
        } else {
            bundleColor.darken(0.16f)
        }

        AppCardColorStop.END -> if (bundleColor.requiresLightContent()) {
            bundleColor.lighten(0.38f)
        } else {
            bundleColor.darken(0.34f)
        }
    }

    private fun List<Color>.bundleColor(): Color = firstOrNull() ?: defaultGradientColors[0]
}
