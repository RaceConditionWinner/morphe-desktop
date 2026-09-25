/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

data class ChangelogEntry(
    val version: String,
    val date: String?,
    val content: String,
    val scopedBullets: Map<String, List<String>> = emptyMap(),
)

val ChangelogEntry.isPrerelease: Boolean get() = version.contains('-')

object ChangelogParser {

    private val VERSION_HEADING = Regex(
        """^#{1,3}\s+(?:\S+\s+)?(?:\[([^]]+)]\([^)]*\)|([^\s\[(]+))\s+\((\d{4}-\d{2}-\d{2})\)""",
        RegexOption.IGNORE_CASE,
    )

    private val VERSION_SHAPE = Regex("""^\d+(\.\d+)*""")

    private val EXPERIMENTAL_VERSION_ADDITION_RE = Regex(
        """^Add(?:ed)?\s+experimental\s+support\s+for\b""",
        RegexOption.IGNORE_CASE,
    )

    private val COMMIT_LINK_REGEX = Regex("""\s*\(\[([0-9a-f]{7,})]\([^)]+/commit/[^)]+\)\)""")

    private fun String.sanitizeContent(): String = replace(COMMIT_LINK_REGEX, "").trimEnd()

    private fun resolveScopedBullets(content: String): Map<String, List<String>> =
        ChangelogMarkdown.scopedBullets(content)

    /**
     * Parse raw CHANGELOG.md text into a list of [ChangelogEntry], ordered
     * newest-first (same order as in the file).
     *
     * When [stopAfterFirstStable] is true, parsing stops as soon as the first
     * stable release (no pre-release suffix) has been collected and the next
     * heading is reached, skipping the rest of the file. A dev-branch changelog
     * accumulates every historical entry below the last stable baseline, which
     * this keeps out of what a stable-channel source ever sees.
     */
    fun parse(markdown: String, stopAfterFirstStable: Boolean = false): List<ChangelogEntry> {
        val entries = mutableListOf<ChangelogEntry>()

        var currentVersion: String? = null
        var currentDate: String? = null
        val currentContent = StringBuilder()

        fun flush() {
            val v = currentVersion ?: return
            val raw = currentContent.toString()
            entries += ChangelogEntry(
                version = v,
                date = currentDate,
                content = raw.sanitizeContent(),
                scopedBullets = resolveScopedBullets(raw),
            )
        }

        for (line in markdown.lines()) {
            val match = VERSION_HEADING.find(line)
            if (match != null) {
                flush()
                if (stopAfterFirstStable && entries.lastOrNull()?.isPrerelease == false) {
                    currentVersion = null
                    break
                }
                currentVersion = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
                currentDate = match.groupValues[3]
                currentContent.clear()
            } else if (currentVersion != null) {
                currentContent.appendLine(line)
            }
        }
        flush()

        return entries
    }

    /**
     * The version [entriesNewerThan] measures from, or null when there is no usable baseline:
     * blank, "unknown" or something that is not a version at all. A null baseline means "no
     * baseline", never "everything is newer than nothing-valid".
     */
    fun usableBaseline(version: String?): String? {
        val v = version?.normalizeVersion()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.equals("unknown", ignoreCase = true)) return null
        return v.takeIf { VERSION_SHAPE.containsMatchIn(it) }
    }

    /**
     * Entries strictly newer than [installedVersion]. With no usable baseline this is every
     * entry (Manager's behaviour), and with a usable one that is already the newest it is
     * empty: the latest release is never passed off as new.
     */
    fun entriesNewerThan(
        entries: List<ChangelogEntry>,
        installedVersion: String?,
    ): List<ChangelogEntry> {
        val baseline = usableBaseline(installedVersion) ?: return entries
        val baselineDate = findVersion(entries, baseline)?.date
        return entries.filter { entry ->
            isNewerVersion(entry.version, baseline) &&
                (baselineDate == null || entry.date == null || entry.date >= baselineDate)
        }
    }

    fun hasChangesFor(
        entries: List<ChangelogEntry>,
        installedVersion: String?,
        appNames: Collection<String>,
    ): Boolean {
        if (appNames.isEmpty()) return false
        return entriesNewerThan(entries, installedVersion).any { it.hasChangesFor(appNames) }
    }

    /**
     * Narrows [entries] to one app, dropping entries with no substantive bullet for
     * it. Kept entries hold its bullets and, under [generalHeading] when it is
     * non-null, the bullets scoped to no app at all.
     *
     * This is what makes "What's New" for one app actually show only that app: the
     * markdown content itself is rebuilt from the matching bullets, not merely
     * decided whether to show — rendering the untouched [ChangelogEntry.content]
     * for a version that also touched other apps would show their bullets too.
     */
    fun entriesFor(
        entries: List<ChangelogEntry>,
        appNames: Collection<String>,
        generalHeading: String? = null,
    ): List<ChangelogEntry> {
        if (appNames.isEmpty()) return entries
        return entries
            .filter { it.hasChangesFor(appNames) }
            .map { entry ->
                entry.copy(
                    content = ChangelogMarkdown.filterForApp(entry.content, appNames, generalHeading),
                    scopedBullets = entry.scopedBullets.filterKeys { ChangelogMarkdown.scopeMatches(it, appNames) },
                )
            }
    }

    /** True when the app has a bullet here that is more than bookkeeping. */
    private fun ChangelogEntry.hasChangesFor(appNames: Collection<String>) =
        scopedBullets.any { (scope, bullets) ->
            ChangelogMarkdown.scopeMatches(scope, appNames) &&
                bullets.any { !EXPERIMENTAL_VERSION_ADDITION_RE.containsMatchIn(it) }
        }

    fun findVersion(entries: List<ChangelogEntry>, version: String): ChangelogEntry? {
        val normalized = version.normalizeVersion()
        return entries.firstOrNull { it.version.normalizeVersion() == normalized }
    }
}
