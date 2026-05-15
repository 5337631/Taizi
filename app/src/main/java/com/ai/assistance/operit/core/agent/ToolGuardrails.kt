package com.ai.assistance.operit.core.agent

import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Pure tool-call loop guardrail primitives.
 * Ported from upstream hermes-agent/agent/tool_guardrails.py
 *
 * Tracks per-turn tool-call observations and returns decisions.
 * Runtime code owns whether those decisions become warning guidance,
 * synthetic tool results, or controlled turn halts.
 */
object ToolGuardrails {

    private const val TAG = "ToolGuardrails"

    val IDEMPOTENT_TOOLS = setOf(
        "read_file", "search_files", "web_search", "web_extract",
        "session_search", "browser_snapshot", "browser_console",
        "browser_get_images", "list_files", "grep_code", "grep_context",
        "query_memory", "find_files"
    )

    val MUTATING_TOOLS = setOf(
        "terminal", "execute_code", "write_file", "patch", "todo",
        "memory", "skill_manage", "browser_click", "browser_type",
        "browser_press", "browser_scroll", "browser_navigate",
        "send_message", "cronjob", "delegate_task", "process",
        "apply_file", "delete_file", "make_directory"
    )

    // ── Data classes ──

    data class ToolCallSignature(
        val toolName: String,
        val argsHash: String
    ) {
        companion object {
            fun fromCall(toolName: String, args: Map<String, Any?>): ToolCallSignature {
                return ToolCallSignature(toolName, sha256(canonicalToolArgs(args)))
            }
        }
    }

    data class GuardrailDecision(
        val action: String = "allow",  // allow | warn | block | halt
        val code: String = "allow",
        val message: String = "",
        val toolName: String = "",
        val count: Int = 0,
        val signature: ToolCallSignature? = null
    ) {
        val allowsExecution: Boolean get() = action in setOf("allow", "warn")
        val shouldHalt: Boolean get() = action in setOf("block", "halt")
    }

    data class GuardrailConfig(
        val warningsEnabled: Boolean = true,
        val hardStopEnabled: Boolean = false,
        val exactFailureWarnAfter: Int = 2,
        val exactFailureBlockAfter: Int = 5,
        val sameToolFailureWarnAfter: Int = 3,
        val sameToolFailureHaltAfter: Int = 8,
        val noProgressWarnAfter: Int = 2,
        val noProgressBlockAfter: Int = 5
    )

    // ── Per-turn controller ──

    class Controller(private val config: GuardrailConfig = GuardrailConfig()) {

        private val exactFailureCounts = mutableMapOf<ToolCallSignature, Int>()
        private val sameToolFailureCounts = mutableMapOf<String, Int>()
        private val noProgress = mutableMapOf<ToolCallSignature, Pair<String, Int>>()
        var haltDecision: GuardrailDecision? = null
            private set

        fun resetForTurn() {
            exactFailureCounts.clear()
            sameToolFailureCounts.clear()
            noProgress.clear()
            haltDecision = null
        }

        fun beforeCall(toolName: String, args: Map<String, Any?>): GuardrailDecision {
            val signature = ToolCallSignature.fromCall(toolName, args)
            if (!config.hardStopEnabled) {
                return GuardrailDecision(toolName = toolName, signature = signature)
            }

            // Check exact failure block
            val exactCount = exactFailureCounts[signature] ?: 0
            if (exactCount >= config.exactFailureBlockAfter) {
                val decision = GuardrailDecision(
                    action = "block",
                    code = "repeated_exact_failure_block",
                    message = "Blocked $toolName: same call failed $exactCount times with identical arguments.",
                    toolName = toolName,
                    count = exactCount,
                    signature = signature
                )
                haltDecision = decision
                return decision
            }

            // Check idempotent no-progress block
            if (isIdempotent(toolName)) {
                val record = noProgress[signature]
                if (record != null && record.second >= config.noProgressBlockAfter) {
                    val decision = GuardrailDecision(
                        action = "block",
                        code = "idempotent_no_progress_block",
                        message = "Blocked $toolName: read-only call returned same result ${record.second} times.",
                        toolName = toolName,
                        count = record.second,
                        signature = signature
                    )
                    haltDecision = decision
                    return decision
                }
            }

            return GuardrailDecision(toolName = toolName, signature = signature)
        }

