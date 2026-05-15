package com.ai.assistance.operit.core.agent

/**
 * Abstract base class for pluggable context engines.
 * Ported from upstream hermes-agent/agent/context_engine.py
 *
 * A context engine controls how conversation context is managed when
 * approaching the model's token limit. The built-in ContextCompressor
 * is the default implementation.
 *
 * Lifecycle:
 *   1. Engine is instantiated
 *   2. onSessionStart() called when a conversation begins
 *   3. updateFromResponse() called after each API response with usage data
 *   4. shouldCompress() checked after each turn
 *   5. compress() called when shouldCompress() returns true
 *   6. onSessionEnd() called at real session boundaries
 */
interface ContextEngine {

    /** Short identifier (e.g. 'compressor', 'lcm') */
    val name: String

    /** Token state — engines MUST maintain these */
    var lastPromptTokens: Int
    var lastCompletionTokens: Int
    var lastTotalTokens: Int
    var thresholdTokens: Int
    var contextLength: Int
    var compressionCount: Int

    /** Compaction parameters */
    var thresholdPercent: Float
    var protectFirstN: Int
    var protectLastN: Int

    /**
     * Update tracked token usage from an API response.
     */
    fun updateFromResponse(usage: Map<String, Any>)

    /**
     * Return true if compaction should fire this turn.
     */
    fun shouldCompress(promptTokens: Int? = null): Boolean

    /**
     * Compact the message list and return the new message list.
     *
     * @param messages Full message list in OpenAI format
     * @param currentTokens Current token count
     * @param focusTopic Optional topic from manual /compress command
     * @return Compacted message list
     */
    fun compress(
        messages: MutableList<Map<String, Any?>>,
        currentTokens: Int? = null,
        focusTopic: String? = null
    ): List<Map<String, Any?>>

    // -- Optional: pre-flight check --

    fun shouldCompressPreflight(messages: List<Map<String, Any?>>): Boolean = false
    fun hasContentToCompress(messages: List<Map<String, Any?>>): Boolean = true

    // -- Optional: session lifecycle --

    fun onSessionStart(sessionId: String, kwargs: Map<String, Any> = emptyMap()) {}
    fun onSessionEnd(sessionId: String, messages: List<Map<String, Any?>>) {}

    fun onSessionReset() {
        lastPromptTokens = 0
        lastCompletionTokens = 0
        lastTotalTokens = 0
        compressionCount = 0
    }

    // -- Optional: tools --

    fun getToolSchemas(): List<Map<String, Any>> = emptyList()
    fun handleToolCall(name: String, args: Map<String, Any>): String =
        """{"error": "Unknown context engine tool: $name"}"""

    // -- Optional: status --

    fun getStatus(): Map<String, Any> = mapOf(
        "last_prompt_tokens" to lastPromptTokens,
        "threshold_tokens" to thresholdTokens,
        "context_length" to contextLength,
        "usage_percent" to if (contextLength > 0)
            minOf(100.0, lastPromptTokens.toDouble() / contextLength * 100) else 0.0,
        "compression_count" to compressionCount
    )

    // -- Optional: model switch --

    fun updateModel(model: String, contextLength: Int, baseUrl: String = "", apiKey: String = "", provider: String = "") {
        this.contextLength = contextLength
        this.thresholdTokens = (contextLength * thresholdPercent).toInt()
    }
}