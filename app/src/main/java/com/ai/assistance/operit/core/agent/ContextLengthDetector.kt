package com.ai.assistance.operit.core.agent

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 自动探测模型上下文窗口大小。
 *
 * 三级策略：
 *   1. 调用 /models API 获取元数据中的 context_length
 *   2. 通过模型名子串匹配硬编码表
 *   3. 返回 null（降级到用户手动配置）
 */
object ContextLengthDetector {

    private const val TAG = "ContextLengthDetector"

    /**
     * 模型上下文长度来源
     */
    enum class Source(val label: String) {
        AUTO_API("API 自动检测"),
        AUTO_NAME("模型名匹配"),
        MANUAL("手动设置")
    }

    data class Result(
        val lengthK: Float,
        val source: Source
    )

    // ── 硬编码模型名 → 上下文长度（K）子串表，按 key 长度降序匹配 ──

    private val NAME_TO_CONTEXT_K = listOf(
        // Anthropic Claude 4.6/4.7 (1M)
        "claude-opus-4-7" to 1000f,
        "claude-opus-4.7" to 1000f,
        "claude-opus-4-6" to 1000f,
        "claude-sonnet-4-6" to 1000f,
        "claude-opus-4.6" to 1000f,
        "claude-sonnet-4.6" to 1000f,
        // OpenAI GPT-5 family
        "gpt-5.5" to 1050f,
        "gpt-5.4-nano" to 400f,
        "gpt-5.4-mini" to 400f,
        "gpt-5.4" to 1050f,
        "gpt-5.1-chat" to 128f,
        "gpt-5" to 400f,
        "gpt-4.1" to 1047f,
        // DeepSeek V4 (1M)
        "deepseek-v4-pro" to 1000f,
        "deepseek-v4-flash" to 1000f,
        "deepseek-v4" to 1000f,
        // Grok
        "grok-4-1-fast" to 2000f,
        "grok-4.20" to 2000f,
        "grok-4-fast" to 2000f,
        "grok-code-fast" to 256f,
        "grok-4" to 256f,
        "grok-3" to 128f,
        "grok-2" to 128f,
        // Qwen
        "qwen3-coder-plus" to 1000f,
        "qwen3-coder" to 256f,
        // MiMo
        "mimo-v2-pro" to 1000f,
        "mimo-v2.5-pro" to 1000f,
        "mimo-v2.5" to 1000f,
        "mimo-v2-omni" to 256f,
        "mimo-v2-flash" to 256f,
        // Gemma
        "gemma-4" to 256f,
        "gemma4" to 256f,
        "gemma-3" to 128f,
        // Kimi
        "kimi" to 256f,
        // Gemini (broad)
        "gemini" to 1000f,
        // Claude (broad catch-all, after specific entries)
        "claude" to 200f,
        // DeepSeek (broad catch-all)
        "deepseek" to 128f,
        // GLM
        "glm" to 202f,
        // MiniMax
        "minimax" to 200f,
        // Llama
        "llama" to 128f,
        // Qwen (broad)
        "qwen" to 128f,
        // GPT-4 (broad)
        "gpt-4" to 128f,
        // GPT-3.5
        "gpt-3.5" to 16f,
    )

    /**
     * 自动探测模型上下文长度。
     *
     * @param baseUrl API 端点 (如 https://api.openai.com/v1)
     * @param apiKey API Key
     * @param modelName 模型名 (如 gpt-4o)
     * @return 探测到的结果，null 表示无法确定
     */
    fun detect(baseUrl: String, apiKey: String, modelName: String): Result? {
        // 先尝试 API 探测
        val apiResult = detectFromApi(baseUrl, apiKey, modelName)
        if (apiResult != null) return apiResult

        // 再尝试模型名子串匹配
        val nameResult = detectFromName(modelName)
        if (nameResult != null) return nameResult

        return null
    }

    /**
     * 从 /models API 端点探测上下文长度。
     */
    private fun detectFromApi(baseUrl: String, apiKey: String, modelName: String): Result? {
        if (baseUrl.isBlank()) return null

        val baseUrlClean = baseUrl.trimEnd('/')
        val candidates = mutableListOf<String>()

        // 尝试 /models 端点
        if (baseUrlClean.endsWith("/v1")) {
            candidates.add(baseUrlClean + "/models")
            candidates.add(baseUrlClean.removeSuffix("/v1") + "/api/v1/models")
        } else {
            candidates.add(baseUrlClean + "/v1/models")
            candidates.add(baseUrlClean + "/models")
        }

        for (url in candidates) {
            try {
                val result = probeModelsEndpoint(url, apiKey, modelName)
                if (result != null) {
                    Log.i(TAG, "API detected context length: ${result.lengthK}K from $url")
                    return result
                }
            } catch (e: Exception) {
                Log.d(TAG, "Probe $url failed: ${e.message}")
            }
        }
        return null
    }

    private fun probeModelsEndpoint(url: String, apiKey: String, modelName: String): Result? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connectTimeout = 5000
            readTimeout = 5000
        }

        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }

        val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        conn.disconnect()

        val json = JSONObject(body)
        val models = json.optJSONArray("data") ?: return null

        // 寻找目标模型
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            val id = model.optString("id", "")
            if (!modelMatches(id, modelName)) continue

            // 尝试多个 key
            val contextLength = extractContextLength(model)
            if (contextLength != null) {
                return Result(contextLength / 1000f, Source.AUTO_API)
            }
        }

        return null
    }

    private fun modelMatches(apiModelId: String, targetModel: String): Boolean {
        if (apiModelId.isBlank() || targetModel.isBlank()) return false
        val a = apiModelId.lowercase().trim()
        val b = targetModel.lowercase().trim()
        return a == b || a.endsWith("/$b") || b.endsWith("/$a")
    }

    private fun extractContextLength(model: JSONObject): Int? {
        val keys = listOf(
            "context_length", "context_window", "max_context_length",
            "max_position_embeddings", "max_model_len", "max_input_tokens",
            "max_sequence_length", "max_seq_len", "n_ctx_train", "n_ctx", "ctx_size"
        )
        for (key in keys) {
            val value = model.opt(key)
            if (value is Number) {
                val v = value.toInt()
                if (v in 1024..10_000_000) return v
            }
        }
        return null
    }

    /**
     * 通过模型名子串匹配硬编码表。
     */
    private fun detectFromName(modelName: String): Result? {
        if (modelName.isBlank()) return null
        val lower = modelName.lowercase().trim()

        // 按 key 长度降序匹配（最长匹配优先）
        for ((key, contextK) in NAME_TO_CONTEXT_K) {
            if (lower.contains(key)) {
                Log.i(TAG, "Name-matched '$modelName' → $key → ${contextK}K")
                return Result(contextK, Source.AUTO_NAME)
            }
        }
        return null
    }
}