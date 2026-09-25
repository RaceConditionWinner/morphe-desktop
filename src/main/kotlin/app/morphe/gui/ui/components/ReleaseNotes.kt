/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.morphe.gui.ui.theme.LocalMorpheAccents
import app.morphe.gui.ui.theme.LocalMorpheCorners
import app.morphe.gui.ui.theme.LocalMorpheFont
import app.morphe.gui.util.ChangelogMarkdown

/**
 * Renders changelog Markdown as [ChangelogMarkdown] parses it: headings, list items at their
 * original nesting (bulleted or numbered, matching the source marker), paragraphs and fenced
 * code — with inline `**bold**`, `` `code` `` and `[text](url)` spans kept as real formatting
 * rather than stripped to plain text, so a linked PR or a scope label still reads as one.
 *
 * Unlike the old line-by-line stripper, nothing here discards structure: a numbered list stays
 * numbered, an item's continuation lines stay attached to it, and a fenced code block keeps its
 * own monospaced block instead of being read as prose.
 */
@Composable
fun FormattedReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val font = LocalMorpheFont.current
    val sections = remember(markdown) { ChangelogMarkdown.parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEachIndexed { index, section ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                    thickness = 1.dp,
                )
            }
            section.heading?.let { HeadingText(it, font) }
            section.nodes.forEach { node ->
                when (node) {
                    is ChangelogMarkdown.Node.Text -> TextBlock(node.lines, font)
                    is ChangelogMarkdown.Node.Bullet -> ItemRow(node.item, depth = 0, font = font)
                }
            }
        }
    }
}

@Composable
private fun HeadingText(raw: String, font: FontFamily) {
    val level = raw.takeWhile { it == '#' }.length.coerceIn(1, 6)
    val text = raw.trimStart('#', ' ')
    Text(
        text = inlineSpans(text, MaterialTheme.colorScheme.onSurface),
        fontSize = if (level <= 2) 12.sp else 11.sp,
        fontWeight = if (level <= 2) FontWeight.Bold else FontWeight.SemiBold,
        fontFamily = font,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun TextBlock(lines: List<String>, font: FontFamily) {
    val isCode = lines.firstOrNull()?.trimStart()?.let { it.startsWith("```") || it.startsWith("~~~") } == true
    if (isCode) {
        val body = lines.drop(1).dropLast(if (lines.size > 1 && lines.last().isFenceClose()) 1 else 0)
        CodeBlock(body.joinToString("\n"), font)
    } else {
        Text(
            text = inlineSpans(lines.joinToString(" ") { it.trim() }, MaterialTheme.colorScheme.onSurfaceVariant),
            fontSize = 11.sp,
            fontFamily = font,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun ItemRow(item: ChangelogMarkdown.Item, depth: Int, font: FontFamily) {
    // Keep the source's own bullet vs. numeral so a numbered list still counts as one.
    val markerMatch = Regex("""^(?:([*+-])|(\d+)[.)])\s""").find(item.head.trimStart())
    val markerText = markerMatch?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.let { "$it." } ?: "•"
    val headText = item.head.trimStart().replaceFirst(Regex("""^(?:[*+-]|\d+[.)])\s+"""), "")

    Column {
        Row {
            Spacer(modifier = Modifier.width((depth * 14).dp))
            Text(
                text = markerText,
                modifier = Modifier.alignByBaseline(),
                fontSize = 11.sp,
                fontFamily = font,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = inlineSpans(headText, MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.weight(1f).alignByBaseline(),
                fontSize = 11.sp,
                fontFamily = font,
                lineHeight = 17.sp,
            )
        }
        val continuation = item.parts.filterIsInstance<ChangelogMarkdown.Part.Raw>()
            .map { it.line }.filter { it.isNotBlank() }
        if (continuation.isNotEmpty()) {
            Text(
                text = inlineSpans(continuation.joinToString(" ") { it.trim() }, MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(start = ((depth + 1) * 14 + 12).dp, top = 2.dp),
                fontSize = 11.sp,
                fontFamily = font,
                lineHeight = 17.sp,
            )
        }
        item.children.forEach { child -> ItemRow(child, depth = depth + 1, font = font) }
    }
}

@Composable
private fun CodeBlock(code: String, font: FontFamily) {
    val corners = LocalMorpheCorners.current
    Surface(
        shape = RoundedCornerShape(corners.small),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = code,
            modifier = Modifier.padding(PaddingValues(horizontal = 10.dp, vertical = 8.dp)),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 15.sp,
        )
    }
}

private fun String.isFenceClose() = trimStart().let { it.startsWith("```") || it.startsWith("~~~") }

/**
 * Renders `**bold**`, `` `code` `` and `[text](url)` as real spans instead of stripping them,
 * so a scope prefix stays visually bold and a linked commit or PR stays clickable. Anything
 * else (raw text, unmatched markers) passes through unchanged.
 */
@Composable
private fun inlineSpans(text: String, baseColor: Color): AnnotatedString {
    // LocalMorpheAccents.current is @Composable/@ReadOnlyComposable, so it must be read out
    // here — buildAnnotatedString's builder lambda below is a plain, non-composable lambda.
    val accent = LocalMorpheAccents.current.primary
    return buildAnnotatedString {
        var i = 0
        val boldRe = Regex("""\*\*(.+?)\*\*""")
        val codeRe = Regex("""`([^`]+)`""")
        val linkRe = Regex("""\[([^\]]*)]\(([^)]+)\)""")
        val tokenRe = Regex("""${boldRe.pattern}|${codeRe.pattern}|${linkRe.pattern}""")

        tokenRe.findAll(text).forEach { match ->
            if (match.range.first > i) append(text.substring(i, match.range.first))
            when {
                match.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                    append(match.groupValues[1])
                }
                match.groups[2] != null -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = baseColor.copy(alpha = 0.08f))
                ) { append(match.groupValues[2]) }
                else -> withLink(
                    LinkAnnotation.Url(
                        match.groupValues[4],
                        TextLinkStyles(style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)),
                    )
                ) { append(match.groupValues[3]) }
            }
            i = match.range.last + 1
        }
        if (i < text.length) append(text.substring(i))
    }
}
