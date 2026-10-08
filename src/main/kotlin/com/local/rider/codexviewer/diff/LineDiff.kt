package com.local.rider.codexviewer.diff

/** A contiguous replacement in line coordinates, with the removed and added lines preserved. */
data class ChangeHunk(
    val oldStartLine: Int,
    val oldEndLine: Int,
    val newStartLine: Int,
    val newEndLine: Int,
    val oldLines: List<String>,
    val newLines: List<String>,
) {
    val key = "$oldStartLine:$oldEndLine:$newStartLine:$newEndLine:${oldLines.hashCode()}:${newLines.hashCode()}"
}

/** Small line-based diff for editor review; huge comparisons degrade to one safe hunk. */
object LineDiff {
    private const val MAX_LCS_CELLS = 2_000_000L

    fun compute(before: String, after: String): List<ChangeHunk> {
        if (before == after) return emptyList()
        val oldLines = before.split('\n')
        val newLines = after.split('\n')
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++

        var oldEnd = oldLines.size
        var newEnd = newLines.size
        while (oldEnd > prefix && newEnd > prefix && oldLines[oldEnd - 1] == newLines[newEnd - 1]) {
            oldEnd--
            newEnd--
        }

        val oldMiddle = oldLines.subList(prefix, oldEnd)
        val newMiddle = newLines.subList(prefix, newEnd)
        if (oldMiddle.size.toLong() * newMiddle.size > MAX_LCS_CELLS) {
            return listOf(hunk(prefix, oldEnd, prefix, newEnd, oldLines, newLines))
        }

        val lcs = Array(oldMiddle.size + 1) { IntArray(newMiddle.size + 1) }
        for (oldIndex in oldMiddle.indices.reversed()) {
            for (newIndex in newMiddle.indices.reversed()) {
                lcs[oldIndex][newIndex] = if (oldMiddle[oldIndex] == newMiddle[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<ChangeHunk>()
        var oldIndex = 0
        var newIndex = 0
        var hunkOldStart = -1
        var hunkNewStart = -1
        fun flush() {
            if (hunkOldStart < 0) return
            result += hunk(prefix + hunkOldStart, prefix + oldIndex, prefix + hunkNewStart, prefix + newIndex, oldLines, newLines)
            hunkOldStart = -1
            hunkNewStart = -1
        }

        while (oldIndex < oldMiddle.size || newIndex < newMiddle.size) {
            if (oldIndex < oldMiddle.size && newIndex < newMiddle.size && oldMiddle[oldIndex] == newMiddle[newIndex]) {
                flush()
                oldIndex++
                newIndex++
            } else {
                if (hunkOldStart < 0) {
                    hunkOldStart = oldIndex
                    hunkNewStart = newIndex
                }
                if (newIndex >= newMiddle.size ||
                    oldIndex < oldMiddle.size && lcs[oldIndex + 1][newIndex] >= lcs[oldIndex][newIndex + 1]
                ) oldIndex++ else newIndex++
            }
        }
        flush()
        return result
    }

    fun replaceLines(text: String, startLine: Int, endLine: Int, replacement: String): String {
        val range = lineRange(text, startLine, endLine)
        return text.replaceRange(range.start, range.end, replacement)
    }

    fun lineSlice(text: String, startLine: Int, endLine: Int): String =
        lineRange(text, startLine, endLine).let { text.substring(it.start, it.end) }

    private fun lineStartOffset(text: String, line: Int): Int {
        if (line <= 0) return 0
        var currentLine = 0
        text.forEachIndexed { index, character ->
            if (character == '\n' && ++currentLine == line) return index + 1
        }
        return text.length
    }

    /**
     * A non-empty replacement that reaches EOF owns the preceding newline. This keeps appending
     * a line lossless when an empty old-side range is accepted or restored.
     */
    private fun lineRange(text: String, startLine: Int, endLine: Int): CharacterRange {
        var start = lineStartOffset(text, startLine)
        val end = lineStartOffset(text, endLine)
        val lineCount = text.count { it == '\n' } + 1
        if (startLine < endLine && endLine >= lineCount && startLine > 0 && start > 0 && text[start - 1] == '\n') {
            start--
        }
        return CharacterRange(start, end)
    }

    private fun hunk(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        oldLines: List<String>,
        newLines: List<String>,
    ) = ChangeHunk(oldStart, oldEnd, newStart, newEnd, oldLines.subList(oldStart, oldEnd).toList(), newLines.subList(newStart, newEnd).toList())

    private data class CharacterRange(val start: Int, val end: Int)
}
