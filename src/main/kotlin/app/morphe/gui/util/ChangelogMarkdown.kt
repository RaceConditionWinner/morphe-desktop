/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.util

/**
 * The smallest structure of one changelog entry's Markdown that per-app filtering needs:
 * sections split by heading, and inside them list items (with their nested items and
 * continuation lines) and plain text blocks (paragraphs, code fences).
 *
 * Every node keeps its original raw lines, so whatever survives a filter is emitted
 * byte-for-byte as it was written: inline formatting, links, nested lists, numbered
 * lists and fenced code are never re-interpreted or rewritten, only kept or dropped.
 */
internal object ChangelogMarkdown {

    /** A list item, with everything indented beneath it, in original order. */
    class Item(val indent: Int, val head: String) {
        val parts = mutableListOf<Part>()

        /** The app scope of the head line (`* **Scope:** text`), or null when unscoped. */
        val scope: String? = SCOPE_RE.find(head.trim())?.groupValues?.get(1)

        val children: List<Item> get() = parts.filterIsInstance<Part.Child>().map { it.item }

        /** True when this item or any item beneath it names a scope. */
        fun hasScopedDescendant(): Boolean =
            scope != null || children.any { it.hasScopedDescendant() }
    }

    sealed interface Part {
        data class Raw(val line: String) : Part
        data class Child(val item: Item) : Part
    }

    sealed interface Node {
        data class Text(val lines: List<String>) : Node
        data class Bullet(val item: Item) : Node
    }

    /** One heading (null for content before any heading) and what sits under it. */
    class Section(val heading: String?, val nodes: List<Node>)

    private val HEADING_RE = Regex("""^\s{0,3}#{1,6}\s""")
    private val ITEM_RE = Regex("""^(\s*)(?:[*+-]|\d+[.)])\s+\S""")
    private val FENCE_RE = Regex("""^\s*(```|~~~)""")

    /** Bold scope prefix as conventional-changelog emits it: `**Scope:**`, colon inside the bold. */
    val SCOPE_RE = Regex("""^(?:[*+-]|\d+[.)])\s+\*\*(.+?):\*\*""")

