# CHANGELOG — 本地修改记录

> 详细记录每次对源码的修改，包括完整 diff。
> 对照 `FRAMEWORK_DECOMPILE.md` 底部的变更日志表快速定位。
> **本文件不会被 Gradle 编译。**

---

## [2026-05-09] 初始化

- 从 GitHub `SelectXn00b/HermesApp` main 分支下载 zip 并解压
- 版本：v2.0.8 (versionCode 52)
- 基线 commit: GitHub `5f483c6` (2026-05-08)

### 项目关系

- **上游原版**: `NousResearch/hermes-agent` (Python, 139k stars)
- **本项目**: `SelectXn00b/HermesApp` (Kotlin, 150 stars) — 基于上游魔改的 Android 客户端

### 新建文件

3. **FRAMEWORK_DECOMPILE.md** — 完整框架拆解文档（287行）
   - 覆盖：架构/导航/页面/工具/Provider/语音/Avatar/悬浮窗/网关/集成/服务/数据/子模块
   - 底部含变更日志表，记录每次修改概要

4. **CHANGELOG.md** — 本文件

### 修改文件

5. **.gitignore** — 追加排除项
   ```diff
   + # 框架拆解文档（不编译）
   + FRAMEWORK_DECOMPILE.md
   + CHANGELOG.md
   ```

### 参考仓库

- `/storage/emulated/0/hermes-agent/` — 上游原版 hermes-agent 源码
- `/storage/emulated/0/TaiziHermes/` — 本项目（HermesApp）源码

---

## [2026-05-09] 对齐上游 v0.13.0 — 第1批

> 上游: NousResearch/hermes-agent v0.13.0 (2026-05-07)
> 基线 SHA: main@78b0008f4451c4b3047107926e466dcfc257ae3e

### 新建目录

`core/agent/` — Agent 核心增强模块目录

### 新建文件（8个模块）

| 文件 | 功能 | 对应上游 | 行数 |
|------|------|---------|------|
| `TitleGenerator.kt` | 会话标题自动生成 | agent/title_generator.py | 106 |
| `ErrorClassifier.kt` | 错误分类（重试/降级/通知） | agent/error_classifier.py | 156 |
| `TrajectoryRecorder.kt` | 决策轨迹记录（JSONL） | agent/trajectory.py | 135 |
| `GoalManager.kt` | 持久化目标（Ralph loop） | agent/goal feature | 154 |
| `InsightEngine.kt` | 用量洞察报告 | agent/insights.py | 135 |
| `MemoryNudgeManager.kt` | 记忆推进提示 | agent/memory_manager.py | 109 |
| `SessionSearchManager.kt` | 跨会话全文搜索 | tools/session_search_tool.py | 182 |
| `CheckpointManager.kt` | 会话快照保存/恢复/剪枝 | tools/checkpoint_manager.py | 270 |

### 适配说明

所有模块均适配 Android 端：
- 使用 Room `db.openHelper.readableDatabase` 做聚合查询
- 使用 `suspend fun` + `Dispatchers.IO` 做 IO 操作
- 数据存储在 `/sdcard/Download/Hermes/` 下（与主应用一致）
- 不依赖 Python 运行时，纯 Kotlin 实现

---

## [2026-05-09] 对齐上游 v0.13.0 — 第2批

> 工具层模块迁移（10个 Kotlin 文件），新增目录 `core/tools/`

### 新建目录

`core/tools/` — 增强工具模块

### 新建文件（10个模块）

| 文件 | 功能 | 对应上游 | 行数 |
|------|------|---------|------|
| `TodoTool.kt` | 任务管理增强 | tools/todo_tool.py | 84 |
| `PostWriteLint.kt` | 写后代码检查 | tools/post_write_lint.py | 114 |
| `ClarifyTool.kt` | 理解确认工具 | tools/clarify_tool.py | 68 |
| `ApprovalSystem.kt` | 操作审批系统 | tools/approval.py | 119 |
| `PatchParser.kt` | Diff/补丁解析器 | tools/patch_parser.py | 244 |
| `FileStateManager.kt` | 文件状态跟踪 | tools/file_state.py | 105 |
| `ProviderProfile.kt` | 提供商配置档案 | providers/base.py | 196 |
| `ProviderRegistry.kt` | 提供商注册表 | providers/__init__.py | 240 |
| `ToolGuardrails.kt` | 工具循环护栏 | agent/tool_guardrails.py | 268 |
| `ToolResultPruner.kt` | 工具结果剪枝 | agent/context_compressor.py | 243 |

