/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morphe.gui.data.model.AppCardColorDefaults
import app.morphe.gui.data.model.AppCardColorMode
import app.morphe.gui.data.model.AppCardColorResolver
import app.morphe.gui.data.model.AppCardColorValues
import app.morphe.gui.util.toHexString

// Morphe Brand Colors
object MorpheColors {
    val Blue = Color(0xFF3B7BF7)
    val Teal = Color(0xFF00D1B2)
}

// Morphe Preset Colors
val THEME_PRESET_COLORS = listOf(
    Color(0xFF6750A4),
    Color(0xFF386641),
    Color(0xFF0061A4),
    Color(0xFF8E24AA),
    Color(0xFFEF6C00),
    Color(0xFF00897B),
    Color(0xFFD81B60),
    Color(0xFF5C6BC0),
    Color(0xFF43A047),
    Color(0xFF1DE9B6),
    Color(0xFFFFC400),
    Color(0xFF00B8D4),
    Color(0xFFD32F2F),
    Color(0xFFAFB42B),
    Color(0xFF795548),
    Color(0xFF546E7A)
)

// ════════════════════════════════════════════════════════════════════
//  ACCENT COLOR SYSTEM
// ════════════════════════════════════════════════════════════════════

/**
 * Per-theme accent colors. Components should read from LocalMorpheAccents
 * instead of using MorpheColors.Blue/Teal directly.
 */
data class MorpheAccentColors(
    val primary: Color,    // Buttons, selections, links (replaces MorpheColors.Blue)
    val secondary: Color,  // Badges, options, success states (replaces MorpheColors.Teal)
    val tertiary: Color = Color(0xFF5C6BC0), // Structural emphasis, info accents
    val warning: Color = Color(0xFFFF9800),  // Warning states (was hardcoded everywhere)
)

val LocalMorpheAccents = compositionLocalOf { MorpheAccentColors(MorpheColors.Blue, MorpheColors.Teal) }

private val DarkAccents = MorpheAccentColors(
    primary = Color(0xFFA4C9FF),   // Morphe dark primary, light blue
    secondary = Color(0xFF9CCC65), // Success green for dark surfaces
    tertiary = Color(0xFFD9BDE3),  // Morphe dark tertiary
    warning = Color(0xFFE0A030),   // Amber
)

private val LightAccents = MorpheAccentColors(
    primary = Color(0xFF005FAC),   // Morphe Material blue (buttons, links, selections)
    secondary = Color(0xFF386A20), // Success green
    tertiary = Color(0xFF6D5677),  // Morphe tertiary, muted purple
    warning = Color(0xFFB26A00),   // Amber
)

// ════════════════════════════════════════════════════════════════════
//  CORNER / SHAPE STYLE SYSTEM
// ════════════════════════════════════════════════════════════════════

/**
 * Defines the corner radius style for the current theme.
 */
data class MorpheCornerStyle(
    val small: Dp = 2.dp,
    val medium: Dp = 2.dp,
    val large: Dp = 2.dp,
)

val LocalMorpheCorners = compositionLocalOf { MorpheCornerStyle() }

/**
 * Canonical control sizing across the app. Use these instead of hardcoded `.dp`
 * values for buttons, text fields, search bars, and dialog action rows so the
 * same dimensions apply everywhere, with no per-screen drift.
 *
 * - [controlHeight]: standard interactive height (buttons, text fields, pills,
 *   search bars). Matches the height of OPEN LOGS / OPEN APP DATA action buttons.
 * - [chipHeight]: badges and small chips. MUST stay above twice corners.small,
 *   or the radius clamps to half the height and the chip renders as a capsule
 *   instead of picking up the rounded-rectangle corner the buttons have.
 * - [iconInControl]: icon size used inside controlHeight-sized affordances.
 * - [controlHorizontalPadding]: standard horizontal padding inside a control.
 */
data class MorpheDimens(
    val controlHeight: Dp = 36.dp,
    val chipHeight: Dp = 28.dp,
    val iconInControl: Dp = 14.dp,
    val controlHorizontalPadding: Dp = 12.dp,
)

val LocalMorpheDimens = compositionLocalOf { MorpheDimens() }

