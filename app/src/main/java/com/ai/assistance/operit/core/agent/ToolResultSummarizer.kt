package com.ai.assistance.operit.core.agent

/**
 * Generate informative 1-line summaries for tool call + result pairs.
 * Ported from upstream hermes-agent/agent/context_compressor.py
 *
 * Instead of a generic placeholder, produces a short but useful description
 * of what the tool did, e.g.:
 *   [terminal] ran `npm test` -> exit 0, 47 lines output
 *   [read_file] read config.py from line 1 (1,200 chars)
 */
object ToolResultSummarizer {

    /**
     * Create a 1-line summary of a tool call + result.
     *
     * @param toolName Name of the tool
     * @param toolArgs JSON string of tool arguments
     * @param toolContent Tool result content
     * @return Informative 1-line summary
     */
    fun summarize(toolName: String, toolArgs: String, toolContent: String): String {
        val args = try {
            org.json.JSONObject(toolArgs)
        } catch (_: Exception) {
            org.json.JSONObject()
        }

        val content = toolContent
        val contentLen = content.length
        val lineCount = if (content.isNotBlank()) content.count { it == '\n' } + 1 else 0

        return when (toolName) {
            // ── Hermes built-in tools ──
            "terminal" -> {
                val cmd = (args.optString("command", "")).let {
                    if (it.length > 80) it.take(77) + "..." else it
                }
                val exitCode = Regex(""""exit_code"\s*:\s*(-?\d+)""").find(content)?.groupValues?.get(1) ?: "?"
                "[terminal] ran `$cmd` -> exit $exitCode, $lineCount lines output"
            }
            "read_file" -> {
                val path = args.optString("path", "?")
                val offset = args.optInt("offset", 1)
                "[read_file] read $path from line $offset ($contentLen chars)"
            }
            "write_file" -> {
                val path = args.optString("path", "?")
                "[write_file] wrote to $path"
            }
            "patch" -> {
                val path = args.optString("path", "?")
                val mode = args.optString("mode", "replace")
                "[patch] $mode in $path ($contentLen chars result)"
            }
            "search_files" -> {
                val pattern = args.optString("pattern", "?")
                val path = args.optString("path", ".")
                val count = Regex(""""total_count"\s*:\s*(\d+)""").find(content)?.groupValues?.get(1) ?: "?"
                "[search_files] search for '$pattern' in $path -> $count matches"
            }
            "list_files" -> {
                val path = args.optString("path", "?")
                "[list_files] listed $path ($contentLen chars)"
            }
            "find_files" -> {
                val path = args.optString("path", "?")
                val pattern = args.optString("pattern", "?")
                "[find_files] pattern='$pattern' in $path ($contentLen chars)"
            }
            "grep_code" -> {
                val path = args.optString("path", ".")
                val pattern = args.optString("pattern", "?")
                "[grep_code] pattern='$pattern' in $path ($contentLen chars)"
            }
            "grep_context" -> {
                val path = args.optString("path", ".")
                val intent = args.optString("intent", "?")
                "[grep_context] intent='$intent' in $path ($contentLen chars)"
            }
            "apply_file" -> {
                val path = args.optString("path", "?")
                val type = args.optString("type", "?")
                "[apply_file] $type in $path ($contentLen chars result)"
            }
            "delete_file" -> {
                val path = args.optString("path", "?")
                "[delete_file] deleted $path"
            }
            "make_directory" -> {
                val path = args.optString("path", "?")
                "[make_directory] created $path"
            }
            "download_file" -> {
                val destination = args.optString("destination", "?")
                "[download_file] -> $destination ($contentLen chars)"
            }
            "visit_web" -> {
                val url = args.optString("url", "")
                "[visit_web] $url ($contentLen chars)"
            }
            // ── Browser tools ──
            "browser_navigate", "browser_click", "browser_snapshot",
            "browser_type", "browser_scroll", "browser_vision" -> {
                val url = args.optString("url", "")
                val ref = args.optString("ref", "")
                val detail = if (url.isNotEmpty()) url else if (ref.isNotEmpty()) "ref=$ref" else ""
                "[$toolName] $detail ($contentLen chars)"
            }
            // ── Web tools ──
            "web_search" -> {
                val query = args.optString("query", "?")
                "[web_search] query='$query' ($contentLen chars result)"
            }
            "web_extract" -> {
                "[web_extract] ($contentLen chars)"
            }
            // ── Memory ──
            "memory", "query_memory" -> {
                val action = args.optString("action", args.optString("query", "?"))
                "[memory] $action"
            }
            // ── Messaging ──
            "send_message" -> {
                "[send_message] sent message"
            }
            "todo" -> "[todo] updated task list"
            "clarify" -> "[clarify] asked user a question"
            // ── Code execution ──
            "execute_code" -> {
                val codePreview = (args.optString("code", "")).take(60).replace("\n", " ")
                "[execute_code] `$codePreview` ($lineCount lines output)"
            }
            // ── Skills ──
            "skill_view", "skills_list", "skill_manage" -> {
                val name = args.optString("name", "?")
                "[$toolName] name=$name ($contentLen chars)"
            }
            // ── Vision ──
            "vision_analyze" -> {
                val question = args.optString("question", "").take(50)
                "[vision_analyze] '$question' ($contentLen chars)"
            }
            // ── Cron ──
            "cronjob" -> {
                val action = args.optString("action", "?")
                "[cronjob] $action"
            }
            // ── Delegate ──
            "delegate_task" -> {
                val goal = args.optString("goal", "").let {
                    if (it.length > 60) it.take(57) + "..." else it
                }
                "[delegate_task] '$goal' ($contentLen chars)"
            }
            // ── Audio ──
            "text_to_speech" -> "[text_to_speech] generated audio ($contentLen chars)"
            // ── Process ──
            "process" -> {
                val action = args.optString("action", "?")
                val sid = args.optString("session_id", "?")
                "[process] $action session=$sid"
            }
            // ── Generic fallback ──
            else -> {
                val firstArg = args.keys().asSequence().take(2).joinToString(" ") { key ->
                    val sv = args.optString(key, "").take(40)
                    "$key=$sv"
                }
                "[$toolName] $firstArg ($contentLen chars result)"
            }
        }
    }
}