### 适配说明

- `ToolGuardrails` 实现了精确失败检测/相同工具失败检测/幂等无进展检测
- `ToolResultPruner` 包含去重/摘要替换/参数截断三阶段剪枝
- 所有模块适配 Android: 使用 `suspend fun` + `Dispatchers.IO`, Room db, `/sdcard/Download/Hermes/`

---

## [2026-05-09] 对齐上游 v0.13.0 — 第3批

> Agent 核心能力增强（14个 Kotlin 文件）

### 新建文件（14个模块）

| 文件 | 功能 | 对应上游 | 行数 |
|------|------|---------|------|
| `ContextEngine.kt` | 上下文引擎接口 | agent/context_engine.py | 101 |
| `ContextCompressor.kt` | 上下文压缩主类 | agent/context_compressor.py | 413 |
| `ToolResultSummarizer.kt` | 工具结果摘要 | agent/context_compressor.py | 174 |
| `StreamingThinkScrubber.kt` | 流式推理块清洗 | agent/think_scrubber.py | 258 |
| `ImageRouting.kt` | 图像输入路由 | agent/image_routing.py | 164 |
| `BudgetConfig.kt` | 工具结果预算配置 | tools/budget_config.py | 84 |
| `RetryUtils.kt` | 抖动退避重试 | agent/retry_utils.py | 67 |

### 适配说明

- `ContextCompressor` 拆分为 3 个文件: 主类/Pruner/Summarizer
- `StreamingThinkScrubber` 支持 5 种标签变体 + 部分标签保持
- `ImageRouting` 支持 magic-byte MIME 基嗅探 + native 模式
- `ToolGuardrails` / `ToolResultPruner` / `ToolResultSummarizer` 已在第2批创建，第3批复用

---

## [2026-05-11] Token 消耗优化 — 工具结果预算截断

> 解决：单次请求 input tokens 高达 1640 万（正常应为 23 万）

### 根因
- `BudgetConfig.PINNED_THRESHOLDS` 中 `read_file` 被设为 `POSITIVE_INFINITY`
- `HermesAgentLoop` 的 `toolResultPersister` 参数未接入
- 所有工具结果原样存储在上下文中，永不压缩

### 新建文件
1. `core/agent/BudgetToolResultPersister.kt` — 连接 BudgetConfig 和 ToolResultPersister

### 修改文件
2. `core/agent/BudgetConfig.kt` — 移除 read_file 的 PINNED POSITIVE_INFINITY
3. `hermes/HermesAdapter.kt` — 接入 BudgetToolResultPersister
4. `api/chat/EnhancedAIService.kt` — 两处 HermesAgentLoop 均接入 BudgetToolResultPersister

### 预算配置
- 默认单工具结果: 8KB
- read_file: 16KB
- grep_code: 4KB
- list_files: 2KB
- visit_web: 8KB
- 单轮总预算: 50KB
- 预览大小: 1KB

---

## [2026-05-11] 工作区/私人区 — 侧边栏空间分组

> 在侧边栏新增"空间"分组，支持按工作/私人过滤聊天列表

### 新建文件
1. `ui/common/SpaceFilterHolder.kt` — 全局空间过滤状态持有者

### 修改文件
2. `ui/common/NavItem.kt` — 新增 WorkSpace/PersonalSpace 导航项
3. `ui/main/OperitApp.kt` — 新增"空间"NavGroup + navigateTo 中注入 filter
4. `ui/main/screens/OperitScreens.kt` — Router: WorkSpace/PersonalSpace → AiChat
5. `ui/features/chat/screens/AIChatScreen.kt` — 聊天列表按 group 过滤
6. `ui/features/chat/viewmodel/ChatViewModel.kt` — 新建会话自动继承 space group
7. `res/values/strings.xml` — 新增中文字符串（空间/工作区/私人区）
8. `res/values-en/strings.xml` — 新增英文字符串（Spaces/Work/Personal）

### 工作原理
- 侧边栏最顶部新增"空间"分组，包含"工作区"和"私人区"
- 点击"工作区" → 过滤出 group=="work" 的聊天
- 点击"私人区" → 过滤出 group=="personal" 的聊天
- 点击"AI对话" → 显示全部
- 在空间内新建会话，自动继承 space group 标签
- 利用 ChatHistory.group 字段（已有），无需改数据库
