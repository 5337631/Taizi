package com.ai.assistance.operit.core.agent

import android.util.Log
import com.xiaomo.hermes.hermes.ToolResultPersister

/**
 * Connects [BudgetConfig] and [ToolResultPruner] to [ToolResultPersister].
 *
 * Single-tool-result truncation: when a tool result exceeds the per-tool
 * budget, it gets truncated to a preview + persisted to disk.
 *
 * Multi-turn pruning: call [pruneOldResults] via beforeNextTurn to replace
 * old tool results with 1-line summaries.
 */
class BudgetToolResultPersister(
    private val budget: BudgetConfig.Budget = BudgetConfig.DEFAULT
) : ToolResultPersister {

    private val largeResults = mutableMapOf<String, String>() // toolUseId -> full content

    /**
     * Called per tool result after execution.
     * If result exceeds threshold, truncate and store full version.
     */
    override fun maybePersist(content: String, toolName: String, toolUseId: String): String {
        val threshold = budget.resolveThreshold(toolName)
        if (threshold == Double.POSITIVE_INFINITY) return content // never truncate (e.g. read_file)

        val resultSize = content.length.toDouble()
        if (resultSize <= threshold) return content

        // Persist full content to disk
        try {
            val dir = java.io.File("/sdcard/Download/Hermes/tool_results")
            dir.mkdirs()
            val file = java.io.File(dir, "${toolUseId.take(16)}.txt")
            file.writeText(content, Charsets.UTF_8)
            Log.i(TAG, "Persisted tool result: ${toolUseId.take(16)}.txt (${content.length} chars)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist tool result: ${e.message}")
        }

        // Return truncated preview
        val preview = budget.previewText(content)
        Log.i(TAG, "Truncated $toolName result: ${content.length} -> ${preview.length} chars")
        return preview
    }

    companion object {
        private const val TAG = "BudgetPersister"
    }
}