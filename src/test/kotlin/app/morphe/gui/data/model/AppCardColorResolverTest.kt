/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.model

import androidx.compose.ui.graphics.Color
import app.morphe.gui.util.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Theme accent used wherever the user has picked none of their own. */
private val AccentFallback = Color(0xFFA4C9FF)

private const val BUNDLE = AppCardColorDefaults.BUNDLE_COLOR_TOKEN

/** Reddit's declared color, and one of YouTube's, so "per app" is tested with real values. */
private val RedditBundle = listOf(Color(0xFFFF4500), AppCardColorDefaults.GRADIENT_MID, AppCardColorDefaults.GRADIENT_END)
private val YouTubeBundle = listOf(Color(0xFFFF0033), AppCardColorDefaults.GRADIENT_MID, AppCardColorDefaults.GRADIENT_END)

class AppCardColorResolverTest {

    private fun resolver(mode: AppCardColorMode, values: AppCardColorValues = AppCardColorValues()) =
        AppCardColorDefaults.resolver(
            mode = mode,
            accentHex = "",
            accentFallback = AccentFallback,
            values = values,
        )

    // ── Default ──────────────────────────────────────────────────────────────

    @Test
    fun `default has no resolver at all, which is what leaves cards on their bundle`() {
        assertNull(resolver(AppCardColorMode.DEFAULT))
    }

    // ── Bundle colors ────────────────────────────────────────────────────────

    @Test
    fun `a package with no declared color falls back to the default palette`() {
        assertEquals(AppCardColorDefaults.defaultGradientColors, AppCardColorDefaults.bundleColors(null))
        assertEquals(AppCardColorDefaults.defaultGradientColors, AppCardColorDefaults.bundleColors(""))
        assertEquals(AppCardColorDefaults.defaultGradientColors, AppCardColorDefaults.bundleColors("not a color"))
    }

    @Test
    fun `a declared color leads the palette and the brand tail follows`() {
        val colors = AppCardColorDefaults.bundleColors("#FF4500")
        assertEquals(Color(0xFFFF4500), colors[0])
        assertEquals(AppCardColorDefaults.GRADIENT_MID, colors[1])
        assertEquals(AppCardColorDefaults.GRADIENT_END, colors[2])
    }

    // ── Gradient ─────────────────────────────────────────────────────────────

    @Test
    fun `three fixed stops resolve to themselves whatever app is asking`() {
        val values = AppCardColorValues(
            startHex = "#112233",
            middleHex = "#445566",
            endHex = "#778899",
        )
        val resolved = resolver(AppCardColorMode.GRADIENT, values)!!

        val expected = listOf(Color(0xFF112233), Color(0xFF445566), Color(0xFF778899))
        assertEquals(expected, resolved.resolve(RedditBundle))
        assertEquals(expected, resolved.resolve(YouTubeBundle))
    }

    @Test
    fun `a bundle bound start stop is the app's own color, untouched`() {
        val values = AppCardColorValues(startHex = BUNDLE, middleHex = "#445566", endHex = "#778899")
        val resolved = resolver(AppCardColorMode.GRADIENT, values)!!

        assertEquals(RedditBundle[0], resolved.resolve(RedditBundle)[0])
        assertEquals(YouTubeBundle[0], resolved.resolve(YouTubeBundle)[0])
    }

    @Test
    fun `middle and end shade away from the bundle color so the gradient does not read flat`() {
        // A genuinely light bundle color (its real luminance, not just how vivid it looks, is
        // what requiresLightContent() reads) so this exercises the darkening branch of
        // bundleShade(). #FF4500 looks bright but is actually dark by that measure — it belongs
        // to the lightening branch, which the sibling "dark bundle color" test below covers.
        val lightBundle = listOf(Color(0xFFFFC400), AppCardColorDefaults.GRADIENT_MID, AppCardColorDefaults.GRADIENT_END)
        val values = AppCardColorValues(startHex = BUNDLE, middleHex = BUNDLE, endHex = BUNDLE)
        val resolved = resolver(AppCardColorMode.GRADIENT, values)!!.resolve(lightBundle)

        assertEquals(lightBundle[0], resolved[0])
        assertNotEquals(resolved[0], resolved[1])
        assertNotEquals(resolved[1], resolved[2])

        // Light enough to want dark content, so its stops darken
        assertTrue(resolved[1].luminanceOf() < resolved[0].luminanceOf())
        assertTrue(resolved[2].luminanceOf() < resolved[1].luminanceOf())
    }

