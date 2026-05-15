package com.ai.assistance.operit.core.agent

/**
 * Configurable budget constants for tool result persistence.
 * Ported from upstream hermes-agent/tools/budget_config.py
 *
 * 3-layer tool result persistence system:
 *   Layer 2 (per-result): resolveThreshold(toolName) -> threshold in chars
 *   Layer 3 (per-turn):   turnBudget -> aggregate char budget across all tools
 *   Preview:              previewSize -> inline snippet size after persistence
 */
object BudgetConfig {

    // Pinned thresholds — tools whose thresholds must never be overridden.
    // read_file is no longer pinned to infinity; it now uses the toolOverrides
    // from the caller (e.g. 16KB in HermesAdapter/EnhancedAIService) to prevent
    // context bloat from large file reads.
    private val PINNED_THRESHOLDS = mapOf<String, Double>()

    const val DEFAULT_RESULT_SIZE_CHARS = 100_000
    const val DEFAULT_TURN_BUDGET_CHARS = 200_000
    const val DEFAULT_PREVIEW_SIZE_CHARS = 1_500

    /**
     * Budget configuration.
     */
    data class Budget(
        val defaultResultSize: Int = DEFAULT_RESULT_SIZE_CHARS,
        val turnBudget: Int = DEFAULT_TURN_BUDGET_CHARS,
        val previewSize: Int = DEFAULT_PREVIEW_SIZE_CHARS,
        val toolOverrides: Map<String, Int> = emptyMap()
    ) {
        /**
         * Resolve the persistence threshold for a tool.
         * Priority: pinned -> toolOverrides -> default
         */
        fun resolveThreshold(toolName: String): Double {
            val pinned = PINNED_THRESHOLDS[toolName]
            if (pinned != null) return pinned
            val override = toolOverrides[toolName]
            if (override != null) return override.toDouble()
            return defaultResultSize.toDouble()
        }

        /**
         * Check if a tool result should be persisted (exceeds threshold).
         */
        fun shouldPersist(toolName: String, resultSizeChars: Int): Boolean {
            val threshold = resolveThreshold(toolName)
            return resultSizeChars > threshold
        }

        /**
         * Calculate preview text (truncated result for inline display).
         */
        fun previewText(result: String): String {
            if (result.length <= previewSize) return result
            return result.take(previewSize) + "...[truncated]"
        }
    }

    /** Default budget instance. */
    val DEFAULT = Budget()

    /**
     * Parse budget from a config map.
     */
    fun fromConfig(config: Map<String, Any?>): Budget {
        val defaultResultSize = (config["default_result_size"] as? Number)?.toInt()
            ?: DEFAULT_RESULT_SIZE_CHARS
        val turnBudget = (config["turn_budget"] as? Number)?.toInt()
            ?: DEFAULT_TURN_BUDGET_CHARS
        val previewSize = (config["preview_size"] as? Number)?.toInt()
            ?: DEFAULT_PREVIEW_SIZE_CHARS
        val toolOverrides = mutableMapOf<String, Int>()
        @Suppress("UNCHECKED_CAST")
        val overrides = config["tool_overrides"] as? Map<String, Any>
        if (overrides != null) {
            for ((key, value) in overrides) {
                (value as? Number)?.toInt()?.let { toolOverrides[key] = it }
            }
        }
        return Budget(defaultResultSize, turnBudget, previewSize, toolOverrides)
    }
}