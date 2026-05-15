package com.ai.assistance.operit.core.agent

import android.util.Log

/**
 * Dangerous command detection and approval system.
 * Ported from upstream hermes-agent/tools/approval.py
 *
 * Detects destructive shell commands before execution.
 * On Android, we keep the detection patterns but use AlertDialog for approval.
 * The approval flow: detect → user prompt → approve/deny.
 */
object ApprovalSystem {

    private const val TAG = "ApprovalSystem"

    enum class ApprovalLevel { SAFE, DANGEROUS, HARDLINE }

    data class ApprovalResult(
        val command: String,
        val level: ApprovalLevel,
        val description: String?,
        val approved: Boolean
    )

    private val dangerousPatterns = listOf(
        Regex("""\brm\s+(-[^\s]*\s+)*/""", RegexOption.IGNORE_CASE) to "delete in root path",
        Regex("""\brm\s+-[^\s]*r""", RegexOption.IGNORE_CASE) to "recursive delete",
        Regex("""\bchmod\s+(-[^\s]*\s+)*(777|666|o\+[rwx]*w|a\+[rwx]*w)\b""", RegexOption.IGNORE_CASE) to "world-writable permissions",
        Regex("""\bmkfs\b""", RegexOption.IGNORE_CASE) to "format filesystem",
        Regex("""\bdd\s+.*if=""", RegexOption.IGNORE_CASE) to "disk copy",
        Regex(""">\s*/dev/sd""", RegexOption.IGNORE_CASE) to "write to block device",
        Regex("""\bDROP\s+(TABLE|DATABASE)\b""", RegexOption.IGNORE_CASE) to "SQL DROP",
        Regex("""\bDELETE\s+FROM\b(?!.*\bWHERE\b)""", RegexOption.IGNORE_CASE) to "SQL DELETE without WHERE",
        Regex("""\bTRUNCATE\s+(TABLE)?\s*\w""", RegexOption.IGNORE_CASE) to "SQL TRUNCATE",
        Regex(""">\s*/etc/""", RegexOption.IGNORE_CASE) to "overwrite system config",
        Regex("""\bsystemctl\s+(-[^\s]+\s+)*(stop|restart|disable|mask)\b""", RegexOption.IGNORE_CASE) to "stop/restart system service",
        Regex("""\bkill\s+-9\s+-1\b""", RegexOption.IGNORE_CASE) to "kill all processes",
        Regex("""\bpkill\s+-9\b""", RegexOption.IGNORE_CASE) to "force kill processes",
        Regex("""\bgit\s+reset\s+--hard\b""", RegexOption.IGNORE_CASE) to "git reset --hard",
        Regex("""\bgit\s+push\b.*--force\b""", RegexOption.IGNORE_CASE) to "git force push",
        Regex("""\bfind\b.*-exec\s+(/\S*/)?rm\b""", RegexOption.IGNORE_CASE) to "find -exec rm",
        Regex("""\bfind\b.*-delete\b""", RegexOption.IGNORE_CASE) to "find -delete",
        Regex("""\bxargs\s+.*\brm\b""", RegexOption.IGNORE_CASE) to "xargs with rm",
    )

    private val hardlinePatterns = listOf(
        Regex("""\brm\s+(-[^\s]*\s+)*(/|/\*|/ \*)(\s|$)""", RegexOption.IGNORE_CASE) to "recursive delete of root filesystem",
        Regex("""\brm\s+(-[^\s]*\s+)*(~|\$HOME)(/?|/\*)?(\s|$)""", RegexOption.IGNORE_CASE) to "recursive delete of home directory",
        Regex("""\bmkfs(\.[a-z0-9]+)?\b""", RegexOption.IGNORE_CASE) to "format filesystem (mkfs)",
        Regex("""\bdd\b[^\n]*\bof=/dev/(sd|nvme|hd|mmcblk)[a-z0-9]*""", RegexOption.IGNORE_CASE) to "dd to raw block device",
        Regex("""\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""", RegexOption.IGNORE_CASE) to "fork bomb",
        Regex("""\bkill\s+(-[^\s]+\s+)*-1\b""", RegexOption.IGNORE_CASE) to "kill all processes",
        Regex("""\b(shutdown|reboot|halt|poweroff)\b""", RegexOption.IGNORE_CASE) to "system shutdown/reboot",
    )

    /**
     * Detect if a command is dangerous.
     */
    fun detect(command: String): ApprovalResult {
        val normalized = command.lowercase().trim()

        // Check hardline first
        for ((pattern, desc) in hardlinePatterns) {
            if (pattern.containsMatchIn(normalized)) {
                return ApprovalResult(
                    command = command,
                    level = ApprovalLevel.HARDLINE,
                    description = desc,
                    approved = false
                )
            }
        }

        // Check dangerous
        for ((pattern, desc) in dangerousPatterns) {
            if (pattern.containsMatchIn(normalized)) {
                return ApprovalResult(
                    command = command,
                    level = ApprovalLevel.DANGEROUS,
                    description = desc,
                    approved = false
                )
            }
        }

        return ApprovalResult(
            command = command,
            level = ApprovalLevel.SAFE,
            description = null,
            approved = true
        )
    }

    /**
     * Build system prompt snippet for approval guidance.
     */
    fun getApprovalPrompt(): String = """
[APPROVAL SYSTEM]
Some commands require user approval before execution:
- DANGEROUS commands: destructive but recoverable (git reset --hard, rm -rf, chmod 777)
  → Will be blocked unless user approves
- HARDLINE commands: unrecoverable destruction (rm -rf /, mkfs, shutdown)
  → Always blocked, cannot be approved
- SAFE commands: normal operations
  → Execute directly

When you need to run a potentially dangerous command, inform the user and
wait for approval. Suggest safer alternatives when possible.
[/APPROVAL SYSTEM]
""".trimIndent()
}