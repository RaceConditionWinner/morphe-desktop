/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.data.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppCardColorPersistenceTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "#kind"
    }

    // ── Encoding ─────────────────────────────────────────────────────────────

    @Test
    fun `the four colors survive encode and decode`() {
        val values = AppCardColorValues(
            startHex = "#112233",
            middleHex = AppCardColorDefaults.BUNDLE_COLOR_TOKEN,
            endHex = "#778899",
            solidHex = "#445566",
        )

        val decoded = AppCardColorDefaults.decodeColorValues(
            AppCardColorDefaults.encodeColorValues(values)
        )

        assertEquals(values, decoded)
    }

    @Test
    fun `a blank encoding decodes to nothing chosen`() {
        assertEquals(AppCardColorValues(), AppCardColorDefaults.decodeColorValues(""))
        assertEquals(AppCardColorValues(), AppCardColorDefaults.decodeColorValues("   "))
    }

    @Test
    fun `a truncated encoding decodes what is there rather than failing`() {
        val decoded = AppCardColorDefaults.decodeColorValues("#112233|#445566")

        assertEquals("#112233", decoded.startHex)
        assertEquals("#445566", decoded.middleHex)
        assertEquals("", decoded.endHex)
        assertEquals("", decoded.solidHex)
    }

    @Test
    fun `colors picked in one mode are kept while another mode is active`() {
        // The editor hands over all four whatever the mode is, so switching to solid and back
        // finds the gradient where it was left
        val values = AppCardColorValues(
            startHex = "#112233",
            middleHex = "#445566",
            endHex = "#778899",
            solidHex = "#AABBCC",
        )
        val config = AppConfig(
            appCardColorMode = AppCardColorMode.SOLID.name,
            customAppCardColors = AppCardColorDefaults.encodeColorValues(values),
        )

        assertEquals(values, config.getAppCardColorValues())
    }

    // ── Config ───────────────────────────────────────────────────────────────

    @Test
    fun `the configuration survives a round trip through the config file`() {
        val config = AppConfig(
            appCardColorMode = AppCardColorMode.GRADIENT.name,
            customAppCardColors = AppCardColorDefaults.encodeColorValues(
                AppCardColorValues(
                    startHex = AppCardColorDefaults.BUNDLE_COLOR_TOKEN,
                    middleHex = "#445566",
                    endHex = "#778899",
                    solidHex = "#AABBCC",
                )
            ),
        )

        val back = json.decodeFromString(AppConfig.serializer(), json.encodeToString(AppConfig.serializer(), config))

        assertEquals(AppCardColorMode.GRADIENT, back.getAppCardColorMode())
        assertEquals(config.getAppCardColorValues(), back.getAppCardColorValues())
        assertEquals(
            AppCardColorDefaults.BUNDLE_COLOR_TOKEN,
            back.getAppCardColorValues().startHex,
            "the bundle token must stay an instruction, not resolve to a color on the way through",
        )
    }

    @Test
    fun `a fresh config is on the default mode with nothing picked`() {
        val config = AppConfig()

        assertEquals(AppCardColorMode.DEFAULT, config.getAppCardColorMode())
        assertEquals(AppCardColorValues(), config.getAppCardColorValues())
    }

    @Test
    fun `a mode the build does not know degrades to the default`() {
        assertEquals(
            AppCardColorMode.DEFAULT,
            AppConfig(appCardColorMode = "RAINBOW").getAppCardColorMode(),
        )
        assertEquals(
            AppCardColorMode.DEFAULT,
            AppConfig(appCardColorMode = "").getAppCardColorMode(),
        )
    }

    @Test
    fun `every mode names itself the same way in and out`() {
        AppCardColorMode.entries.forEach { mode ->
            assertEquals(mode, AppConfig(appCardColorMode = mode.name).getAppCardColorMode())
        }
    }

    @Test
    fun `reset is the configuration a fresh install has`() {
        // What the editor's reset restores: the default mode, and the default palette as the
        // colors, rather than one field cleared
        val reset = AppConfig(
            appCardColorMode = AppCardColorMode.DEFAULT.name,
            customAppCardColors = "",
        )

        assertEquals(AppConfig().getAppCardColorMode(), reset.getAppCardColorMode())
        assertEquals(AppConfig().getAppCardColorValues(), reset.getAppCardColorValues())
    }

    // ── Legacy ───────────────────────────────────────────────────────────────

    @Test
    fun `a config written before the universal model still loads`() {
        // Old builds wrote per-app fills and a global fill. Unknown keys are ignored and the
        // legacy fields are declared, so neither stops the config from being read
        val legacy = """
            {
              "themePreference": "DARK",
              "cardFills": {
                "com.reddit.frontpage": { "#kind": "app.morphe.gui.icon.IconProject.Background.Solid", "argb": -49920 }
              },
              "globalCardFill": { "#kind": "app.morphe.gui.icon.IconProject.Background.Solid", "argb": -49920 },
              "somethingThisBuildNeverHeardOf": true
            }
        """.trimIndent()

        val config = json.decodeFromString(AppConfig.serializer(), legacy)

        assertEquals("DARK", config.themePreference)
        assertTrue(config.cardFills.isNotEmpty(), "the legacy field has to survive the read to be migrated")
        assertEquals(AppCardColorMode.DEFAULT, config.getAppCardColorMode())
    }
}
