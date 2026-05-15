package com.ai.assistance.operit.core.agent

/**
 * Stateful scrubber for reasoning/thinking blocks in streamed assistant text.
 * Ported from upstream hermes-agent/agent/think_scrubber.py
 *
 * State machine:
 *   - inBlock: True while inside an opened block, waiting for a close tag.
 *     All text inside is discarded.
 *   - buf: held-back partial-tag tail. Emitted/discarded on the next
 *     feed() call or by flush().
 *
 * Tag variants handled (case-insensitive):
 *   <think>, <thinking>, <reasoning>, <thought>, <REASONING_SCRATCHPAD>
 *
 * Block-boundary rule: an opening tag is only treated as a reasoning-block
 * opener when it appears at the start of the stream, after a newline,
 * or when only whitespace has been emitted on the current line.
 */
class StreamingThinkScrubber {

    companion object {
        private val OPEN_TAG_NAMES = arrayOf(
            "think", "thinking", "reasoning", "thought", "REASONING_SCRATCHPAD"
        )
        private val OPEN_TAGS = OPEN_TAG_NAMES.map { "<$it>" }
        private val CLOSE_TAGS = OPEN_TAG_NAMES.map { "</$it>" }
        private val MAX_TAG_LEN: Int = (OPEN_TAGS + CLOSE_TAGS).maxOf { it.length }
    }

    private var inBlock = false
    private var buf = ""
    private var lastEmittedEndedNewline = true

    fun reset() {
        inBlock = false
        buf = ""
        lastEmittedEndedNewline = true
    }

    /**
     * Feed one delta; return the scrubbed visible portion.
     */
    fun feed(text: String): String {
        if (text.isEmpty()) return ""
        var bufLocal = buf + text
        buf = ""
        val out = StringBuilder()

        while (bufLocal.isNotEmpty()) {
            if (inBlock) {
                val (closeIdx, closeLen) = findFirstTag(bufLocal, CLOSE_TAGS)
                if (closeIdx == -1) {
                    val held = maxPartialSuffix(bufLocal, CLOSE_TAGS)
                    buf = if (held > 0) bufLocal.takeLast(held) else ""
                    return out.toString()
                }
                bufLocal = bufLocal.substring(closeIdx + closeLen)
                inBlock = false
            } else {
                // Priority 1 — closed <tag>X</tag> pair anywhere
                val pair = findEarliestClosedPair(bufLocal)
                // Priority 2 — unterminated open tag at block boundary
                val (openIdx, openLen) = findOpenAtBoundary(bufLocal, out)

                if (pair != null && (openIdx == -1 || pair.first <= openIdx)) {
                    val preceding = bufLocal.substring(0, pair.first)
                    if (preceding.isNotEmpty()) {
                        val stripped = stripOrphanCloseTags(preceding)
                        if (stripped.isNotEmpty()) {
                            out.append(stripped)
                            lastEmittedEndedNewline = stripped.endsWith('\n')
                        }
                    }
                    bufLocal = bufLocal.substring(pair.second)
                    continue
                }

                if (openIdx != -1) {
                    val preceding = bufLocal.substring(0, openIdx)
                    if (preceding.isNotEmpty()) {
                        val stripped = stripOrphanCloseTags(preceding)
                        if (stripped.isNotEmpty()) {
                            out.append(stripped)
                            lastEmittedEndedNewline = stripped.endsWith('\n')
                        }
                    }
                    inBlock = true
                    bufLocal = bufLocal.substring(openIdx + openLen)
                    continue
                }

                // No resolvable tag — hold back partial-tag prefix
                val heldOpen = maxPartialSuffix(bufLocal, OPEN_TAGS)
                val heldClose = maxPartialSuffix(bufLocal, CLOSE_TAGS)
                val held = maxOf(heldOpen, heldClose)
                if (held > 0) {
                    val emitText = stripOrphanCloseTags(bufLocal.substring(0, bufLocal.length - held))
                    buf = bufLocal.takeLast(held)
                    if (emitText.isNotEmpty()) {
                        out.append(emitText)
                        lastEmittedEndedNewline = emitText.endsWith('\n')
                    }
                } else {
                    val emitText = stripOrphanCloseTags(bufLocal)
                    buf = ""
                    if (emitText.isNotEmpty()) {
                        out.append(emitText)
                        lastEmittedEndedNewline = emitText.endsWith('\n')
                    }
                }
                return out.toString()
            }
        }
        return out.toString()
    }

