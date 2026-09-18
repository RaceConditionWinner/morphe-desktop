/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.morphe.gui.data.model.AppCardColorDefaults
import app.morphe.gui.ui.theme.LocalAppCardColorResolver
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.shiftLightness
import app.morphe.gui.util.blend
import app.morphe.gui.util.compositeOver
import app.morphe.gui.util.requiresLightContent

/**
 * The card's three-stop glass sweep. Every translucent layer costs a full blend pass over the
 * card, so the fill is one gradient rather than a stack of washes.
 */
private const val GLASS_START_ALPHA = 0.70f
private const val GLASS_MID_ALPHA = 0.58f
private const val GLASS_END_ALPHA = 0.64f

/** What the sweep averages out to, which is what its content actually lands on. */
private const val GLASS_MEAN_ALPHA = (GLASS_START_ALPHA + GLASS_MID_ALPHA + GLASS_END_ALPHA) / 3f

/** How far a hovered card lifts, in lightness. */
private const val HOVER_LIFT = 0.03f

/**
 * The colors a card's own content should draw itself in, chosen from the fill that content will
 * sit on.
 *
 * White reads on the colors a bundle declares for itself, but the appearance settings let a card
 * be any color, including one light enough to swallow it. Rather than every row inside a card
 * working that out for itself, [AppCard] resolves it once and publishes it here.
 */
@Immutable
data class AppCardInk(
    /** Primary label: app name, headline. */
    val title: Color,
    /** Secondary label: version, package, metadata. */
    val subtitle: Color,
    /** Wash behind a chip or badge drawn on the card. */
    val chipContainer: Color,
    /** Label inside that chip, and the fill of an opaque badge. */
    val chipContent: Color,
    /** Hairline for boxes and placeholders drawn on the card. */
    val outline: Color,
) {
    companion object {
        /** Ink for a fill that wants [light] content, or dark content when it does not. */
        fun of(light: Boolean): AppCardInk {
            val base = if (light) Color.White else Color.Black
            return AppCardInk(
                title = base,
                subtitle = base.copy(alpha = 0.75f),
                chipContainer = base.copy(alpha = 0.20f),
                chipContent = base,
                outline = base.copy(alpha = 0.35f),
            )
        }

        /**
         * Content for a dark fill, which is what a card is unless the settings take it somewhere
         * else. Also the value outside any card, so a component that can appear in both places
         * keeps working when it is used off one.
         */
        val OnDark = of(light = true)
    }
}

/**
 * Ink for the card currently being drawn. Read this instead of hard-coding white in anything
 * rendered inside an [AppCard].
 */
val LocalAppCardInk = compositionLocalOf { AppCardInk.OnDark }

/**
 * Ink for chips drawn on a card fill. Kept as a name of its own because that is what the call
 * sites ask for, but it is the card's own resolved content color rather than a tinted white.
 */
val cardChipInk: Color
    @Composable get() = LocalAppCardInk.current.chipContent

/**
 * One app card.
 *
 * The colors come from the universal app card configuration rather than from this call site:
 * [appIconColorHex] is what the app's own patch bundle declares, and
 * [LocalAppCardColorResolver] decides what the card makes of it. A null resolver is
 * [app.morphe.gui.data.model.AppCardColorMode.DEFAULT], where the bundle's own palette is the
 * card's palette.
 *
 * @param appIconColorHex The `appIconColor` this app's patches declare, or null when they
 *   declare none.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = LocalMorpheCorners.current.medium,
    appIconColorHex: String? = null,
    interactive: Boolean = true,
    onClick: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val resolver = LocalAppCardColorResolver.current

    // Both steps are cheap, but a list of cards re-runs them on every scroll frame otherwise
    val bundleColors = remember(appIconColorHex) {
        AppCardColorDefaults.bundleColors(appIconColorHex)
    }
    val colors = remember(resolver, bundleColors) {
        resolver?.resolve(bundleColors) ?: bundleColors
    }

    // The glass is translucent, so what content lands on is the fill blended with whatever the
    // window is showing behind the card, not the fill on its own
    val behind = MaterialTheme.colorScheme.background
    val ink = remember(colors, behind) {
        val drawn = colors.blend().compositeOver(behind, GLASS_MEAN_ALPHA)
        AppCardInk.of(light = drawn.requiresLightContent())
    }

    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    val hoverProgress by animateFloatAsState(
        targetValue = if (isHovered && interactive) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "hover_progress",
    )

    val shape = RoundedCornerShape(cornerRadius)
    val baseColor = colors.firstOrNull() ?: Color.White
    val midColor = colors.getOrElse(1) { baseColor }
    val endColor = colors.lastOrNull() ?: baseColor

    Box(
        modifier = modifier
            .clip(shape)
            // Brushes are rebuilt only when the size, the palette or the hover lift changes, so
            // scrolling a list of cards does not reallocate them on every frame
            .drawWithCache {
                val w = size.width
                val h = size.height
                val cr = CornerRadius(cornerRadius.toPx())
                val lift = hoverProgress * HOVER_LIFT

                // One sweep from the bottom-start tint through to the top-end accent
                val glass = Brush.linearGradient(
                    colors = listOf(
                        baseColor.shiftLightness(lift).copy(alpha = GLASS_START_ALPHA),
                        midColor.shiftLightness(lift).copy(alpha = GLASS_MID_ALPHA),
                        endColor.shiftLightness(lift).copy(alpha = GLASS_END_ALPHA),
                    ),
                    start = Offset(0f, h),
                    end = Offset(w, 0f),
                )

                // Border: bright top-start, faded bottom-end. Struck in the card's own content
                // color so it stays visible when the fill flips light
                val border = Brush.linearGradient(
                    colors = listOf(
                        ink.chipContent.copy(alpha = 0.65f),
                        midColor.copy(alpha = 0.30f),
                        endColor.copy(alpha = 0.15f),
                        ink.chipContent.copy(alpha = 0.20f),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w, h),
                )
                val borderStroke = Stroke(width = 1.5.dp.toPx())

                onDrawWithContent {
                    drawRoundRect(brush = glass, cornerRadius = cr)
                    drawContent()
                    drawRoundRect(brush = border, cornerRadius = cr, style = borderStroke)
                }
            }
            .hoverable(hoverInteraction)
            .then(
                if (interactive) {
                    Modifier.handCursor().clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    ) {
        CompositionLocalProvider(LocalAppCardInk provides ink) {
            content()
        }
    }
}