/**
 * Shared alpha values for a border or fill drawn in a tone or accent color —
 * a banner, a notice, a hero panel, a status chip — rather than in a flat
 * Material color role, which already carries its own contrast from the
 * color scheme and doesn't need one of these.
 *
 * One small set of numbers instead of every call site choosing its own alpha,
 * so an accent-colored border or wash reads as the same weight of emphasis
 * everywhere it appears, the way Manager's own recurring border/accent
 * treatment does. [MorpheBadge]'s border already established 1dp width at
 * 0.25 alpha for a chip; [badgeBorderAlpha] names that existing value rather
 * than a new number for the same weight, and [accentBorderAlpha] is reached
 * for instead when the surface is not chip-sized.
 */
object MorpheOutline {
    /** Width of every hairline border drawn in a tone or accent color. */
    val width: Dp = 1.dp

    /** Border alpha over a tone/accent color: hero panels, banners, notices. */
    const val accentBorderAlpha = 0.30f

    /** Fill alpha over a tone/accent color, paired with [accentBorderAlpha]'s border. */
    const val accentFillAlpha = 0.12f

    /** Border alpha for a badge/chip-weight accent border — see [MorpheBadge]. */
    const val badgeBorderAlpha = 0.25f

    /** Alpha for a neutral hairline against `MaterialTheme.colorScheme.outline`. */
    const val hairlineAlpha = 0.12f

    /** Alpha of the accent border on an interactive surface: buttons, menus, tooltips. */
    const val emphasisBorderAlpha = 0.35f

    /** Alpha of the border on a selected surface. */
    const val selectedBorderAlpha = 0.55f

    /** Alpha of the border on a disabled surface. */
    const val disabledBorderAlpha = 0.08f

    // ── Semantic outlines ────────────────────────────────────────────────
    // Reach for one of these instead of `BorderStroke(1.dp, color.copy(alpha = …))`, so the
    // same meaning draws the same line on every surface.

    /** The neutral outline as a plain color, for APIs that take a color rather than a stroke. */
    @Composable
    fun neutralColor(): Color = MaterialTheme.colorScheme.outline.copy(alpha = badgeBorderAlpha)

    /** The accent outline as a plain color. */
    @Composable
    fun accentColor(color: Color = LocalMorpheAccents.current.primary): Color =
        color.copy(alpha = emphasisBorderAlpha)

    /** Quiet separation: dividers, dialog and card chrome. */
    @Composable
    fun subtle(): BorderStroke = stroke(MaterialTheme.colorScheme.outline, hairlineAlpha)

    /** Default outline of an interactive control at rest. */
    @Composable
    fun neutral(): BorderStroke = stroke(MaterialTheme.colorScheme.outline, badgeBorderAlpha)

    /** A selected or focused surface. */
    @Composable
    fun selected(): BorderStroke = stroke(MaterialTheme.colorScheme.primary, selectedBorderAlpha)

    /** An accent-colored surface; defaults to the theme's primary accent. */
    @Composable
    fun accent(color: Color = LocalMorpheAccents.current.primary): BorderStroke =
        stroke(color, emphasisBorderAlpha)

    @Composable
    fun success(): BorderStroke = stroke(LocalMorpheAccents.current.secondary, accentBorderAlpha)

    @Composable
    fun warning(): BorderStroke = stroke(LocalMorpheAccents.current.warning, accentBorderAlpha)

    @Composable
    fun error(): BorderStroke = stroke(MaterialTheme.colorScheme.error, emphasisBorderAlpha)

    @Composable
    fun disabled(): BorderStroke = stroke(MaterialTheme.colorScheme.outline, disabledBorderAlpha)

    private fun stroke(color: Color, alpha: Float) = BorderStroke(width, color.copy(alpha = alpha))
}

/**
 * The three corner radii surfaces are built from, taken from the active corner style so that
 * sharp and rounded themes change all of them together.
 */
object MorpheShapes {
    /** Interactive controls: buttons, fields, chips. */
    val control: Shape @Composable get() = RoundedCornerShape(LocalMorpheCorners.current.small)

