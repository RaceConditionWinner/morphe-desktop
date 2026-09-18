/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure black, which a tinted fill is drawn over in the amoled theme. */
private val PureBlack = Color.Black

/** The bar [readableOn] holds content to, WCAG AA for large text. */
private const val MinRatio = 3f

class ColorUtilsTest {

    // ── Parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `a six digit hex parses opaque, with or without the hash`() {
        val expected = Color(0xFFFF4500)
        assertEquals(expected, "#FF4500".toColorOrNull())
        assertEquals(expected, "FF4500".toColorOrNull())
        assertEquals(expected, "  #ff4500  ".toColorOrNull())
    }

    @Test
    fun `an eight digit hex keeps its alpha`() {
        val parsed = "#80FF4500".toColorOrNull()
        assertNotNull(parsed)
        assertEquals(0x80 / 255f, parsed.alpha, absoluteTolerance = 0.01f)
    }

    @Test
    fun `anything that is not a hex color parses to nothing`() {
        // Every one of these reaches the parser: a field mid-edit, a stray character, the token
        // a bundle-bound stop is stored as, and a length no color has
        listOf("", "   ", "#", "#GGGGGG", "bundle", "#FFF", "#FFFFFFFFF", "not a color")
            .forEach { assertNull(it.toColorOrNull(), "expected no color from \"$it\"") }
        assertNull(null.toColorOrNull())
    }

    @Test
    fun `hex survives a round trip through the parser`() {
        listOf("#000000", "#FFFFFF", "#0E3F6E", "#1E5AA8", "#00AFAE", "#FF0033").forEach { hex ->
            assertEquals(hex, hex.toColorOrNull()?.toHexString())
        }
    }

    @Test
    fun `alpha is printed only when it is asked for`() {
        val color = Color(0x80FF4500)
        assertEquals("#FF4500", color.toHexString())
        assertEquals("#80FF4500", color.toHexString(includeAlpha = true))
    }

    // ── HSV ──────────────────────────────────────────────────────────────────

    @Test
    fun `hue comes back in degrees`() {
        val (hue, saturation, value) = Color.Red.toHsv()
        assertEquals(0f, hue, absoluteTolerance = 0.5f)
        assertEquals(1f, saturation, absoluteTolerance = 0.01f)
        assertEquals(1f, value, absoluteTolerance = 0.01f)

        assertEquals(120f, Color.Green.toHsv().first, absoluteTolerance = 0.5f)
        assertEquals(240f, Color.Blue.toHsv().first, absoluteTolerance = 0.5f)
    }

    @Test
    fun `a color survives the trip out to hsv and back`() {
        listOf(Color(0xFF0E3F6E), Color(0xFF1E5AA8), Color(0xFF00AFAE), Color(0xFFFF4500))
            .forEach { original ->
                val (hue, saturation, value) = original.toHsv()
                val rebuilt = Color.hsv(hue, saturation, value)
                assertEquals(original.red, rebuilt.red, absoluteTolerance = 0.01f)
                assertEquals(original.green, rebuilt.green, absoluteTolerance = 0.01f)
                assertEquals(original.blue, rebuilt.blue, absoluteTolerance = 0.01f)
            }
    }

    @Test
    fun `a grey reports no saturation, which is what lets the picker keep its hue`() {
        // The conversion has no hue to report for a grey and says zero, which would swing the
        // panel back to red. Callers key off the saturation instead, so it has to be exactly 0
        listOf(Color.Black, Color.White, Color(0xFF808080)).forEach {
            assertEquals(0f, it.toHsv().second, absoluteTolerance = 0.001f)
        }
    }

    // ── Contrast ─────────────────────────────────────────────────────────────

    @Test
    fun `black on white is the widest contrast there is`() {
        assertEquals(21f, Color.Black.contrastAgainst(Color.White), absoluteTolerance = 0.01f)
        assertEquals(1f, Color.White.contrastAgainst(Color.White), absoluteTolerance = 0.01f)
    }

    @Test
    fun `a dark fill wants light content and a light one does not`() {
        assertTrue(Color.Black.requiresLightContent())
        assertTrue(Color(0xFF0E3F6E).requiresLightContent())
        assertTrue(!Color.White.requiresLightContent())
        assertTrue(!Color(0xFFFFC400).requiresLightContent())
    }

    @Test
    fun `a label that cannot be read on its own fill is replaced`() {
        val fill = Color(0xFFC8CFFD).copy(alpha = 0.55f)
        val label = Color(0xFFADAFC8)

        val readable = label.readableOn(fill, PureBlack)

        assertEquals(Color.White, readable)
        assertTrue(label.contrastAgainst(fill.compositeOver(PureBlack)) < MinRatio)
        assertTrue(readable.contrastAgainst(fill.compositeOver(PureBlack)) >= MinRatio)
    }

    @Test
    fun `a label that already reads is left untouched`() {
        val selected = Color(0xFF004884).copy(alpha = 0.55f) to Color(0xFFD4E3FF)
        val recommended = Color(0xFF543F5E).copy(alpha = 0.6f) to Color(0xFFF6D9FF)

        listOf(selected, recommended).forEach { (fill, label) ->
            assertEquals(label, label.readableOn(fill, PureBlack))
        }
    }

    @Test
    fun `a label is replaced when the fill it lands on flips polarity`() {
        val fill = Color(0xFFFEE27F).copy(alpha = 0.55f)
        val label = Color.Black
        val drawn = fill.compositeOver(PureBlack)

        assertTrue(label.contrastAgainst(drawn) >= MinRatio)
        assertEquals(Color.White, label.readableOn(fill, PureBlack))
    }

    @Test
    fun `a dimmed label stays dimmed after being replaced`() {
        val fill = Color(0xFFC8CFFD).copy(alpha = 0.55f)
        val label = Color(0xFFADAFC8).copy(alpha = 0.5f)

        assertEquals(0.5f, label.readableOn(fill, PureBlack).alpha, absoluteTolerance = 0.01f)
    }

    @Test
    fun `compositing a translucent fill lands between the fill and what is behind it`() {
        val drawn = Color.White.compositeOver(PureBlack, alpha = 0.5f)
        assertEquals(0.5f, drawn.red, absoluteTolerance = 0.01f)
        assertEquals(1f, drawn.alpha, absoluteTolerance = 0.01f)
    }

    // ── Shading ──────────────────────────────────────────────────────────────

    @Test
    fun `lighten walks toward white and darken toward black`() {
        val base = Color(0xFF808080)
        assertTrue(base.lighten(0.5f).luminanceOf() > base.luminanceOf())
        assertTrue(base.darken(0.5f).luminanceOf() < base.luminanceOf())

        assertEquals(Color.White.red, Color.Black.lighten(1f).red, absoluteTolerance = 0.01f)
        assertEquals(0f, Color.White.darken(1f).red, absoluteTolerance = 0.01f)
    }

    @Test
    fun `shading leaves alpha alone`() {
        val base = Color(0xFF808080).copy(alpha = 0.4f)
        assertEquals(0.4f, base.lighten(0.3f).alpha, absoluteTolerance = 0.01f)
        assertEquals(0.4f, base.darken(0.3f).alpha, absoluteTolerance = 0.01f)
    }

    @Test
    fun `an argb round trip rounds to the nearest channel rather than truncating`() {
        listOf(0xFF0E3F6E.toInt(), 0xFFFF4500.toInt(), 0x80123456.toInt(), -1)
            .forEach { assertEquals(it, Color(it).toArgbInt()) }
    }
}

private fun Color.luminanceOf(): Float = red * 0.2126f + green * 0.7152f + blue * 0.0722f