    @Test
    fun `a dark bundle color shades the other way, so the stops stay visible`() {
        val darkBundle = listOf(Color(0xFF101820), AppCardColorDefaults.GRADIENT_MID, AppCardColorDefaults.GRADIENT_END)
        val values = AppCardColorValues(startHex = BUNDLE, middleHex = BUNDLE, endHex = BUNDLE)
        val resolved = resolver(AppCardColorMode.GRADIENT, values)!!.resolve(darkBundle)

        assertTrue(resolved[1].luminanceOf() > resolved[0].luminanceOf())
        assertTrue(resolved[2].luminanceOf() > resolved[1].luminanceOf())
    }

    @Test
    fun `mixing a bundle stop with fixed ones keeps the card per app`() {
        // START = bundle, MIDDLE = bundle, END = fixed
        val values = AppCardColorValues(startHex = BUNDLE, middleHex = BUNDLE, endHex = "#123456")
        val resolved = resolver(AppCardColorMode.GRADIENT, values)!!

        val reddit = resolved.resolve(RedditBundle)
        val youTube = resolved.resolve(YouTubeBundle)

        assertNotEquals(reddit[0], youTube[0])
        assertNotEquals(reddit[1], youTube[1])
        assertEquals(Color(0xFF123456), reddit[2])
        assertEquals(Color(0xFF123456), youTube[2])
    }

    @Test
    fun `an app with no bundle colors at all still resolves`() {
        val values = AppCardColorValues(startHex = BUNDLE, middleHex = BUNDLE, endHex = BUNDLE)
        val resolved = resolver(AppCardColorMode.GRADIENT, values)!!.resolve(emptyList())

        assertEquals(3, resolved.size)
        assertEquals(AppCardColorDefaults.defaultGradientColors[0], resolved[0])
    }

    @Test
    fun `an unreadable stop falls back rather than failing`() {
        val values = AppCardColorValues(startHex = "nonsense", middleHex = "", endHex = "#778899")
        val resolved = resolver(AppCardColorMode.GRADIENT, values)!!.resolve(RedditBundle)

        assertEquals(AppCardColorDefaults.defaultGradientColors[0], resolved[0])
        assertEquals(AppCardColorDefaults.defaultGradientColors[1], resolved[1])
        assertEquals(Color(0xFF778899), resolved[2])
    }

    // ── Solid ────────────────────────────────────────────────────────────────

    @Test
    fun `a fixed solid is the same color three times`() {
        val values = AppCardColorValues(solidHex = "#445566")
        val resolved = resolver(AppCardColorMode.SOLID, values)!!.resolve(RedditBundle)

        assertEquals(listOf(Color(0xFF445566), Color(0xFF445566), Color(0xFF445566)), resolved)
    }

    @Test
    fun `a bundle bound solid is the app's own color`() {
        val values = AppCardColorValues(solidHex = BUNDLE)
        val resolved = resolver(AppCardColorMode.SOLID, values)!!

        assertEquals(List(3) { RedditBundle[0] }, resolved.resolve(RedditBundle))
        assertEquals(List(3) { YouTubeBundle[0] }, resolved.resolve(YouTubeBundle))
    }

    // ── Accent ───────────────────────────────────────────────────────────────

    @Test
    fun `accent spreads into three distinct stops rather than repeating itself`() {
        val resolved = resolver(AppCardColorMode.ACCENT)!!.resolve(RedditBundle)

        assertEquals(3, resolved.size)
        assertEquals(AccentFallback, resolved[1])
        assertNotEquals(resolved[0], resolved[1])
        assertNotEquals(resolved[1], resolved[2])
    }

    @Test
    fun `accent ignores the app, being the one mode that is the same everywhere`() {
        val resolved = resolver(AppCardColorMode.ACCENT)!!
        assertEquals(resolved.resolve(RedditBundle), resolved.resolve(YouTubeBundle))
    }

    @Test
    fun `a custom accent hex wins over the theme accent`() {
        val resolved = AppCardColorDefaults.resolver(
            mode = AppCardColorMode.ACCENT,
            accentHex = "#FF4500",
            accentFallback = AccentFallback,
            values = AppCardColorValues(),
        )!!.resolve(RedditBundle)

        assertEquals(Color(0xFFFF4500), resolved[1])
    }