    /** Cards and panels (24dp in the rounded style, Manager's card radius). */
    val card: Shape @Composable get() = RoundedCornerShape(LocalMorpheCorners.current.large)

    /** Dialog surfaces. */
    val dialog: Shape @Composable get() = RoundedCornerShape(LocalMorpheCorners.current.large)
}

/**
 * Fill for panels that sit over the animated background. Opaque, because motion
 * behind a panel reads through even a few percent of translucency.
 */
val panelFill: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)

/**
 * Scrim for a screen whose content is dense text. The animated background still
 * reads through it, but not enough to bleed into what is on top.
 */
val screenScrim: Color
    @Composable get() = MaterialTheme.colorScheme.background.copy(alpha = SCREEN_SCRIM_ALPHA)

private const val SCREEN_SCRIM_ALPHA = 0.72f

private val SharpCorners = MorpheCornerStyle(small = 2.dp, medium = 2.dp, large = 2.dp)

private val RoundedCorners = MorpheCornerStyle(small = 12.dp, medium = 16.dp, large = 24.dp)

// ════════════════════════════════════════════════════════════════════
//  COLOR SCHEMES
// ════════════════════════════════════════════════════════════════════

private val MorpheDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA4C9FF),
    onPrimary = Color(0xFF00315D),
    primaryContainer = Color(0xFF004884),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3D4758),
    onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFFD9BDE3),
    onTertiary = Color(0xFF3D2946),
    tertiaryContainer = Color(0xFF543F5E),
    onTertiaryContainer = Color(0xFFF6D9FF),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val MorpheAmoledColorScheme = MorpheDarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color.Black,
    onSurfaceVariant = Color(0xFFB0B0B0),
)

private val MorpheLightColorScheme = lightColorScheme(
    primary = Color(0xFF005FAC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF001C39),
    secondary = Color(0xFF545F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF6D5677),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6D9FF),
    onTertiaryContainer = Color(0xFF271430),
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

// ════════════════════════════════════════════════════════════════════
//  THEME PREFERENCE
// ════════════════════════════════════════════════════════════════════

enum class ThemePreference {
    LIGHT,
    DARK,
    AMOLED,
    SYSTEM;

    /** Whether this theme uses dark color scheme (for resource qualifiers). */
    fun isDark(): Boolean = when (this) {
        DARK, AMOLED -> true
        LIGHT -> false
        SYSTEM -> false // caller should check isSystemInDarkTheme()
    }
}

// ════════════════════════════════════════════════════════════════════
//  THEME COMPOSABLE
// ════════════════════════════════════════════════════════════════════

/**
 * Resolves app card colors from the appearance settings, or `null` when cards keep the per-app
 * colors declared by their patch bundle.
 *
 * Static, because a card reads it while drawing rather than while laying out, and a new resolver
 * instance would otherwise invalidate every card that reads it.
 */
val LocalAppCardColorResolver = staticCompositionLocalOf<AppCardColorResolver?> { null }

