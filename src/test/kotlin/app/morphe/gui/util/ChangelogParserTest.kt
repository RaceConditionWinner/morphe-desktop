/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangelogParserTest {

    private val sample = """
        ## [1.39.0](https://github.com/MorpheApp/morphe-patches/compare/v1.38.0...v1.39.0) (2026-08-05)

        ### 🐛 Bug Fixes

        * **YouTube - Hide ads:** Hide the new fullscreen promo ([abc1234](https://github.com/x/y/commit/abc1234))
        * **Reddit:** Fix crash on profile open

        ## [1.38.0](https://github.com/MorpheApp/morphe-patches/compare/v1.37.0...v1.38.0) (2026-08-01)

        ### ✨ Features

        * **YouTube Music:** Add track crossfade
        * Bump some dependency

        ## 1.37.0 (2026-07-20)

        ### 🐛 Bug Fixes

        * **YouTube:** Add experimental support for `21.25.523`
    """.trimIndent()

    private val entries = ChangelogParser.parse(sample)

    @Test
    fun `parses every heading style newest first`() {
        assertEquals(listOf("1.39.0", "1.38.0", "1.37.0"), entries.map { it.version })
        assertEquals("2026-08-05", entries.first().date)
        assertEquals("2026-07-20", entries.last().date)
    }

    @Test
    fun `scoped bullets are grouped by scope`() {
        val newest = entries.first().scopedBullets
        assertEquals(setOf("YouTube - Hide ads", "Reddit"), newest.keys)
        assertFalse(entries.first().content.contains("commit/abc1234"))
    }

    @Test
    fun `unscoped bullets are ignored`() {
        assertEquals(setOf("YouTube Music"), entries[1].scopedBullets.keys)
    }

    @Test
    fun `sub-scope matches its parent app`() {
        assertTrue(ChangelogParser.hasChangesFor(entries, "1.38.0", listOf("YouTube")))
    }

    @Test
    fun `only newer entries count`() {
        assertTrue(ChangelogParser.hasChangesFor(entries, "1.38.0", listOf("Reddit")))
        assertFalse(ChangelogParser.hasChangesFor(entries, "1.39.0", listOf("Reddit")))
    }

    @Test
    fun `an app untouched by newer entries gets no badge`() {
        assertFalse(ChangelogParser.hasChangesFor(entries, "1.38.0", listOf("YouTube Music")))
        assertTrue(ChangelogParser.hasChangesFor(entries, "1.37.0", listOf("YouTube Music")))
    }

    @Test
    fun `experimental support additions alone do not count`() {
        val onlyExperimental = entries.filter { it.version == "1.37.0" }
        assertFalse(ChangelogParser.hasChangesFor(onlyExperimental, "1.36.0", listOf("YouTube")))
    }

    @Test
    fun `a real bullet alongside an experimental addition still counts`() {
        val mixed = ChangelogParser.parse(
            """
            ## [2.0.0](https://x/compare/v1.0.0...v2.0.0) (2026-08-05)

            * **YouTube:** Add experimental support for `21.25.523`
            * **YouTube:** Fix seekbar rendering
            """.trimIndent()
        )
        assertTrue(ChangelogParser.hasChangesFor(mixed, "1.0.0", listOf("YouTube")))
    }

    @Test
    fun `empty name list never matches`() {
        assertFalse(ChangelogParser.hasChangesFor(entries, "1.36.0", emptyList()))
    }

    @Test
    fun `scope matching is case insensitive`() {
        assertTrue(ChangelogParser.hasChangesFor(entries, "1.38.0", listOf("reddit")))
    }

    @Test
    fun `unknown heading formats yield no entries`() {
        val foreign = ChangelogParser.parse("# Changelog\n\n- did some stuff\n- did more stuff")
        assertTrue(foreign.isEmpty())
    }

    // ── entriesFor: app-scoped filtering ────────────────────────────────────

    private val multiApp = ChangelogParser.parse(
        """
        ## [1.45.0](https://x/compare/v1.44.0...v1.45.0) (2026-09-01)

        * **Instagram:** Added feature A
        * **Reddit:** Fixed feature B
        * **YouTube:** Changed feature C
        * General maintenance
        """.trimIndent()
    )

    @Test
    fun `entriesFor keeps only the requested app's bullets`() {
        val scoped = ChangelogParser.entriesFor(multiApp, listOf("Instagram"))
        assertEquals(1, scoped.size)
        assertTrue(scoped[0].content.contains("Added feature A"))
        assertFalse(scoped[0].content.contains("Fixed feature B"), "Reddit's change leaked into Instagram's scope")
        assertFalse(scoped[0].content.contains("Changed feature C"), "YouTube's change leaked into Instagram's scope")
    }

    @Test
    fun `entriesFor puts unscoped bullets under the general heading, once asked for one`() {
        val withGeneral = ChangelogParser.entriesFor(multiApp, listOf("Instagram"), generalHeading = "General changes")
        assertTrue(withGeneral[0].content.contains("### General changes"))
        assertTrue(withGeneral[0].content.contains("General maintenance"))

        // Without a heading to file them under, unscoped bullets are left out entirely
        // rather than attached to whichever app happened to be asked for
        val withoutGeneral = ChangelogParser.entriesFor(multiApp, listOf("Instagram"))
        assertFalse(withoutGeneral[0].content.contains("General maintenance"))
    }

    @Test
    fun `entriesFor drops an entry with nothing for the requested app`() {
        val onlyOthers = ChangelogParser.parse(
            """
            ## [2.0.0](https://x/compare/v1.0.0...v2.0.0) (2026-09-01)

            * **Reddit:** Fixed feature B
            """.trimIndent()
        )
        assertTrue(ChangelogParser.entriesFor(onlyOthers, listOf("Instagram")).isEmpty())
    }

    @Test
    fun `entriesFor is case insensitive and matches sub-scopes`() {
        assertEquals(1, ChangelogParser.entriesFor(multiApp, listOf("instagram")).size)
        assertEquals(1, ChangelogParser.entriesFor(entries, listOf("youtube")).size)
        // "YouTube - Hide ads" is a sub-scope of "YouTube"
        val subscoped = ChangelogParser.entriesFor(entries, listOf("YouTube"))
        assertTrue(subscoped.first().content.contains("Hide ads"))
    }

    @Test
    fun `an empty app name list leaves entries untouched`() {
        assertEquals(multiApp, ChangelogParser.entriesFor(multiApp, emptyList()))
    }

    @Test
    fun `entriesFor never mixes another app's bullets into scopedBullets either`() {
        val scoped = ChangelogParser.entriesFor(multiApp, listOf("Instagram"))
        assertEquals(setOf("Instagram"), scoped[0].scopedBullets.keys)
    }

    // ── stopAfterFirstStable ─────────────────────────────────────────────────

    @Test
    fun `stopAfterFirstStable keeps every prerelease above the first stable baseline`() {
        val devHistory = """
            ## [2.0.0-dev.3](https://x/compare/v2.0.0-dev.2...v2.0.0-dev.3) (2026-09-03)

            * **Reddit:** dev change 2

            ## [2.0.0-dev.2](https://x/compare/v2.0.0-dev.1...v2.0.0-dev.2) (2026-09-02)

            * **Reddit:** dev change 1

            ## [1.9.0](https://x/compare/v1.8.0...v1.9.0) (2026-08-01)

            * **Reddit:** last stable change

            ## [1.8.0](https://x/compare/v1.7.0...v1.8.0) (2026-07-01)

            * **Reddit:** older stable change
        """.trimIndent()

        val truncated = ChangelogParser.parse(devHistory, stopAfterFirstStable = true)
        // Both dev entries, plus the stable baseline they're built on — nothing below it
        assertEquals(listOf("2.0.0-dev.3", "2.0.0-dev.2", "1.9.0"), truncated.map { it.version })
    }

    @Test
    fun `stopAfterFirstStable has no effect when disabled`() {
        val devHistory = """
            ## [2.0.0-dev.1](https://x/compare/v1.9.0...v2.0.0-dev.1) (2026-09-01)

            * **Reddit:** dev change

            ## [1.9.0](https://x/compare/v1.8.0...v1.9.0) (2026-08-01)

            * **Reddit:** stable change

            ## [1.8.0](https://x/compare/v1.7.0...v1.8.0) (2026-07-01)

            * **Reddit:** older stable change
        """.trimIndent()

        assertEquals(3, ChangelogParser.parse(devHistory, stopAfterFirstStable = false).size)
        assertEquals(3, ChangelogParser.parse(devHistory).size)
    }
}
