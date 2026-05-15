# HermesApp 框架拆解文档

> 源码版本: 2.0.8 (versionCode 52) | 拆解时间: 2026-05-09
> **此文档不会被编译，仅供修改/编译时对照参考**

---

## 规模

| 指标 | 数量 |
|------|------|
| Kotlin 源文件 | 1011 |
| 顶层包 | 274 |
| LLM Provider | 16 |
| TTS Provider | 6 |
| STT Provider | 4 |
| Avatar 类型 | 6 |
| 一级导航入口 | 13 |
| 二级页面 | ~60+ |
| 内置工具 | 65+ |
| 子模块 | 9 |

---

## 顶层架构

```
api/          → AI 接口层（LLM/TTS/STT/语音）
core/         → 核心引擎层（Agent Loop/工具/技能/Avatar）
ui/           → 界面层（Compose UI）
hermes/       → 网关层（飞书/Discord/OpenAI 兼容 API）
services/     → 后台服务层（悬浮窗/网关/录屏/通知）
integrations/ → 外部集成（Tasker/HTTP 外部聊天/WebChat）
plugins/      → 插件系统（生命周期/工具包/工作流）
data/         → 数据层（数据库/API/偏好/备份）
widget/       → 桌面小部件
```

---

## 侧边栏导航（NavItem）

| NavItem | 路由 | 功能 |
|---------|------|------|
| AiChat | ai_chat | 主聊天界面 |
| Packages | packages | 包管理（Skill/MCP/Artifact 市场） |
| MemoryBase | memory_base | 记忆库（含知识图谱） |
| Toolbox | toolbox | 工具箱 |
| Workflow | workflow | 工作流管理 |
| AssistantConfig | assistant_config | 助手配置（人设/角色） |
| HermesSettings | hermes_settings | 网关设置（飞书/Discord） |
| ShizukuCommands | shizuku_commands | 权限管理（Shizuku） |
| Settings | settings | 通用设置 |
| TokenConfig | token_config | Token/密钥配置 |
| Help | help | 帮助 |
| About | about | 关于 |
| Feedback | feedback | 反馈 |

**侧边栏两种模式：** enableNewSidebar 开关控制
- 经典分组模式：按 NavGroup 分组显示
- 新版卡片模式：顶部网络状态 + 3个快捷卡片 + AI 功能列表

---

## 全部页面清单（Screen）

### 主页面
| Screen | 功能 |
|--------|------|
| AiChat | 主聊天 |
| MemoryBase | 记忆库主页 |
| Packages | 包管理主页 |
| Toolbox | 工具箱主页 |
| Workflow | 工作流列表 |
| AssistantConfig | 助手人设配置 |
| Settings | 设置主页 |
| ShizukuCommands | 权限管理 |
| HermesSettings | 网关设置主页 |
| Help | 帮助 |
| About | 关于 |
| Agreement | 用户协议 |

### 包管理子页面
| Screen | 功能 |
|--------|------|
| Market | 包市场（含 Skill/Artifact/MCP 三 Tab） |
| ArtifactManage | Artifact 管理 |
| ArtifactPublish | Artifact 发布 |
| SkillManage | Skill 管理 |
| SkillPublish | Skill 发布 |
| MCPManage | MCP 管理 |
| MCPPublish | MCP 发布 |
| MCPEditPlugin | MCP 插件编辑 |

### 设置子页面
| Screen | 功能 |
|--------|------|
| ModelConfig | 模型配置（含 MNN 端侧模型下载） |
| ModelPromptsSettings | 模型提示词/人设设置 |
| PersonaCardGeneration | 人设卡生成 |
| WaifuModeSettings | Waifu 模式 |
| CustomEmojiManagement | 自定义表情管理 |
| TagMarket | 标签市场 |
| FunctionalConfig | 功能开关配置 |
| ToolPermission | 工具权限管理 |
| UserPreferencesSettings | 用户偏好设置 |
| UserPreferencesGuide | 用户偏好引导 |
| ThemeSettings | 主题设置 |
| GlobalDisplaySettings | 全局显示设置 |
| LayoutAdjustmentSettings | 布局调整 |
| ChatHistorySettings | 聊天历史管理 |
| ChatBackupSettings | 聊天备份 |
| LanguageSettings | 语言设置 |
| TokenUsageStatistics | Token 用量统计 |
| ContextSummarySettings | 上下文摘要设置 |
| SpeechServicesSettings | 语音服务设置 |
| ExternalHttpChatSettings | 外部 HTTP 聊天设置 |
| MnnModelDownload | MNN 端侧模型下载 |
| GitHubAccount | GitHub 账号绑定 |

