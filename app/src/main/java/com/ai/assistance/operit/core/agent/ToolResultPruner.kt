package com.ai.assistance.operit.core.agent

import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * Tool output pruning (cheap pre-pass, no LLM call).
 * Ported from upstream hermes-agent/agent/context_compressor.py
 *
 * Replaces old tool result contents with informative 1-line summaries.
 * Deduplicates identical tool results and truncates large tool_call arguments.
 */
object ToolResultPruner {

    private const val CHARS_PER_TOKEN = 4
    private const val PRUNED_PLACEHOLDER = "[Old tool output cleared to save context space]"

    /**
     * Prune old tool results with informative summaries.
     *
     * @param messages Original message list
     * @param protectTailCount Minimum messages to protect from end
     * @param protectTailTokens Token budget for tail protection (overrides count if larger)
     * @return Pair of (pruned messages, count of pruned items)
     */
    fun pruneOldToolResults(
        messages: List<Map<String, Any?>>,
        protectTailCount: Int,
        protectTailTokens: Int? = null
    ): Pair<List<Map<String, Any?>>, Int> {
        if (messages.isEmpty()) return Pair(messages, 0)

        val result = messages.map { it.toMutableMap() }.toMutableList()
        var pruned = 0

        // Build index: tool_call_id -> (tool_name, arguments_json)
        val callIdToTool = mutableMapOf<String, Pair<String, String>>()
        for (msg in result) {
            if (msg["role"] == "assistant") {
                @Suppress("UNCHECKED_CAST")
                val toolCalls = msg["tool_calls"] as? List<Map<String, Any?>>
                if (toolCalls != null) {
                    for (tc in toolCalls) {
                        val cid = tc["id"] as? String ?: ""
                        @Suppress("UNCHECKED_CAST")
                        val fn = tc["function"] as? Map<String, Any?> ?: emptyMap()
                        callIdToTool[cid] = Pair(
                            fn["name"] as? String ?: "unknown",
                            fn["arguments"] as? String ?: ""
                        )
                    }
                }
            }
        }

        // Determine the prune boundary
        val pruneBoundary: Int
        if (protectTailTokens != null && protectTailTokens > 0) {
            var accumulated = 0
            var boundary = result.size
            val minProtect = minOf(protectTailCount, result.size)
            for (i in result.size - 1 downTo 0) {
                val msg = result[i]
                val contentLen = contentLengthForBudget(msg["content"])
                var msgTokens = contentLen / CHARS_PER_TOKEN + 10

                @Suppress("UNCHECKED_CAST")
                val toolCalls = msg["tool_calls"] as? List<Map<String, Any?>>
                if (toolCalls != null) {
                    for (tc in toolCalls) {
                        @Suppress("UNCHECKED_CAST")
                        val fn = tc["function"] as? Map<String, Any?> ?: emptyMap()
                        val args = fn["arguments"] as? String ?: ""
                        msgTokens += args.length / CHARS_PER_TOKEN
                    }
                }

                if (accumulated + msgTokens > protectTailTokens && (result.size - i) >= minProtect) {
                    boundary = i
                    pruneBoundary = boundary
                    break
                }
                accumulated += msgTokens
                boundary = i
                pruneBoundary = if (i == 0) 0 else boundary
            }
        } else {
            pruneBoundary = result.size - protectTailCount
        }

        if (pruneBoundary <= 0) return Pair(result, 0)

        // Pass 1: Deduplicate identical tool results (keep most recent)
        val contentHashes = mutableMapOf<String, Pair<Int, String>>() // hash -> (index, tool_call_id)
        for (i in result.size - 1 downTo 0) {
            val msg = result[i]
            if (msg["role"] != "tool") continue
            val content = msg["content"] as? String ?: continue
            if (content.length < 200) continue
            val h = sha256(content).take(12)
            if (contentHashes.containsKey(h)) {
                result[i]["content"] = "[Duplicate tool output — same content as a more recent call]"
                pruned++
            } else {
                contentHashes[h] = Pair(i, msg["tool_call_id"] as? String ?: "?")
            }
        }

        // Pass 2: Replace old tool results with informative summaries
        for (i in 0 until pruneBoundary) {
            val msg = result[i]
            if (msg["role"] != "tool") continue
            val content = msg["content"] as? String ?: continue
            val toolCallId = msg["tool_call_id"] as? String
            val toolInfo = if (toolCallId != null) callIdToTool[toolCallId] else null
            val toolName = toolInfo?.first ?: "unknown"
            val toolArgs = toolInfo?.second ?: ""

            val summary = ToolResultSummarizer.summarize(toolName, toolArgs, content)
            result[i]["content"] = "[Pruned] $summary"
            pruned++
        }

        // Pass 3: Truncate large tool_call arguments in assistant messages outside protected tail
        for (i in 0 until pruneBoundary) {
            val msg = result[i]
            if (msg["role"] != "assistant") continue
            @Suppress("UNCHECKED_CAST")
            val toolCalls = msg["tool_calls"] as? List<Map<String, Any?>>
            if (toolCalls == null || toolCalls.isEmpty()) continue

            val truncatedCalls = toolCalls.map { tc ->
                @Suppress("UNCHECKED_CAST")
                val fn = tc["function"] as? Map<String, Any?> ?: return@map tc
                val args = fn["arguments"] as? String
                if (args != null && args.length > 800) {
                    val truncatedFn = fn.toMutableMap()
                    truncatedFn["arguments"] = truncateToolCallArgs(args, 200)
                    val newTc = tc.toMutableMap()
                    newTc["function"] = truncatedFn
                    newTc
                } else {
                    tc
                }
            }
            result[i]["tool_calls"] = truncatedCalls
        }

        return Pair(result, pruned)
    }

    /**
     * Content length for token budgeting (same logic as ContextCompressor).
     */
    private fun contentLengthForBudget(content: Any?): Int {
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
                                "image_url", "input_image", "image" ->
                                    total += ContextCompressor.IMAGE_CHAR_EQUIVALENT
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
     * Shrink long string values inside a tool-call arguments JSON blob
     * while preserving JSON validity.
     */
    fun truncateToolCallArgs(args: String, headChars: Int = 200): String {
        try {
            val json = org.json.JSONObject(args)
            return truncateJsonStrings(json, headChars).toString()
        } catch (_: Exception) {
            return args
        }
    }

    private fun truncateJsonStrings(obj: org.json.JSONObject, headChars: Int): org.json.JSONObject {
        val result = org.json.JSONObject()
        for (key in obj.keys()) {
            val value = obj.get(key)
            when (value) {
                is String -> {
                    result.put(key, if (value.length > headChars) {
                        value.take(headChars) + "...[truncated]"
                    } else value)
                }
                is org.json.JSONObject -> {
                    result.put(key, truncateJsonStrings(value, headChars))
                }
                is org.json.JSONArray -> {
                    result.put(key, truncateJsonArray(value, headChars))
                }
                else -> result.put(key, value)
            }
        }
        return result
    }

    private fun truncateJsonArray(arr: org.json.JSONArray, headChars: Int): org.json.JSONArray {
        val result = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val value = arr.get(i)
            when (value) {
                is String -> {
                    result.put(if (value.length > headChars) {
                        value.take(headChars) + "...[truncated]"
                    } else value)
                }
                is org.json.JSONObject -> {
                    result.put(truncateJsonStrings(value, headChars))
                }
                is org.json.JSONArray -> {
                    result.put(truncateJsonArray(value, headChars))
                }
                else -> result.put(value)
            }
        }
        return result
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}