    /**
     * End-of-stream flush. If still inside an unterminated block,
     * held-back content is discarded.
     */
    fun flush(): String {
        if (inBlock) {
            buf = ""
            inBlock = false
            return ""
        }
        val tail = buf
        buf = ""
        if (tail.isEmpty()) return ""
        val stripped = stripOrphanCloseTags(tail)
        if (stripped.isNotEmpty()) {
            lastEmittedEndedNewline = stripped.endsWith('\n')
        }
        return stripped
    }

    // ── Internal helpers ──

    private fun findFirstTag(b: String, tags: List<String>): Pair<Int, Int> {
        val bLower = b.lowercase()
        var bestIdx = -1
        var bestLen = 0
        for (tag in tags) {
            val idx = bLower.indexOf(tag.lowercase())
            if (idx != -1 && (bestIdx == -1 || idx < bestIdx)) {
                bestIdx = idx
                bestLen = tag.length
            }
        }
        return Pair(bestIdx, bestLen)
    }

    private fun findEarliestClosedPair(b: String): Pair<Int, Int>? {
        val bLower = b.lowercase()
        var best: Pair<Int, Int>? = null
        for ((openTag, closeTag) in OPEN_TAGS.zip(CLOSE_TAGS)) {
            val openIdx = bLower.indexOf(openTag.lowercase())
            if (openIdx == -1) continue
            val closeIdx = bLower.indexOf(closeTag.lowercase(), openIdx + openTag.length)
            if (closeIdx == -1) continue
            val endIdx = closeIdx + closeTag.length
            if (best == null || openIdx < best.first) {
                best = Pair(openIdx, endIdx)
            }
        }
        return best
    }

    private fun findOpenAtBoundary(b: String, alreadyEmitted: StringBuilder): Pair<Int, Int> {
        val bLower = b.lowercase()
        var bestIdx = -1
        var bestLen = 0
        for (tag in OPEN_TAGS) {
            val tagLower = tag.lowercase()
            var searchStart = 0
            while (true) {
                val idx = bLower.indexOf(tagLower, searchStart)
                if (idx == -1) break
                if (isBlockBoundary(b, idx, alreadyEmitted)) {
                    if (bestIdx == -1 || idx < bestIdx) {
                        bestIdx = idx
                        bestLen = tag.length
                    }
                    break
                }
                searchStart = idx + 1
            }
        }
        return Pair(bestIdx, bestLen)
    }

    private fun isBlockBoundary(b: String, idx: Int, alreadyEmitted: StringBuilder): Boolean {
        if (idx == 0) {
            return if (alreadyEmitted.isNotEmpty()) {
                alreadyEmitted.last() == '\n'
            } else {
                lastEmittedEndedNewline
            }
        }
        val preceding = b.substring(0, idx)
        val lastNl = preceding.lastIndexOf('\n')
        return if (lastNl == -1) {
            val priorNewline = if (alreadyEmitted.isNotEmpty()) {
                alreadyEmitted.last() == '\n'
            } else {
                lastEmittedEndedNewline
            }
            priorNewline && preceding.isBlank()
        } else {
            preceding.substring(lastNl + 1).isBlank()
        }
    }

    private fun maxPartialSuffix(b: String, tags: List<String>): Int {
        if (b.isEmpty()) return 0
        val bLower = b.lowercase()
        val maxCheck = minOf(bLower.length, MAX_TAG_LEN - 1)
        for (i in maxCheck downTo 1) {
            val suffix = bLower.substring(bLower.length - i)
            for (tag in tags) {
                val tagLower = tag.lowercase()
                if (tagLower.length > i && tagLower.startsWith(suffix)) {
                    return i
                }
            }
        }
        return 0
    }

    private fun stripOrphanCloseTags(text: String): String {
        if (!text.contains("</")) return text
        val textLower = text.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            var matched = false
            if (textLower.startsWith("</", i)) {
                for (tag in CLOSE_TAGS) {
                    val tagLower = tag.lowercase()
                    val tagLen = tagLower.length
                    if (textLower.startsWith(tagLower, i)) {
                        var j = i + tagLen
                        while (j < text.length && text[j] in " \t\n\r") j++
                        i = j
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }
}