    fun parse(content: String): List<Section> {
        val lines = content.lines()
        val sections = mutableListOf<Section>()
        var heading: String? = null
        var nodes = mutableListOf<Node>()
        var sawAnything = false

        fun closeSection() {
            if (heading != null || nodes.isNotEmpty()) sections += Section(heading, nodes)
            nodes = mutableListOf()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.isBlank() -> i++
                HEADING_RE.containsMatchIn(line) -> {
                    closeSection()
                    heading = line
                    sawAnything = true
                    i++
                }
                ITEM_RE.containsMatchIn(line) -> {
                    val (item, next) = parseItem(lines, i)
                    nodes += Node.Bullet(item)
                    sawAnything = true
                    i = next
                }
                else -> {
                    val (block, next) = parseTextBlock(lines, i)
                    nodes += Node.Text(block)
                    sawAnything = true
                    i = next
                }
            }
        }
        closeSection()
        return if (sawAnything) sections else emptyList()
    }

    /** Consumes a paragraph or fenced code block starting at [start]. */
    private fun parseTextBlock(lines: List<String>, start: Int): Pair<List<String>, Int> {
        val block = mutableListOf<String>()
        var i = start
        if (FENCE_RE.containsMatchIn(lines[i])) {
            val marker = FENCE_RE.find(lines[i])!!.groupValues[1]
            block += lines[i++]
            while (i < lines.size) {
                block += lines[i]
                if (lines[i].trimStart().startsWith(marker)) { i++; break }
                i++
            }
            return block to i
        }
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank() || HEADING_RE.containsMatchIn(l) || ITEM_RE.containsMatchIn(l) ||
                FENCE_RE.containsMatchIn(l)
            ) break
            block += l
            i++
        }
        return block to i
    }

    /**
     * Consumes the list item starting at [start] together with everything that belongs to it:
     * deeper-indented items (its children), indented continuation text or code, lazy
     * continuation lines directly following it, and blank lines that sit *inside* it.
     */
    private fun parseItem(lines: List<String>, start: Int): Pair<Item, Int> {
        val head = lines[start]
        val indent = head.indentWidth()
        val item = Item(indent, head)
        var i = start + 1
        var lastWasBlank = false
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                // A blank line stays inside the item only when what follows is still beneath it
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() }
                if (next != null && next.indentWidth() > indent && !HEADING_RE.containsMatchIn(next)) {
                    item.parts += Part.Raw(line)
                    lastWasBlank = true
                    i++
                    continue
                }
                break
            }
            if (HEADING_RE.containsMatchIn(line)) break
            val lineIndent = line.indentWidth()
            if (ITEM_RE.containsMatchIn(line)) {
                if (lineIndent > indent) {
                    val (child, next) = parseItem(lines, i)
                    item.parts += Part.Child(child)
                    i = next
                    lastWasBlank = false
                    continue
                }
                break // sibling or shallower item ends this one
            }
            if (lineIndent > indent) {
                if (FENCE_RE.containsMatchIn(line)) {
                    val (block, next) = parseTextBlock(lines, i)
                    block.forEach { item.parts += Part.Raw(it) }
                    i = next
                } else {
                    item.parts += Part.Raw(line)
                    i++
                }
                lastWasBlank = false
                continue
            }
            // Lazy continuation: unindented text glued to the item with no blank line between
            if (!lastWasBlank) {
                item.parts += Part.Raw(line)
                i++
                continue
            }
            break
        }
        // Blank lines belong to the gap after the item, not to it
        while (item.parts.lastOrNull().let { it is Part.Raw && it.line.isBlank() }) {
            item.parts.removeAt(item.parts.lastIndex)
        }
        return item to i
    }

    private fun String.indentWidth(): Int = takeWhile { it == ' ' || it == '\t' }
        .sumOf { if (it == '\t') 4 else 1 }

    // ─── Rendering ───────────────────────────────────────────────────────────

    fun render(sections: List<Section>): String {
        val out = mutableListOf<String>()
        for (section in sections) {
            if (out.isNotEmpty()) out += ""
            section.heading?.let {
                out += it
                if (section.nodes.isNotEmpty()) out += ""
            }
            var previousWasBullet = false
            section.nodes.forEachIndexed { index, node ->
                when (node) {
                    is Node.Text -> {
                        if (index > 0) out += ""
                        out += node.lines
                        previousWasBullet = false
                    }
                    is Node.Bullet -> {
                        if (index > 0 && !previousWasBullet) out += ""
                        renderItem(node.item, out)
                        previousWasBullet = true
                    }
                }
            }
        }
        return out.joinToString("\n")
    }

    private fun renderItem(item: Item, out: MutableList<String>) {
        out += item.head
        for (part in item.parts) when (part) {
            is Part.Raw -> out += part.line
            is Part.Child -> renderItem(part.item, out)
        }
    }

    // ─── Filtering ───────────────────────────────────────────────────────────

    /** True when [scope] names the app exactly or as one of its sub-scopes. */
    fun scopeMatches(scope: String, appNames: Collection<String>): Boolean = appNames.any { name ->
        scope.equals(name, ignoreCase = true) || scope.startsWith("$name - ", ignoreCase = true)
    }

    /**
     * Narrows [content] to one app.
     *
     * - An item scoped to the app is kept whole, nested items and continuation lines included.
     * - An item scoped to another app is dropped whole.
     * - An unscoped item that has scoped items beneath it is kept only for the scoped items
     *   that match, with its own line as their context.
     * - Everything unscoped (bullets with no scope, paragraphs, code) is *general*: gathered,
     *   still in its original formatting, under [generalHeading] after the app's own changes,
     *   or left out when [generalHeading] is null.
     *
     * A heading survives only when something beneath it does.
     */
    fun filterForApp(content: String, appNames: Collection<String>, generalHeading: String?): String {
        val kept = mutableListOf<Section>()
        val general = mutableListOf<Node>()

        for (section in parse(content)) {
            val keptNodes = mutableListOf<Node>()
            for (node in section.nodes) when (node) {
                is Node.Text -> general += node
                is Node.Bullet -> {
                    val item = node.item
                    when {
                        item.scope != null ->
                            if (scopeMatches(item.scope, appNames)) keptNodes += node
                        !item.hasScopedDescendant() -> general += node
                        else -> pruneUnscoped(item, appNames)?.let { keptNodes += Node.Bullet(it) }
                    }
                }
            }
            if (keptNodes.isNotEmpty()) kept += Section(section.heading, keptNodes)
        }

        if (generalHeading != null && general.isNotEmpty()) {
            kept += Section("### $generalHeading", general)
        }
        return render(kept)
    }

    /** An unscoped item keeping only its matching scoped descendants, or null when none match. */
    private fun pruneUnscoped(item: Item, appNames: Collection<String>): Item? {
        val copy = Item(item.indent, item.head)
        for (part in item.parts) when (part) {
            is Part.Raw -> copy.parts += part
            is Part.Child -> {
                val child = part.item
                val survivor = when {
                    child.scope != null -> child.takeIf { scopeMatches(child.scope, appNames) }
                    child.hasScopedDescendant() -> pruneUnscoped(child, appNames)
                    else -> null // unscoped leaf beside scoped siblings is general noise here
                }
                if (survivor != null) copy.parts += Part.Child(survivor)
            }
        }
        return copy.takeIf { it.children.isNotEmpty() }
    }

    /** Every scoped bullet body at any depth, keyed by scope. */
    fun scopedBullets(content: String): Map<String, List<String>> {
        val scoped = mutableMapOf<String, MutableList<String>>()
        fun visit(item: Item) {
            item.scope?.let { scope ->
                val match = SCOPE_RE.find(item.head.trim())!!
                scoped.getOrPut(scope) { mutableListOf() }
                    .add(item.head.trim().substring(match.value.length).trim())
            }
            item.children.forEach(::visit)
        }
        parse(content).forEach { section ->
            section.nodes.filterIsInstance<Node.Bullet>().forEach { visit(it.item) }
        }
        return scoped
    }
}