### 工具箱子页面
| Screen | 功能 |
|--------|------|
| FileManager | 文件管理器 |
| Terminal | 终端（Termux SSH） |
| TerminalSetup | 终端配置向导 |
| TerminalAutoConfig | 终端自动配置 |
| AppPermissions | 应用权限查看 |
| UIDebugger | UI 调试器（无障碍节点查看） |
| ShellExecutor | Shell 执行器 |
| Logcat | 系统日志查看 |
| SpeechToText | 语音转文字 |
| TextToSpeech | 文字转语音 |
| ToolTester | 工具测试器 |
| SqlViewer | 数据库查看器 |
| SkillRecorder | 技能录制器 |
| AutoGLM | AutoGLM 自动化 |
| ProcessLimit | 进程限制 |
| HtmlPackager | HTML 打包器 |
| ToolPkgComposeDsl | 工具包 Compose DSL 界面 |

### 网关子页面
| Screen | 功能 |
|--------|------|
| HermesGatewayCredentials | 网关凭证 |
| HermesGatewayPolicies | 网关策略 |
| HermesAgentParams | Agent 参数配置 |
| HermesGatewayService | 网关服务开关 |
| HermesGatewayQrBind | 网关二维码绑定 |

### 其他
| Screen | 功能 |
|--------|------|
| UpdateHistory | 更新历史 |
| TokenConfig | Token 配置（WebView） |
| ToolPkgPluginConfig | 工具包插件配置 |
| WorkflowDetail | 工作流详情 |

---

## Agent 工具系统（core/tools/）

```
core/tools/
├── defaultTool/
│   ├── accessbility/    → 无障碍 UI 工具（snapshot/tap/type/scroll）
│   ├── admin/           → 管理员工具（Shizuku）
│   ├── debugger/        → 调试工具
│   ├── root/            → Root 工具
│   ├── standard/        → 标准工具（文件/Shell/搜索/网页）
│   └── websession/      → WebSession 浏览器自动化
│       ├── browser/     → 浏览器控制
│       └── userscript/  → 用户脚本系统
├── system/              → 系统工具
│   ├── action/          → 设备操作（input/touch/screen_lock/等）
│   ├── shell/           → Shell 执行（Termux SSH）
│   └── shower/          → 投屏控制
├── agent/               → Agent 循环核心
├── calculator/          → 计算器工具
├── condition/           → 条件判断工具
├── javascript/          → QuickJS JavaScript 执行
├── mcp/                 → MCP 工具
├── packTool/            → 包工具（Skill/MCP Plugin 动态加载）
└── skill/               → Skill 工具
```

---

## LLM Provider

| Provider | 说明 |
|----------|------|
| OpenRouterProvider | OpenRouter（推荐，内置 Key） |
| OpenAIProvider | OpenAI 直连 |
| OpenAIResponsesProvider | OpenAI Responses API |
| ClaudeProvider | Anthropic Claude |
| GeminiProvider | Google Gemini |
| DeepseekProvider | DeepSeek |
| DoubaoAIProvider | 豆包 |
| QwenAIProvider | 通义千问 |
| KimiProvider | Kimi/Moonshot |
| OllamaProvider | 本地 Ollama |
| LlamaProvider | 本地 Llama |
| MNNProvider | 本地 MNN 端侧 |
| MistralProvider | Mistral |
| NvidiaAIProvider | NVIDIA |
| NousPortalProvider | Nous Portal |