    @Test
    fun `a dark accent lightens its tail and a light one darkens it`() {
        val dark = AppCardColorDefaults.accentColors(Color(0xFF101820))
        assertTrue(dark[2].luminanceOf() > dark[1].luminanceOf())

        val light = AppCardColorDefaults.accentColors(Color(0xFFFFC400))
        assertTrue(light[2].luminanceOf() < light[1].luminanceOf())
    }

    // ── Bundle stop preview ──────────────────────────────────────────────────

    @Test
    fun `the bundle preview varies across hues, so it does not read as one picked color`() {
        val preview = AppCardColorDefaults.bundleStopPreview(AppCardColorStop.END)
        assertTrue(preview.size > 1)
        assertEquals(preview.size, preview.distinct().size)
    }

    @Test
    fun `the start stop previews the hues untouched, the way it leaves a real bundle color`() {
        val start = AppCardColorDefaults.bundleStopPreview(AppCardColorStop.START)
        val solid = AppCardColorDefaults.bundleStopPreview(AppCardColorStop.SOLID)
        assertEquals(start, solid)
    }

    // ── Token ────────────────────────────────────────────────────────────────

    @Test
    fun `the bundle token is recognised however it is cased or padded`() {
        listOf("bundle", "BUNDLE", "Bundle", "  bundle  ").forEach {
            assertTrue(AppCardColorDefaults.isBundleColor(it), "expected \"$it\" to be the token")
        }
        listOf("", "#bundle", "bundled", "#FF4500").forEach {
            assertTrue(!AppCardColorDefaults.isBundleColor(it), "expected \"$it\" not to be the token")
        }
    }

    @Test
    fun `a token is never turned into a concrete color on the way in`() {
        // The whole point of the token: what is stored stays an instruction, so the same
        // configuration can still resolve differently for a different app
        val values = AppCardColorValues(startHex = BUNDLE, middleHex = BUNDLE, endHex = BUNDLE)
        val encoded = AppCardColorDefaults.encodeColorValues(values)
        val decoded = AppCardColorDefaults.decodeColorValues(encoded)

        assertEquals(BUNDLE, decoded.startHex)
        assertTrue(AppCardColorDefaults.isBundleColor(decoded.endHex))
    }

    // ── Preview helper ───────────────────────────────────────────────────────

    @Test
    fun `the preview helper agrees with the resolver it stands in for`() {
        val values = AppCardColorValues(startHex = BUNDLE, middleHex = "#445566", endHex = "#778899")

        val viaColors = AppCardColorDefaults.colors(
            mode = AppCardColorMode.GRADIENT,
            accentHex = "",
            accentFallback = AccentFallback,
            values = values,
            previewBundleColors = RedditBundle,
        )
        val viaResolver = resolver(AppCardColorMode.GRADIENT, values)!!.resolve(RedditBundle)

        assertEquals(viaResolver, viaColors)
    }

    @Test
    fun `the gradient helper shades a bundle stop the same way the resolver does`() {
        val values = AppCardColorValues(startHex = BUNDLE, middleHex = BUNDLE, endHex = BUNDLE)
        val viaResolver = resolver(AppCardColorMode.GRADIENT, values)!!.resolve(RedditBundle)
        val viaHelper = AppCardColorDefaults.gradientColors(
            startHex = BUNDLE,
            middleHex = BUNDLE,
            endHex = BUNDLE,
            bundleColor = RedditBundle[0],
        )

        assertEquals(viaResolver, viaHelper)
    }

    @Test
    fun `the default palette is the one the manager ships`() {
        assertEquals("#0E3F6E", AppCardColorDefaults.defaultGradientColors[0].toHexString())
        assertEquals("#1E5AA8", AppCardColorDefaults.defaultGradientColors[1].toHexString())
        assertEquals("#00AFAE", AppCardColorDefaults.defaultGradientColors[2].toHexString())
        assertEquals(AppCardColorDefaults.GRADIENT_MID, AppCardColorDefaults.defaultSolidColor)
    }
}

private fun Color.luminanceOf(): Float = red * 0.2126f + green * 0.7152f + blue * 0.0722f
