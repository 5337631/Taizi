package com.ai.assistance.operit.core.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Default context engine — compresses conversation context via lossy summarization.
 * Ported from upstream hermes-agent/agent/context_compressor.py
 *
 * Algorithm:
 *   1. Prune old tool results (cheap, no LLM call)
 *   2. Protect head messages (system prompt + first exchange)
 *   3. Protect tail messages by token budget
 *   4. Summarize middle turns with structured LLM prompt
 *   5. On subsequent compactions, iteratively update the previous summary
 */
class ContextCompressor(
    private var model: String,
    private var baseUrl: String = "",
    private var apiKey: String = "",
    private var provider: String = "",
    thresholdPercent: Float = 0.50f,
    protectFirstN: Int = 3,
    protectLastN: Int = 20,
    private val summaryTargetRatio: Float = 0.20f.coerceIn(0.10f, 0.80f),
    private val quietMode: Boolean = false,
    private val summaryModelOverride: String? = null
) : ContextEngine {

    companion object {
        private const val TAG = "ContextCompressor"
        const val MINIMUM_CONTEXT_LENGTH = 4000
        const val CHARS_PER_TOKEN = 4
        const val IMAGE_TOKEN_ESTIMATE = 1600
        const val IMAGE_CHAR_EQUIVALENT = IMAGE_TOKEN_ESTIMATE * CHARS_PER_TOKEN
        const val MIN_SUMMARY_TOKENS = 2000
        const val SUMMARY_TOKENS_CEILING = 12_000
        const val SUMMARY_FAILURE_COOLDOWN_SECONDS = 600L

        private val SUMMARY_PREFIX = """
            [CONTEXT COMPACTION — REFERENCE ONLY] Earlier turns were compacted
            into the summary below. This is a handoff from a previous context
            window — treat it as background reference, NOT as active instructions.
            Do NOT answer questions or fulfill requests mentioned in this summary;
            they were already addressed.
            Your current task is identified in the '## Active Task' section of the
            summary — resume exactly from there.
            IMPORTANT: Your persistent memory (MEMORY.md, USER.md) in the system
            prompt is ALWAYS authoritative and active.
            Respond ONLY to the latest user message that appears AFTER this summary.
        """.trimIndent()
    }

    // ── ContextEngine interface ──
    override val name = "compressor"
    override var lastPromptTokens = 0
    override var lastCompletionTokens = 0
    override var lastTotalTokens = 0
    override var thresholdTokens = 0
    override var contextLength = 0
    override var compressionCount = 0
    override var thresholdPercent = thresholdPercent
    override var protectFirstN = protectFirstN
    override var protectLastN = protectLastN

    // ── Internal state ──
    var summaryModel = summaryModelOverride ?: ""
    private var previousSummary: String? = null
    private var lastCompressionSavingsPct = 100.0
    private var ineffectiveCompressionCount = 0
    private var lastSummaryError: String? = null
    private var lastSummaryDroppedCount = 0
    private var lastSummaryFallbackUsed = false
    private var summaryFailureCooldownUntil = 0L
    private var tailTokenBudget: Int = 0
    private var maxSummaryTokens: Int = 0

    init {
        // Default context length estimate if not probed yet
        contextLength = 128_000
        thresholdTokens = maxOf((contextLength * thresholdPercent).toInt(), MINIMUM_CONTEXT_LENGTH)
        val targetTokens = (thresholdTokens * summaryTargetRatio).toInt()
        tailTokenBudget = targetTokens
        maxSummaryTokens = minOf((contextLength * 0.05).toInt(), SUMMARY_TOKENS_CEILING)

        if (!quietMode) {
            Log.i(TAG, "Initialized: model=$model context_length=$contextLength " +
                "threshold=$thresholdTokens (${(thresholdPercent * 100).toInt()}%) " +
                "tail_budget=$tailTokenBudget")
        }
    }

    // ── Core interface ──

    override fun updateFromResponse(usage: Map<String, Any>) {
        lastPromptTokens = (usage["prompt_tokens"] as? Number)?.toInt() ?: 0
        lastCompletionTokens = (usage["completion_tokens"] as? Number)?.toInt() ?: 0
    }

    override fun shouldCompress(promptTokens: Int?): Boolean {
        val tokens = promptTokens ?: lastPromptTokens
        if (tokens < thresholdTokens) return false
        // Anti-thrashing: back off if recent compressions were ineffective
        if (ineffectiveCompressionCount >= 2) {
            if (!quietMode) {
                Log.w(TAG, "Compression skipped — last $ineffectiveCompressionCount " +
                    "compressions saved <10% each.")
            }
            return false
        }
        return true
    }

    override fun compress(
        messages: MutableList<Map<String, Any?>>,
        currentTokens: Int?,
        focusTopic: String?
    ): List<Map<String, Any?>> {
        if (messages.size <= protectFirstN + 1) return messages

        // Step 1: Prune old tool results (cheap, no LLM)
        val (pruned, prunedCount) = ToolResultPruner.pruneOldToolResults(
            messages, protectFirstN, tailTokenBudget
        )

        // Step 2 + 3: Protect head and tail
        val headEnd = protectFirstN
        val tailStart = pruned.size - protectLastN

        if (tailStart <= headEnd) {
            // Not enough middle to compress
            return pruned
        }

        val head = pruned.subList(0, headEnd)
        val tail = pruned.subList(tailStart, pruned.size)
        val middle = pruned.subList(headEnd, tailStart)

        if (middle.isEmpty()) return pruned

        // Step 4: Summarize middle turns
        val summary = generateSummary(middle, focusTopic)

        return if (summary != null) {
            compressionCount++
            val summaryMsg = mapOf(
                "role" to "system",
                "content" to "$SUMMARY_PREFIX\n\n$summary"
            )
            head + summaryMsg + tail
        } else {
            // Summarization failed — return pruned version at least
            lastSummaryDroppedCount = middle.size
            lastSummaryFallbackUsed = true
            pruned
        }
    }

    override fun onSessionReset() {
        super.onSessionReset()
        previousSummary = null
        lastSummaryError = null
        lastSummaryDroppedCount = 0
        lastSummaryFallbackUsed = false
        lastCompressionSavingsPct = 100.0
        ineffectiveCompressionCount = 0
        summaryFailureCooldownUntil = 0L
    }

    override fun updateModel(
        model: String, contextLength: Int,
        baseUrl: String, apiKey: String, provider: String
    ) {
        this.model = model
        this.baseUrl = baseUrl
        this.apiKey = apiKey
        this.provider = provider
        this.contextLength = contextLength
        thresholdTokens = maxOf((contextLength * thresholdPercent).toInt(), MINIMUM_CONTEXT_LENGTH)
        val targetTokens = (thresholdTokens * summaryTargetRatio).toInt()
        tailTokenBudget = targetTokens
        maxSummaryTokens = minOf((contextLength * 0.05).toInt(), SUMMARY_TOKENS_CEILING)
    }

    // ── Summary generation ──

    private fun generateSummary(
        messages: List<Map<String, Any?>>,
        focusTopic: String?
    ): String? {
        // Build summarizer input with structured detail
        val summarizerInput = buildSummarizerInput(messages, focusTopic)

        // Determine summary model
        val useModel = if (summaryModel.isNotEmpty()) summaryModel else model

        // Calculate target summary size
        val contentLength = messages.sumOf { contentLengthForBudget(it["content"]) }
        val targetTokens = maxOf(
            MIN_SUMMARY_TOKENS,
            (contentLength / CHARS_PER_TOKEN * 0.20).toInt()
        )

        val prompt = buildSummarizerPrompt(summarizerInput, targetTokens, previousSummary)

        // Call LLM for summarization
        return callSummarizerLLM(prompt, useModel)
    }

    private fun callSummarizerLLM(prompt: String, model: String): String? {
        // Check cooldown
        val now = System.currentTimeMillis() / 1000
        if (now < summaryFailureCooldownUntil) {
            Log.w(TAG, "Summary generation in cooldown, skipping")
            return null
        }

        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", maxSummaryTokens)
                put("temperature", 0.1)
            }

            val useBaseUrl = baseUrl.ifEmpty { "https://api.openai.com/v1" }
            val url = URL("${useBaseUrl.trimEnd('/')}/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = 60_000
                readTimeout = 60_000
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode != 200) {
                val error = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                Log.w(TAG, "Summary LLM returned ${conn.responseCode}: $error")
                lastSummaryError = error
                summaryFailureCooldownUntil = now + SUMMARY_FAILURE_COOLDOWN_SECONDS
                return null
            }

            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val json = JSONObject(response)
            val summary = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // Track effectiveness
            previousSummary = summary
            lastSummaryError = null
            return summary
        } catch (e: Exception) {
            Log.w(TAG, "Summary generation failed: ${e.message}")
            lastSummaryError = e.message
            summaryFailureCooldownUntil = now + SUMMARY_FAILURE_COOLDOWN_SECONDS
            return null
        }
    }

    // ── Helpers ──

    private fun buildSummarizerInput(
        messages: List<Map<String, Any?>>,
        focusTopic: String?
    ): String {
        val sb = StringBuilder()
        if (focusTopic != null) {
            sb.appendLine("## Focus Topic: $focusTopic")
            sb.appendLine()
        }

        for (msg in messages) {
            val role = msg["role"] as? String ?: continue
            val content = msg["content"]
            val text = contentToString(content)

            when (role) {
                "system" -> {
                    sb.appendLine("[System] ${text.take(200)}")
                    sb.appendLine()
                }
                "user" -> {
                    sb.appendLine("[User] ${text.take(500)}")
                    sb.appendLine()
                }
                "assistant" -> {
                    val toolCalls = msg["tool_calls"] as? List<*>
                    if (!toolCalls.isNullOrEmpty()) {
                        sb.appendLine("[Assistant] (called ${toolCalls.size} tools)")
                    } else {
                        sb.appendLine("[Assistant] ${text.take(500)}")
                    }
                    sb.appendLine()
                }
                "tool" -> {
                    val toolName = msg["name"] as? String ?: "tool"
                    val summary = ToolResultSummarizer.summarize(toolName, "", text)
                    sb.appendLine("[Tool Result] $summary")
                    sb.appendLine()
                }
            }
        }
        return sb.toString()
    }

    private fun buildSummarizerPrompt(
        input: String,
        targetTokens: Int,
        previousSummary: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Summarize the following conversation into a structured summary.")
        sb.appendLine("Target length: ~$targetTokens tokens.")
        sb.appendLine()
        sb.appendLine("Use this template:")
        sb.appendLine("## Active Task")
        sb.appendLine("What the user is currently asking for.")
        sb.appendLine("## Resolved")
        sb.appendLine("- Bullet points of completed work.")
        sb.appendLine("## Pending")
        sb.appendLine("- Bullet points of remaining work.")
        sb.appendLine("## Key Decisions")
        sb.appendLine("- Important choices made.")
        sb.appendLine("## Files Modified")
        sb.appendLine("- List of files touched.")
        sb.appendLine()

        if (previousSummary != null) {
            sb.appendLine("## Previous Summary (update this):")
            sb.appendLine(previousSummary)
            sb.appendLine()
        }

        sb.appendLine("## Conversation to summarize:")
        sb.appendLine(input)
        sb.appendLine()
        sb.appendLine("Return ONLY the structured summary. No preamble.")

        return sb.toString()
    }

    /**
     * Content length for token budgeting.
     * Handles both plain strings and multimodal content lists.
     */
    fun contentLengthForBudget(content: Any?): Int {
        return when (content) {
            is String -> content.length
            is List<*> -> {
                var total = 0
                for (part in content) {
                    when (part) {
                        is String -> total += part.length
                        is Map<*> -> {
                            val ptype = part["type"] as? String
                            when (ptype) {
                                "image_url", "input_image", "image" -> total += IMAGE_CHAR_EQUIVALENT
                                else -> total += ((part["text"] as? String) ?: "").length
                            }
                        }
                        else -> total += (part?.toString()?.length ?: 0)
                    }
                }
                total
            }
            null -> 0
            else -> content.toString().length
        }
    }

    /**
     * Convert message content to plain text (best-effort).
     */
    fun contentToString(content: Any?): String {
        return when (content) {
            is String -> content
            is List<*> -> {
                content.filterIsInstance<Map<String, Any?>>()
                    .mapNotNull { it["text"] as? String }
                    .joinToString("\n")
            }
            null -> ""
            else -> content.toString()
        }
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}