---

## 语音系统

**STT：** SherpaSpeechProvider / SherpaMnnSpeechProvider / DeepgramSttProvider / OpenAISttProvider / OnnxSileroVad
**唤醒词：** PersonalWakeListener / PersonalWakeEnrollment / PersonalWakeFeatureExtractor
**TTS：** OpenAIVoiceProvider / OpenAIRealtimeVoiceProvider / MiniMaxVoiceProvider / SiliconFlowVoiceProvider / HttpVoiceProvider / AccessibilityVoiceProvider

---

## Avatar 虚拟形象

| 类型 | 说明 |
|------|------|
| DragonBones | 骨骼动画 |
| FBX | 3D 模型 |
| GLTF | GLTF 3D 模型 |
| MMD | MikuMikuDance 动画 |
| MP4 | 视频形象 |
| WebP | 动图形象 |

---

## 悬浮窗系统（ui/floating/）

ball → 悬浮球 | fullscreen → 全屏 | pet → 宠物模式 | screenocr → 屏幕 OCR | window → 窗口化 | voice → 语音

---

## 网关系统（hermes/gateway/）

HermesGatewayController / HermesGatewayConfigBuilder / HermesGatewayPreferences / GatewayFileLogger / GatewayChatEventBus / HermesAdapter / OperitChatCompletionServer / OperitToolDispatcher / OpenAiToolSchema

---

## 外部集成

ExternalChatHttpServer / ExternalChatReceiver / WebChatHttpBridge / AIAgentTasker / WorkflowTaskerActivity / WorkflowTaskerReceiver

---

## 服务层

ChatServiceCore / ChatServiceUiBridge / FloatingChatService / FloatingWindowManager / GatewayForegroundService / GatewayBootReceiver / SkillRecorderService / UIDebuggerService / OperitNotificationListenerService / OperitVoiceInteractionService / TermuxCommandResultService / CloudEmbeddingService

---

## 数据层

db+dao(Room) / model / preferences(DataStore) / repository / mcp+plugins / mnn / skill / backup / api(GitHub) / updates / collects / exporter / converter / announcement

---

## 子模块

hermes-android / terminal / quickjs / dragonbones / fbx / mmd / mnn / llama / showerclient

---

## 编译注意事项

- 本文件 .md 不会被 Gradle 编译
- compileSdk = 36, targetSdk = 35, minSdk = 26
- Kotlin 2.2.0, AGP 8.13.2, Gradle wrapper 8.13
- 需要 NDK（arm64-v8a）+ CMake
- C++17 / ObjectBox 5.3.0 / Room / KSP 2.2.0-2.0.2

---

## 变更日志

> 每次修改代码后在此追加记录，格式：`[时间] 文件 | 改动内容`
> 详细 diff 见 `CHANGELOG.md`

| 时间 | 文件 | 改动 |
|------|------|------|
| 2026-05-09 | FRAMEWORK_DECOMPILE.md | 新建：完整框架拆解文档 |
| 2026-05-09 | .gitignore | 添加 FRAMEWORK_DECOMPILE.md 排除编译 |
| 2026-05-09 | core/agent/ (新目录) | 对齐上游 v0.13.0 第1批：8个 Agent 核心增强模块 |
| 2026-05-09 | core/agent/TitleGenerator.kt | 会话标题自动生成 |
| 2026-05-09 | core/agent/ErrorClassifier.kt | 错误分类（重试/降级/通知） |
| 2026-05-09 | core/agent/TrajectoryRecorder.kt | 决策轨迹记录 |
| 2026-05-09 | core/agent/GoalManager.kt | 持久化目标（Ralph loop） |
| 2026-05-09 | core/agent/InsightEngine.kt | 用量洞察报告 |
| 2026-05-09 | core/agent/MemoryNudgeManager.kt | 记忆推进提示 |
| 2026-05-09 | core/agent/SessionSearchManager.kt | 跨会话全文搜索 |
| 2026-05-09 | core/agent/CheckpointManager.kt | 会话快照保存/恢复/剪枝 |