@Composable
fun MorpheTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    customAccentColorArgb: Int? = null,
    useSharpCorners: Boolean = false,
    appCardColorMode: AppCardColorMode = AppCardColorMode.DEFAULT,
    appCardColorValues: AppCardColorValues = AppCardColorValues(),
    content: @Composable () -> Unit
) {
    val baseColorScheme = when (themePreference) {
        ThemePreference.DARK -> MorpheDarkColorScheme
        ThemePreference.AMOLED -> MorpheAmoledColorScheme
        ThemePreference.LIGHT -> MorpheLightColorScheme
        ThemePreference.SYSTEM -> {
            if (isSystemInDarkTheme()) MorpheDarkColorScheme else MorpheLightColorScheme
        }
    }

    val customPrimary = customAccentColorArgb?.let { Color(it) }

    val colorScheme = if (customPrimary != null) {
        val isDark = baseColorScheme.background.luminance() < 0.5f
        val secondary = customPrimary.shiftLightness(if (isDark) 0.15f else -0.15f)
        val tertiary = customPrimary.shiftLightness(if (isDark) -0.10f else 0.10f)
        val primaryContainer = customPrimary.shiftLightness(if (isDark) 0.25f else -0.25f)
        val secondaryContainer = customPrimary.shiftLightness(if (isDark) 0.35f else -0.35f)
        
        baseColorScheme.copy(
            primary = customPrimary,
            onPrimary = customPrimary.contrastingForeground(),
            secondary = secondary,
            onSecondary = secondary.contrastingForeground(),
            tertiary = tertiary,
            onTertiary = tertiary.contrastingForeground(),
            primaryContainer = primaryContainer,
            onPrimaryContainer = primaryContainer.contrastingForeground(),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = secondaryContainer.contrastingForeground(),
            surfaceTint = customPrimary
        )
    } else {
        baseColorScheme
    }

    val corners = if (useSharpCorners) SharpCorners else RoundedCorners
    val font = Roboto
    val monoFont = RobotoMono
    val baseAccents = when (themePreference) {
        ThemePreference.DARK, ThemePreference.AMOLED -> DarkAccents
        ThemePreference.LIGHT -> LightAccents
        ThemePreference.SYSTEM -> if (isSystemInDarkTheme()) DarkAccents else LightAccents
    }

    val accents = if (customPrimary != null) {
        val isDark = baseColorScheme.background.luminance() < 0.5f
        val secondary = customPrimary.shiftLightness(if (isDark) 0.15f else -0.15f)
        val tertiary = customPrimary.shiftLightness(if (isDark) -0.10f else 0.10f)
        baseAccents.copy(
            primary = customPrimary,
            secondary = secondary,
            tertiary = tertiary
        )
    } else {
        baseAccents
    }

    // Remembered because a fresh resolver instance would invalidate every card that reads it.
    // The accent is passed as the fallback rather than as the value, so ACCENT mode follows a
    // theme accent the user never overrode
    val appCardColorResolver = remember(
        appCardColorMode,
        customAccentColorArgb,
        accents.primary,
        appCardColorValues,
    ) {
        AppCardColorDefaults.resolver(
            mode = appCardColorMode,
            accentHex = customAccentColorArgb?.let { Color(it).toHexString() }.orEmpty(),
            accentFallback = accents.primary,
            values = appCardColorValues,
        )
    }

    CompositionLocalProvider(
        LocalMorpheCorners provides corners,
        LocalMorpheFont provides font,
        LocalMorpheMono provides monoFont,
        LocalMorpheAccents provides accents,
        LocalMorpheDimens provides MorpheDimens(),
        LocalAppCardColorResolver provides appCardColorResolver,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = morpheTypography(font),
            content = content
        )
    }
}

fun Color.shiftLightness(delta: Float): Color {
    val hsl = FloatArray(3)
    colorToHSL(this, hsl)
    hsl[2] = (hsl[2] + delta).coerceIn(0f, 1f)
    return hslToColor(hsl)
}

fun Color.contrastingForeground(): Color {
    return if (this.luminance() > 0.5f) Color.Black else Color.White
}

private fun colorToHSL(color: Color, hsl: FloatArray) {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    var h = 0f
    var s = 0f
    val l = (max + min) / 2f
    if (max != min) {
        val d = max - min
        s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        h = when (max) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            b -> (r - g) / d + 4f
            else -> 0f
        }
        h /= 6f
    }
    hsl[0] = h * 360f
    hsl[1] = s
    hsl[2] = l
}

private fun hslToColor(hsl: FloatArray): Color {
    val h = hsl[0] / 360f
    val s = hsl[1]
    val l = hsl[2]
    var r = l
    var g = l
    var b = l
    if (s != 0f) {
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        r = hueToRGB(p, q, h + 1f / 3f)
        g = hueToRGB(p, q, h)
        b = hueToRGB(p, q, h - 1f / 3f)
    }
    return Color(r, g, b)
}

private fun hueToRGB(p: Float, q: Float, t: Float): Float {
    var t0 = t
    if (t0 < 0f) t0 += 1f
    if (t0 > 1f) t0 -= 1f
    if (t0 < 1f / 6f) return p + (q - p) * 6f * t0
    if (t0 < 1f / 2f) return q
    if (t0 < 2f / 3f) return p + (q - p) * (2f / 3f - t0) * 6f
    return p
}