        fun afterCall(
            toolName: String,
            args: Map<String, Any?>,
            result: String?,
            failed: Boolean? = null
        ): GuardrailDecision {
            val signature = ToolCallSignature.fromCall(toolName, args)
            val isFailed = failed ?: classifyToolFailure(toolName, result)

            if (isFailed) {
                val exactCount = (exactFailureCounts[signature] ?: 0) + 1
                exactFailureCounts[signature] = exactCount
                noProgress.remove(signature)

                val sameCount = (sameToolFailureCounts[toolName] ?: 0) + 1
                sameToolFailureCounts[toolName] = sameCount

                // Hard stop check
                if (config.hardStopEnabled && sameCount >= config.sameToolFailureHaltAfter) {
                    val decision = GuardrailDecision(
                        action = "halt",
                        code = "same_tool_failure_halt",
                        message = "Stopped $toolName: failed $sameCount times this turn.",
                        toolName = toolName,
                        count = sameCount,
                        signature = signature
                    )
                    haltDecision = decision
                    return decision
                }

                // Warning checks
                if (config.warningsEnabled && exactCount >= config.exactFailureWarnAfter) {
                    return GuardrailDecision(
                        action = "warn",
                        code = "repeated_exact_failure_warning",
                        message = "$toolName failed $exactCount times with identical arguments.",
                        toolName = toolName,
                        count = exactCount,
                        signature = signature
                    )
                }
                if (config.warningsEnabled && sameCount >= config.sameToolFailureWarnAfter) {
                    return GuardrailDecision(
                        action = "warn",
                        code = "same_tool_failure_warning",
                        message = "$toolName failed $sameCount times this turn.",
                        toolName = toolName,
                        count = sameCount,
                        signature = signature
                    )
                }

                return GuardrailDecision(toolName = toolName, count = exactCount, signature = signature)
            }

            // Success — clear failure counts
            exactFailureCounts.remove(signature)
            sameToolFailureCounts.remove(toolName)

            // Track no-progress for idempotent tools
            if (!isIdempotent(toolName)) {
                noProgress.remove(signature)
                return GuardrailDecision(toolName = toolName, signature = signature)
            }

            val resultHash = sha256(result ?: "")
            val previous = noProgress[signature]
            val repeatCount = if (previous != null && previous.first == resultHash) {
                previous.second + 1
            } else 1
            noProgress[signature] = Pair(resultHash, repeatCount)

            if (config.warningsEnabled && repeatCount >= config.noProgressWarnAfter) {
                return GuardrailDecision(
                    action = "warn",
                    code = "idempotent_no_progress_warning",
                    message = "$toolName returned same result $repeatCount times.",
                    toolName = toolName,
                    count = repeatCount,
                    signature = signature
                )
            }

            return GuardrailDecision(toolName = toolName, count = repeatCount, signature = signature)
        }

        private fun isIdempotent(toolName: String): Boolean {
            if (toolName in MUTATING_TOOLS) return false
            return toolName in IDEMPOTENT_TOOLS
        }
    }

    // ── Static helpers ──

    fun classifyToolFailure(toolName: String, result: String?): Boolean {
        if (result == null) return false
        if (toolName == "terminal") {
            try {
                val json = JSONObject(result)
                val exitCode = json.optInt("exit_code", 0)
                if (exitCode != 0) return true
            } catch (_: Exception) {}
        }
        val lower = result.take(500).lowercase()
        return lower.contains("\"error\"") || lower.contains("\"failed\"") || lower.startsWith("error")
    }

    fun buildSyntheticResult(decision: GuardrailDecision): String {
        return JSONObject().apply {
            put("error", decision.message)
            put("guardrail", JSONObject().apply {
                put("action", decision.action)
                put("code", decision.code)
                put("tool_name", decision.toolName)
                put("count", decision.count)
            })
        }.toString()
    }

    fun appendGuidance(result: String, decision: GuardrailDecision): String {
        if (decision.action !in setOf("warn", "halt") || decision.message.isEmpty()) return result
        val label = if (decision.action == "halt") "Tool loop hard stop" else "Tool loop warning"
        return "$result\n\n[$label: ${decision.code}; count=${decision.count}; ${decision.message}]"
    }

    private fun canonicalToolArgs(args: Map<String, Any?>): String {
        val sorted = args.toSortedMap()
        return try {
            JSONObject(sorted as Map<String, Any>).toString()
        } catch (_: Exception) {
            sorted.toString()
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}