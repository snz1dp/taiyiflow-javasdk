# Review: TaiyiFlow Java SDK 与后端 / Electron 能力差异审查报告

> 审查时间：2026-11-19。**复核：2026-11-21**（后端 277bb4beb9→af3825849a、Electron→9b306765 增量调研 + Java 侧逐项复查；Java 仓库自 e40cbf3/11-14 后无新提交）。复核结论与新缺口见 §十一，正文已按复核结果就地修正。
> 权威依据：`../taiyiflow-dev` 后端代码（协议权威）、`../taiyiflow-electron` 桌面智能体（后台适用行为参照）。
> 审查方式：三方代码交叉比对，所有结论附代码证据；未做真实平台联调，联调项单独列出。
> 本报告是差异审查快照，不替代 `plan-taiyiflowJavaSdk-roadmap.prompt.md` 与 `plan-taiyiflowJavaSdk-capability-alignment.prompt.md` 的实施计划地位。差异清单已落入对齐计划 Phase AH–AL（2026-11-21 闭环）。

## 一、总体结论

1. **已对齐面扎实**：Phase G–Z / AA–AG 声称的核心能力经抽查属实。对话双通道（SSE/WS + `/config.stream_protocol` 协商）、11 种流事件、关闭码语义、`data.chunk` 正文、工具三类事件（client_tool/mcp_tool/built_in）、REST 结果回传 + Outbox 补偿 + 3 次重试、执行控制（start/attach/resume + checkpoint）、SKILL 完整供应链路、四类媒体理解（tweaks 与超时逐项一致）、浏览器 17 工具（Chromium-only）、Schedule/todo/session_context/ask_user SPI/工具别名均已落地。**11-21 复核全部维持**。
2. **最大缺口在首帧请求模型**：后端 `SimplifiedAPIRequest` 支持而 Java `ChatRequest`（30 个字段）未覆盖的字段，导致 Java 数字人**静默丧失**项目指令注入、自动续跑、图片生成、模型选择、会话分组、关联会话、AgentApp 等平台能力。这不是协议错误（后端 `extra="allow"` + 字段缺省不报错），而是**能力性缺失**，且因不报错而难以察觉。复核修正：`long_term_memory_enabled/cross_session_memory_enabled/agent_app_owner_id/current_context_dir` **Electron 也未随首帧发送**，属"后端已支持、两端均未用"，降级至 §2.2（见 §十一.3）。
3. **协同 WS 落后于后端事件面**：`agent_loop.*` 恢复通知事件族与 `notification.*` 投递事件族未实现，影响"数字人可自愈"目标中的平台侧恢复通知链路。后端事件面自 11-17 后零改动，结论维持。
4. **工具面存在有意排除项与少量无意遗漏**：whiteboard/computer/预览/终端类属桌面 UI 专有（对齐计划已明确排除，合理）；但 `search_files` 别名、`run_subtask` 远程 detached 与取消契约等属于实质遗漏。**复核新查实两处缺陷**：watchdog 15min 工具超时低于媒体/子任务合法时长（长工具误判断流，§8.2）、白板技能未按工具可用性过滤（§4.2）。
5. **两份实施计划的"SDK 侧完成"表述基本可信**，但其范围冻结于 2026-11-14；此后后端/Electron 的演进未纳入。差异清单已于 2026-11-21 落入对齐计划 **Phase AH–AL**（首帧模型、执行可靠性与对账、恢复与权限语义、工具契约与技能过滤、配置矩阵与生态配套）。
6. **后端 11-19 后增量以管理台与网关认证为主**：Agent 运行时协议面（首帧、协同 WS、流事件、关闭码、结果回传）完全稳定；图谱/本体 14 个只读内置工具为服务端执行、不新增客户端义务；经 Dashserv 网关接入的服务账号有新的身份映射部署要求（§十一.2）。

## 二、协议层差异（P0：首帧请求模型）

后端权威：`taiyiflow-dev/src/backend/base/taiyiflow/api/v1/schemas.py:1640-1958`（`SimplifiedAPIRequest`，`extra="allow"` :1643；11-19 以来零改动）。
Electron 实际发送：`taiyiflow-electron/src/stores/assits/assits-chat-core.js:689-736`（WS 原样复用，assits-ws-lifecycle.js:433）。
Java 现状：`taiyiflow-sdk-core/.../transport/envelope/ChatRequest.java`（**30** 个字段）。
注意：`client_ip`（schemas.py:1804）与 `user_identity_info`（:1914）为**服务端注入字段，客户端勿传**。

### 2.1 Java 缺失且 Electron 已在发送的字段（高优先级）

| 字段 | 后端语义 | Electron 行为（11-21 复核行号） | Java 缺失后果 |
|------|----------|---------------|----------------|
| `client_instructions` | 客户端指令包（CLAUDE.md/AGENTS.md 分层 + merged），服务端 `_build_client_instructions_section` 注入系统提示词 | `instructions.js` 加载项目指令，随首帧发送（chat-core.js:728）；本地 AgentApp system_prompt 也并入此字段 | **数字人不读项目 AGENTS.md**，行为与桌面端在同一工作区不一致；这是当前会话环境中真实生效的机制 |
| `relation_sessions` | 关联会话上下文引入（上限 5，服务端校验截断） | @ 引用会话 → id 列表，空列表不序列化（chat-core.js:725-727） | 无法跨会话携带上下文 |
| `agent_auto_loop_enabled` / `agent_auto_loop_max_rounds` | 跨消息自主续跑开关与轮数上限 | `request_type === 'agent'` 时置 true（chat-core.js:692-693） | 数字人 agent 任务不自动续跑，长任务需外部反复驱动；对齐计划曾明确"不启用"，但现在这是 Electron 默认行为，需要重新决策（**已定案 2026-11-21：默认关闭、按任务显式开启**，对齐计划决策点 10，§六.3） |
| `image_generation_enabled` | 图片生成能力开关 | 按 FlowMetadata `image_generation` feature 自动置位（chat-core.js:706） | 即使 Flow 支持图片生成，Java 请求也不启用 |
| `model`（alias `model_key`） | 用户选择模型，需在 `available_model_keys` 范围内 | ChatModeMenu 选择器传入（chat-core.js:704）；**字段名必须是 `model`**；子任务继承所选模型（ec1a2486，subtask-stream-handler.js:236-237） | 无法按任务选择模型 |
| `group_id` | 新会话自动归档到分组 | launchGroupId（chat-core.js:713） | 数字人会话无法归组，平台侧管理视图缺失 |
| `agent_app_id` | AgentApp manifest 服务端展开（mcpPolicies/promptTemplates/agentTools） | 仅服务端应用携带 id，本地应用传 null 走 client_instructions（chat-core.js:730） | 无法接入 AgentApp 生态 |
| `client_context_dir` | 客户端上下文目录，写入消息审计 | clientContextDir 随请求发送（chat-core.js:700） | 平台审计缺工作区维度；注意披露边界（见 §六） |
| `input_type` / `output_type` 实际取值 | 后端默认 `any` | Electron 固定发 `chat`/`chat`（chat-core.js:709-710） | Java 字段存在但会话默认不设置，走 `any`；需确认对 Agent 提示词组装是否有行为差异 |

### 2.2 Java 缺失、后端已支持的字段（中优先级；含复核降级项）

| 字段 | 语义 | 建议 |
|------|------|------|
| `long_term_memory_enabled` / `cross_session_memory_enabled` | 本次请求长期记忆检索/写入与跨会话作用域开关 | **复核降级（原 §2.1）**：Electron 未随首帧发送；`cross_session_memory_enabled` 仅为共享 YAML 设置项（SystemSettingDialog.vue:788-790，默认 false :1951）。数字人记忆沉淀仍有价值，但无 Electron 行为可复刻，补齐前先确认服务端消费语义 |
| `agent_app_owner_id` | AgentApp 归属主体 | **复核降级**：Electron 未发送；随 `agent_app_id` 配套按需补齐 |
| `current_context_dir` | 当前上下文目录 | **复核降级**：Electron 仅用于设备通话 WS query（device-call/call-connection.js:127），不在对话首帧；数字人按需 |
| `runtime_strategy`（default/hermes） | Agent 运行时子策略 | 暴露为 Builder 可选项 |
| `command_execution_target`（server/client） | 内置命令工具暴露目标 | 数字人场景与 Shell 插件供应相关，需显式决策 |
| `max_iterations` / `agent_continue_windows` | Agent 迭代上限 / 非交互续跑窗口 | 长任务数字人尤其需要 |
| `agent_inputs` | Agent 额外输入（仅 POST 体对象） | WorkerTaskHandler 派单参数透传通道 |
| `master_flow_id` | 影子智能体主流程选择 | FlowMetadata 已暴露 shadow_flows，但请求侧无法使用 |
| `output_component` | 多输出组件选择 | FlowRunClient 同步运行需要 |
| `principal_id` / `actor_principal_id` | 执行/发起主体 | 与 Worker SPI 身份模型衔接 |
| `is_voice_dialogue` | 语音链路标记 | 维持"不做"边界，仅记录 |

### 2.3 已对齐项（抽查通过，11-21 复核维持）

- 执行控制字段全套：`execution_mode/execution_attempt_id/after_event_id/subscription_id/checkpoint_id/checkpoint_version/tool_results/submission_id`（ChatRequest.java:36-60；AgentSessionImpl `buildAttachRequest/buildResumeRequest`）。Electron 首帧仅传 `execution_mode`（start/resume，Electron-only）与 resume 分支的 `run_id/checkpoint_id/checkpoint_version`（chat-core.js:696,731-735）。
- `run_id/message_id/files/request_type/parent_id/reasoning/internet_search/knowledge_scopes/knowledge_docs/requested_skill_names/client_skill_packages/available_tools/client_mcp_servers/client_id/device_id/client_type/tweaks/async` 均存在。注：Electron 对话首帧不发 `message_id/parent_id`（parent_id 仅子任务体 subtask-stream-handler.js:245；message_id 用于结果回传链路），Java 保留字段正确、无害。
- `client_skill_packages` 为强类型 `List<ClientSkillPackage>`，符合对齐计划"只产出类型化数组"的决定（后端兼容 dict/list/str，schemas.py:1893 附近）。
- 事件常量 11 种与后端一致（EventConstants.java）；关闭码 1000/4000/4001/4002/4003/4004/4009 与 endpoints.py 一致（CloseCodes.java；后端行号 11-21 复核不变）。
- 工具结果回传 `POST /agents/client/call/result` 载荷 `{flow_id,session_id,message_id,run_id,call_id,success,result,error_message}` 与后端 `MCPClientCallResult`（**schemas.py:4549**，原 :4475 因管理台模型插入漂移，字段本体未变）逐字段一致（ToolResultReporter.buildPayload）；默认 3 次重试 + 指数退避 + Outbox 补偿，强于 Electron 的 axios 层重试。

### 2.4 client_type 语义差异（已定案：device 默认 + 可配置）

- Java 硬编码 `CLIENT_TYPE = "device"`（AgentSessionImpl.java:63）。
- Electron 使用 `desktop`；后端按 `client_type` 合并默认内置技能（services/agent/service.py）。
- **后果**：同一 Flow 下 Java 数字人与桌面端拿到的服务端内置技能集合可能不同。`device` 对后台数字人可能是正确形态，但必须是**显式决策**而非隐式硬编码。**已定案（2026-11-21，对齐计划决策点 13）**：维持 device 默认 + Builder/yml/starter 可配置 + 文档化对服务端技能合并的影响，Phase AL 实施。

## 三、协同 WS 差异（P1：恢复通知与通知投递）

后端权威：`taiyiflow-dev/src/backend/base/taiyiflow/services/client/service.py:117-120,1725-1769,2500-2543,3077-3135`（**11-21 复核：services/client/ 自 11-17 后零提交，行号全部仍有效**）。
Java 现状：`taiyiflow-sdk-collaboration/.../CollaborationPlugin.java`。

| 事件族 | 后端行为 | Java 现状 | 影响 |
|--------|----------|-----------|------|
| `agent_loop.resume_requested` / `agent_loop.resume_notification_ack` | 平台向声明 `agent_loop.resume.v1` capability 的在线客户端推送恢复通知，客户端 ack 后平台记录 `resume_notification_ack_at`（service.py:3092-3135,2500） | **未实现**；capabilities 只声明 `notification.v1,task.remote_task.v1,task.dialogue_subtask.v1`（CollaborationPlugin.java:46） | 数字人重启/接管后收不到平台主动恢复通知，只能靠自身 `listResumable` 轮询对账；"可自愈"链路缺平台推送半边 |
| `agent_loop.checkpoint_state` / `agent_loop.origin_available` | 检查点就绪状态推送 / 原执行实例可用性通知（service.py:1757-1769） | 未实现 | 跨实例故障接管时机判断缺依据 |
| `notification.deliver/fetch_recent/ack/read/read_sync/recent/reminder_action` | 平台通知投递与已读同步 | 声明了 `notification.v1` capability 但**不处理任何 notification.* 入站事件** | capability 声明与实现不符：平台可能据此认为客户端可接收通知并投递，消息被静默丢弃。**要么实现、要么撤掉 capability 声明**，当前状态最差 |
| `agent_loop.persistence_activate/active`、`agent_loop.event` | 服务端内部/CLI 隧道事件（service.py:3418-3439） | 未实现 | 属 CLI 隧道链路，数字人可暂不实现，但应在计划中显式记录排除 |
| 已对齐 | `connection.ready/ping/pong/kicked`、`task.dispatch/ack/status/result/error/cancel`、`primary_task.dispatch`、`schedule_task.dispatch`、`session.update`、`tasks.fetch_recent`（limit=20）、`release.push` | 均已实现 | — |

后端 capability 注册表共 5 个：`notification.v1 / coordination.v1 / task.remote_task.v1 / task.dialogue_subtask.v1 / agent_loop.resume.v1`（protocol.py:10-14,88-94；事件注册表 :37-86 无新成员）。Java 未声明 `coordination.v1`（桌面协同专用），不构成虚标，维持不声明。

## 四、工具面差异（P1/P2）

Electron 权威：`src-electron/agent-runtime/register-all.js`（主进程注册表，11-19 后零提交、工具零增减）+ 渲染进程拦截工具。
Java 权威：`BuiltinPlugins.java` + 各插件 `tool(...)` 注册 + `BrowserRuntime.register`。

### 4.1 名称/契约不一致（低成本修复 + 子任务取消契约）

| 项 | Electron | Java | 建议 |
|----|----------|------|------|
| 文件搜索 | `search_files`（file-tools.js:1194,1356：pattern 必填，glob/includePatterns/excludePatterns/caseSensitive/isRegexp/outputMode/offset/maxResults≤200，返回 has_more/total_matches，rg 优先 --max-count 5） | `search`/`grep`（SearchPlugin） | 补 `search_files` 别名**并对齐完整参数 schema**，复用同一 handler 与权限；否则桌面端 Flow 提示词中的 `search_files` 调用在数字人侧落空。11-21 复核：工作区搜索修复提交（47fb0b65 等）均为 WorkspaceBrowser UI，与 agent 工具无关，file-tools.js 契约未变 |
| 文件列举 | `glob_files`（pattern 必填，path/includeHidden/kind/maxResults≤1000，返回 truncated） | `glob_files` + `list_files` | 已对齐（Java 多名一个 list_files，无害） |
| `run_subtask` | **detached 仅远程生效**：`wait_for_completion=false` 创建协同任务后立即返回（subtask-manager.js:314,438-444）；**本地串行始终等待**（subtask-tools.js:62-65 schema 明示）。远程取消走 `POST /agents/client/collaborations/{id}/cancel` 服务端受理（api/collaboration.js:88-95），带 request_id 幂等（subtask-manager.js:813-821），**禁止本地 PATCH 终态**（会使服务端受理幂等跳过）；轮询可被 abort 立即中断（:741-768）；在途工具登记 call_id、取消经 cancelTool 中止、abort/父会话暂停后迟到结果不回传（subtask-stream-store.js:311-337）；executionId=call_id 透传含 built_in IPC 回退（subtask-stream-handler.js:535,582,585、client-mcp.js:84-86）；建流准备窗口入口+监听注册后同步检查、本地启动前/running 更新后/success 前三查取消（subtask-stream-handler.js:176-196、subtask-manager.js:574-581,620-625,643-646）；子任务继承 features.model、子任务体恒不传 execution_mode | 强制 `wait_for_completion=true`，detached 抛异常（SubtaskOrchestrator.java:199-200）；CollaborationTaskClient.cancel 已有但子任务链路未接线 | **复核重分类**："本地恒等待"与 Electron 本地语义一致，不是缺陷；缺口在远程形态。**P0 取消契约**（服务端受理+request_id 幂等+在途工具中止+迟到结果短路+executionId 透传+竞态守卫，幂等/副作用安全）；**P1 远程 detached 投递+模型继承**。需按平台任务幂等/对账约束设计后放开 |
| `terminal_capture` / `terminal_read` | 有（终端面板快照/后台任务终端视角） | 无（有 process_output 近似） | UI 依赖强，可排除；但 `terminal_capture` 的"后台任务 stdout/stderr 持续读取"语义与 Java BackgroundProcessRegistry 接近，可评估低成本适配 |
| 浏览器 `type` 参数 | `append`/`press_enter`（browser-tools.js:265-266） | `clear`/`submit`（BrowserRuntime.java:93-94） | **参数名不一致**：同一 Flow 的模型按 Electron schema 生成 `append/press_enter` 参数时 Java 端被忽略。建议兼容两组参数名 |
| 浏览器 `select_option` | `value`/`text` 二选一（browser-tools.js:398-412） | `value`/`label`（BrowserRuntime.java:100） | 同上，建议兼容 `text` |
| 浏览器 `read_text` | `max_chars`（browser-tools.js:296） | `max_characters`（BrowserRuntime.java:115） | 同上，建议兼容 `max_chars` |
| 浏览器 `screenshot` | `path`/`input_value`（browser-tools.js:278,282） | `understand`/`input`（BrowserRuntime.java:109-113,413） | 语义近似但参数名不同；`path` 未支持（Java 强制会话 workspace 是合理安全收紧，但应显式拒绝并提示，而非静默忽略） |
| 浏览器 `set_browser_type` | 有（UA 切换 + 移动视口） | 无（Chromium-only 决策，AGENTS.md 已记录） | 维持排除；当前未知工具返回泛化错误 "No handler for tool"（PluginManager.java:81），建议改为明确"不支持"提示，避免模型反复重试 |

### 4.2 Electron 独有、对齐计划已明确排除（维持现状；白板技能过滤除外）

- `taiyiflow-computer__*` 11 个桌面控制工具（computer-tools.js:28-49）——桌面专有。
- `whiteboard_*` 12 个白板工具（src/stores/assits/assits-whiteboard-tools.js）——渲染进程 UI。**复核确认为真实缺口**：Electron 首帧对技能包做 `filterWhiteboardSkillsByOpenState` 过滤（chat-core.js:716-719，随白板打开状态供应）；Java `SkillSupplier.visible()` 只按 `taiyiflow-in-chrome__` 前缀过滤 browser/chrome 类技能（SkillSupplier.java:121-126），**whiteboard 类技能正文会照常供应给无白板工具的数字人**（教模型调用不存在的工具）。需按 tool_category↔availableTools 联动过滤（行动 #17）。
- `open_file_preview`（assits-preview-tools.js）——右侧面板 UI。
- `get_sso_token`（sso-token-tools.js）——对齐计划明确排除，防止 WorkerAuth 凭证暴露给模型，正确。
- chrome_session Native Messaging / 扩展桥、桌面录屏——排除正确。

### 4.3 Java 独有（合理增强，无需回改）

- `bash`/`execute_command`/`shell` 多别名、`process_*` 主工具 + `task_*` 别名双轨。
- `write_file` 支持原子写入/append 模式、`file_edit` 带 sha256 冲突检测——强于 Electron 契约，向后兼容即可。

## 五、媒体理解与 SKILL 链路核查（抽查通过，附残留项）

| 项 | 结论 | 证据 |
|----|------|------|
| 四类理解 tweaks | 一致：`TextOutput-fXIUJ.output_format=text`；截图专用 `Prompt-d2zsV/Prompt-GRXIq` + `passthrough` | MediaUnderstandingPlugin.java:193-197 ↔ document-tools.js:48-50 |
| 超时默认值 | 一致：图片 29 分钟、音视频 59 分钟（MediaUnderstandingPlugin.java:64-65）。**但 watchdog 工具期上限硬编码 15min，低于上述合法时长——见 §8.2 新缺陷** | ↔ http-client.js:314、document-tools.js:30 |
| 截图理解失败降级 | 一致：保留截图 + `image_understanding_error` | BrowserRuntime.java:420 附近 |
| `/config` 消费 | ServiceConfig 覆盖 stream_protocol/五个 Flow ID/doc_exts/大小数量限制。**复核行号更新**：get_config 现居 endpoints.py:1762-1877（返回字典 :1826-1874）；新增 `ontology` 键（:1841-1843，本体+图谱双启用判定），纯 UI 模块开关，SDK 解析容忍即可 | ServiceConfig.java:18-42 |
| 首帧工具自动清单 | ToolCatalog.automatic/explicit + FlowMetadata features 过滤 + SkillSupplier 快照已接线。**复核新增**：FlowMetadata features 新增 `graph_search`/`ontology`（endpoints.py:1523-1531,1555-1556），解析需容忍新值；服务端 merge 的 14 个 `p_graph_*`/`p_ontology_*` 只读内置工具走既有 built_in 事件、服务端执行，不新增客户端义务（服务账号无角色时 fail-closed 不供应） | AgentSessionImpl.prepareNewStart:414-455 |
| workspace 注入 | `agent.workspaceFor(flowId, sessionId)` 已注入 ToolExecutionContext | AgentSessionImpl.java:1438 |
| **残留 1** | `/config` 的 `max_parallelism_subtask`（服务端默认 3，endpoints.py:1822-1824,1860）、`client_max_session_concurrency`（:1859）、`agent_auto_loop_max_rounds`（:1851）、`max_input_tokens`（:1852）等运行配额字段 Java 未消费；子任务并发上限本地硬编码，可能与服务端配额不一致 | endpoints.py:1762-1877 |
| **残留 2** | `document_understanding` feature 门控：Java 按后端完整语义门控文档工具，Electron 只过滤图/音/视三类——对齐计划 §七.7 **已随决策点 15 定案（2026-11-21）：维持 Java 按后端完整语义门控**。复核新证据：Electron 已将「文档工具」设置卡隐藏（19044fba，SystemSettingDialog.vue:988 v-if=false，服务端文档解析类型配置入口下线），其只过滤图/音/视的行为不再作为对齐目标 | 对齐计划 §四 Phase AB.1 |
| **残留 3** | 真实环境验收（对齐计划 §五 1–9 项：真实平台、真实 Chromium/CDP、Docker、平台技能）仍未执行，"SDK 侧完成"不等于交付完成 | 对齐计划尾注 |

## 六、安全与边界差异提示

1. **client_instructions / client_context_dir 引入时的披露边界**：若补齐 §2.1 字段，须沿用 SKILL 供应的既有原则——只发送显式配置目录中的指令文件，merged 文本限长，不默认扫描 home；容器路径披露需脱敏策略（对齐计划 §二.10 同理适用）。
2. **notification.v1 capability 虚标**（§三）：属于"声明了但不处理"的协议诚信问题，优先级高于功能本身。
3. **自动续跑与副作用**：若补齐 `agent_auto_loop_enabled`，必须与既有 RecoveryPolicy/"状态未知不重放"约束联合审查——自动续跑叠加断线恢复可能放大副作用重放风险，建议默认关闭、按任务显式开启。
4. **浏览器参数别名兼容**（§4.1）不得放宽 URL/网络/execute_js 安全策略；兼容层只做参数名映射。
5. **子任务取消必须走服务端受理**（§4.1）：本地 PATCH 协同任务终态会使服务端受理幂等跳过，产生"平台认为未取消、本地认为已取消"的权威分裂；取消与迟到结果短路是副作用安全约束，不是可选优化。
6. **AUTO 档不等于无守卫**（复核新增）：Electron auto/完全访问档豁免 opaque 命令确认，但高危守卫命中仍 deny，显式 deny/ask 规则先于豁免（security.js:1058-1066）。Java 若仅靠命令白名单，AUTO 档安全语义弱于 Electron；需高危 deny 清单 + 不可解析命令策略（行动 #19）。

## 七、建议行动清单（按优先级；已映射对齐计划 Phase AH–AL）

| # | 优先级 | 行动 | 涉及 | 计划落点 |
|---|--------|------|------|----------|
| 1 | P0 | ChatRequest 补齐 §2.1 九个字段 + 类型化 setter（memory/owner_id/current_context_dir 移 §2.2 按需）；`model` 注意 Pydantic alias；同步更新协议对照单测 | core/envelope、AgentSessionImpl、对照测试 | AH |
| 2 | P0 | `client_instructions` 加载器（AGENTS.md/CLAUDE.md 分层 + merged），Builder 显式开关，默认关闭或显式目录 | core 新增 instructions 组件 | AH |
| 3 | P0 | 协同 WS：实现 `agent_loop.resume.v1` 事件族（resume_requested→ack）或先从 capabilities 撤掉 `notification.v1` 虚标 | collaboration | AI |
| 4 | P1 | 长期记忆开关 + relation_sessions + group_id 接入 Worker 派单参数（WorkerTaskHandler → ChatRequest 透传） | worker、core | AH/AL |
| 5 | P1 | 浏览器工具参数名兼容（append/press_enter、text、max_chars、path 显式拒绝）；`set_browser_type` 返回明确不支持 | browser | AK |
| 6 | P1 | `search_files` 别名（完整 schema 对齐）；SkillSupplyPolicy 确认白板类技能不供应给无白板工具的会话（**复核已确认未过滤**） | plugins、core/skill | AK |
| 7 | P2 | §2.2 中优先级字段按需求逐个暴露；`max_parallelism_subtask` 等 /config 配额消费（含服务端 0=禁用合并语义） | core | AL |
| 8 | P2 | `run_subtask` 远程 detached 投递（本地恒等待语义维持），依托 #16 取消契约与平台幂等/对账，不本地伪造 | core/subtask、collaboration | AK |
| 9 | P2 | client_type 按已定案实施（device 默认 + 三层可配置 + 文档化技能合并影响，决策点 13，2026-11-21 定案） | core、docs | AL |
| 10 | 持续 | 对齐计划 §五真实环境验收 1–9 项仍为发布前待办 | 用户环境 | AG 遗留 |
| 11 | P0 | 按 §八 矩阵补齐"Electron 用户可选项 → Java 配置入口"：请求默认值对象、执行超时族、权限细粒度规则；三层入口（Builder/worker.yml/starter）同步落地 | core、worker、starter | AH/AJ/AL |
| 12 | P1 | 解除硬编码：StreamIdleWatchdog 90s/15min（AgentSessionImpl.java:92-93）、CLIENT_TYPE=device、Shell 命令超时装配层不可配 | core、plugins、worker、starter | AI/AL |
| 13 | P0 | 新增 tool-calls 查询封装（§九.2#1），打通 Outbox dead-letter/重启场景的平台对账闭环 | core/rest、core/report | AI |
| 14 | P1 | 补齐 models/session-groups/notifications REST 封装（§九.2#2-4），与对应首帧字段同批实施 | core/rest | AH/AL |
| 15 | P0 | **（复核新增）watchdog 超时治理**：idle/tool 超时三层可配（0=禁用 idle）；工具期超时 ≥ 注册工具最大时长（媒体 29/59min、子任务 1h），复刻 Electron"链超时 ≥ 工具超时+60s 且 ≥31min"原则；修复当前 15min 硬编码导致合法长工具执行中误报 TimeoutException | core/session、plugins、worker、starter | AI |
| 16 | P0 | **（复核新增）子任务取消契约**：远程取消走服务端受理端点 + request_id 幂等、在途工具 call_id 中止、abort/暂停后迟到结果短路、executionId=call_id 透传、建流/启动竞态守卫 | core/subtask、collaboration | AI |
| 17 | P1 | **（复核新增）SkillSupplier 白板技能过滤**：按 tool_category↔availableTools 联动（对齐 filterWhiteboardSkillsByOpenState），防止教模型调用不存在工具 | core/skill | AK |
| 18 | P1 | **（复核新增）恢复并行化**：多会话并行 resume + per-checkpoint 租约锁 + 有界退避 + 建流超时；回调补偿 400 确定性拒绝不重试、tool_call_paused 丢弃 | core/session | AJ |
| 19 | P1 | **（复核新增）AUTO 档高危 deny 清单 + opaque 命令策略**；显式 deny/ask 规则先于豁免；审批超时返回显式 timeout（≠DENY）、支持 0=无限等待 | plugins/shell、core/dispatch | AJ |
| 20 | P1 | **（复核新增）Dashserv 网关身份部署文档**：经网关接入的 DigitalWorker，X-App-Id 必须是已注册 Dashserv consumer 且映射到启用应用，否则 401；XEAI 禁用即 401 | worker/docs、deploy | AL |
| 21 | P2 | **（复核新增）容忍与口径项**：features/config 新键（graph_search/ontology）；扫描 edit 补报按会话快照归属过滤（排除外部进程改动）；同工作区占用非阻断警告；knowledge retry/parse-record 按需封装 | core、plugins、worker | AL |

## 八、Electron 用户可选项 → Java 配置入口对齐矩阵（2026-11-19 补充，11-21 复核修订）

> 需求：Electron 模式下留给用户选择/设置的项，Java SDK 必须留出对应的代码配置入口，语义对齐。
> Electron 的用户配置有三个来源：① 共享 YAML（`taiyiflow.yaml`，SystemSettingDialog.vue 读写，与 CLI 共享）；② 会话级 UI 选择（ChatModeMenu/AccessPolicySelector/输入区开关）；③ Electron prefs（streamProtocol 等）。
> Java 对应三层入口：`TaiyiAgent.Builder`（standalone）、`worker.yml`（DigitalWorker）、`TaiyiFlowProperties/DigitalWorkerProperties`（Spring Boot）。**同一配置项必须三层同时暴露，默认值与安全语义一致**（对齐计划 Phase AG 的"能力漂移"约束同样适用于配置面）。
> 复核：runtime-config.js/config-manager.js 11-19 后零提交，无新增配置项、无 YAML 默认值变化；唯一变化是「文档工具」设置卡隐藏（见 §五残留 2）。

### 8.1 请求行为类（会话级 UI 选择 → Builder 请求默认值 + 会话级覆盖）

Electron 证据：`assits-chat-core.js:689-736`、`ChatModeMenu.vue`、`SystemSettingDialog.vue:774-790,1078-1113`。

| Electron 用户选项 | Electron 载体/默认 | Java 现状 | 应留的 Java 配置入口 |
|-------------------|--------------------|-----------|----------------------|
| 请求模式 ask/plan/agent | AccessPolicySelector + 会话切换 | `ChatRequest.requestType` 仅调用方逐次显式传入，无 agent 级默认 | `Builder.defaultRequestType(...)` + `worker.yml: execution.default-request-type` + starter 属性；会话级仍可覆盖 |
| 快速回答/深度思考 + 模型选择 | ChatModeMenu → `reasoning` + `model`（alias） | `reasoning` 字段存在但无默认入口；`model` 字段缺失（§2.1） | `Builder.defaultReasoning(boolean)`、`Builder.modelKey(String)`（序列化为 `model`）；per-chat 覆盖；子任务继承所选模型 |
| 联网搜索开关 | 输入区 → `internet_search` | 字段存在，无默认入口 | `Builder.defaultInternetSearch(boolean)`；受 FlowMetadata feature 门控 |
| 知识库范围选择 | 输入区 → `knowledge_scopes/knowledge_docs` | 字段存在，无默认入口 | `Builder.defaultKnowledgeScopes(...)`；worker 按任务配置 |
| 长期记忆/跨会话记忆 | **复核修正**：仅 YAML 设置项 `cross_session_memory_enabled`（SystemSettingDialog.vue:788-790，默认 false），**未随首帧发送** | 字段缺失（§2.2） | `Builder.longTermMemory(boolean)`、`crossSessionMemory(boolean)` + yml/starter 仍可预留，但补齐前先确认服务端消费语义，不复刻不存在的 Electron 请求行为 |
| 自动续跑轮数 | YAML `agent_auto_loop_max_rounds`（默认 50，仅 agent 模式生效，runtime-config.js:100-110） | 字段缺失；无续跑 | `Builder.autoLoop(enabled, maxRounds)`；**默认关闭**（数字人副作用约束，§六.3），yml `execution.auto-loop.*` |
| 图片生成 | 按 feature 自动 `image_generation_enabled` | 字段缺失 | `Builder.imageGeneration(AUTO|ON|OFF)`，AUTO=按 FlowMetadata（对齐 Electron） |
| 输出语言 | YAML `output_language`（:1113） | 无 | 经 tweaks/agent_inputs 透传入口；确认服务端消费点后定字段 |
| 关联会话 @ 引用 | 会话级 `relation_sessions`（上限 5，空列表不序列化） | 字段缺失 | `AgentSession` 会话级 API（非 Builder 全局），对齐"会话级状态"语义 |
| 会话分组 | launchGroupId → `group_id` | 字段缺失 | `Builder.defaultGroupId(...)` + worker 派单参数透传 |
| 项目指令文件 | `client_instructions`（instructions.js 自动加载工作区 AGENTS.md/CLAUDE.md） | 缺失 | `Builder.instructions(dirs...)` 显式目录开关（默认关闭，§六.1 披露边界） |
| AgentApp 选择 | AgentAppLauncher → `agent_app_id`（本地应用传 null） | 缺失 | `Builder.agentApp(id, ownerId)`；本地应用提示词走 instructions 层 |

### 8.2 执行与超时类（SystemSettingDialog 数值项 → Builder/yml/starter）

Electron 证据：`SystemSettingDialog.vue:804-878`、`runtime-config.js:137-232`、`agent-runtime/index.js:308-337`、`shell-tools.js:238-243`。

| Electron 用户选项 | Electron 默认/上限 | Java 现状 | 应留的 Java 配置入口 |
|-------------------|--------------------|-----------|----------------------|
| `http_timeout_seconds` | 600s，上限 2000s | `requestTimeout` 默认 30s（TaiyiFlowProperties:40），语义为单请求 | 已有入口但默认值差异大；文档说明口径，媒体/FlowRun 已单独分离超时（正确） |
| `command_timeout_seconds` | YAML 可配 | ShellPlugin 构造器有 `timeoutSeconds`，但 **BuiltinPlugins.register 未暴露、worker.yml/starter 无配置项** | `tools.shell.command-timeout` 三层打通 |
| `confirm_timeout_seconds` | 默认 300s（index.js:308），可配 **0=永不超时**/10-6000s，回退 command_timeout（:322-337）；**超时返回 'timeout' 区别于拒绝**，工具回错误结果提示模型换方案（:638-639）；排队失效项清队 | `permissionTimeout` 默认 300s 一致，已有三层入口；但超时语义是否等价于 DENY 需核对 | 已对齐默认值；补"超时≠DENY 的显式 timeout 结果"与 0=无限等待语义（行动 #19） |
| `stream_idle_timeout_seconds` | 1200s，0=禁用，上限 6000s；Web 兜底 300s | **硬编码 90s 空闲 + 15min 工具超时**（AgentSessionImpl.java:92-93），无任何配置入口。**复核已核实**：工具期不挂起而是切换 15min 上限（StreamIdleWatchdog.java:75,87，测试 toolExecutionUsesBoundedToolTimeout 证实为有意设计）；所有工具走同一 dispatch 路径（AgentSessionImpl.java:1431,1456），**15min 低于媒体 29/59min 与子任务 1h 合法时长，长工具执行中误报 TimeoutException** | `Builder.streamIdleTimeout(Duration)`（0=禁用）+ `toolExecutionTimeout`；yml/starter 同步；工具期超时须 ≥ 注册工具最大时长（行动 #15，P0） |
| `subtask_timeout_seconds` | 600s，上限 6000s | SubtaskOrchestrator 默认 1h（:42），构造器可配但装配层未暴露 | `tools.subtask.timeout/max-parallelism` 三层打通 |
| `max_parallelism_subtask` | 本地默认 3、上限 32；**服务端 /config 值收紧合并、0=禁用**（runtime-config.js:262-300） | 本地硬编码；未消费服务端值（§五残留 1） | 配置入口 + 复刻服务端合并语义（服务端 0=禁用 run_subtask 工具供应） |
| 工具链任务超时 | subtask+60s 且 ≥31min（runtime-config.js:216-232） | watchdog 15min 工具期上限违反"链超时不得小于工具自身超时"原则（复核证实） | 对齐该原则，避免误报未执行（行动 #15） |
| 流协议选择 | prefs.streamProtocol，空=跟随服务端（stream-protocol.js:8-9） | `Builder.streamProtocol(AUTO|SSE|WEBSOCKET)` 已有 | **已对齐**；补 yml/starter 暴露核查 |

### 8.3 权限与安全类（AccessPolicySelector/SystemSettingDialog → PermissionPolicy + 细粒度规则）

Electron 证据：`AccessPolicySelector.vue:100-143`、`SystemSettingDialog.vue:380-536,565-586,1209,1518`、`security.js:1058-1066`、`shell-command-analysis.js:16-204`。

| Electron 用户选项 | Electron 语义 | Java 现状 | 应留的 Java 配置入口 |
|-------------------|---------------|-----------|----------------------|
| 执行档 confirm/auto/full | confirm=逐次确认；auto=命令自动+浏览器跟随全局；full=`approval_mode:auto + write_confirmation_policy:direct + browser/computer.permission_mode:skip_all_permission_checks`。**复核补充**：auto/full 豁免 opaque（静态分析无法完全解析：命令替换/变量展开/未闭合引号/heredoc/超深度）命令确认，但**高危守卫命中仍 deny**（shutdown/reboot/sudo/mkfs*/dd of=/dev//rm 根或家目录/fork bomb/反向 shell/敏感文件/下载落盘等，security.js:529,611-628,961 起），显式 deny/ask 规则先于豁免 | `PermissionPolicy` 只有 AUTO/CONFIRM/READONLY 三档粗粒度；AUTO≈full 但**无高危 deny 清单、无 opaque 命令策略、无浏览器独立权限模式** | 保留三档兼容，新增 `PermissionRule` 细粒度配置：按工具类别（shell/file/browser/skill/schedule）独立 AUTO/CONFIRM/DENY；`browser.permission-mode` 独立于全局；AUTO 档补高危 deny 清单 + 不可解析命令策略（行动 #19） |
| `write_confirmation_policy` confirm/direct | 写操作确认独立于命令审批 | 无独立开关（isMutating 一刀切） | `permission.write-confirmation: CONFIRM|DIRECT` |
| `permission_rules` 规则列表 | 用户自定义逐条规则（:565-586,2604），先于 auto 豁免 | 无 | `permission.rules[]`（工具名/模式 → ALLOW/DENY/ASK），PermissionHook 链已有承载点 |
| `sandbox_escape_policy` / `shell_sandbox_mode` | OS 级沙盒策略（macOS sandbox-exec 等） | 仅目录沙盒+命令白名单，无 OS 沙盒 | 短期：文档明确 Java 无 OS 沙盒等价物，`tools.shell.sandbox` 预留枚举；不伪装支持 |
| `skill_review_policy` / `skills.auto_install_policy` / `skills.install_scope` | 技能审查/自动安装/安装范围 | `skills.sync-mode: DISABLED/CONFIRM/AUTO` 已有；install_scope 对应 skill_create 的 workspace/user | 基本对齐；补 `skills.review-policy` 与 elevated-risk 开关联动核查 |
| 浏览器模式 builtin/isolated/chrome_session/disabled + headless + share_mode | inapp-browser.js:8、SystemSettingDialog:1300-1703 | `browser.mode: DISABLED/HEADLESS/REMOTE_CDP`（有意排除 chrome_session） | 已对齐后台适用子集；`browser.headless` 在 Java 中 HEADLESS 即无头，文档映射即可 |
| computer.permission_mode / computer.enabled | 桌面控制独立权限 | 无 computer 工具（有意排除） | 不实现；配置模型不预留假开关 |

### 8.4 落地要求

1. **新增 `RequestDefaults`（或 ChatRequest 模板）值对象**：承载 §8.1 全部请求级默认值，Builder 持有不可变默认，`AgentSession.chat()` 逐次覆盖；attach/resume 沿用 RunContext 快照（不因配置热更漂移，与 SKILL 快照同一原则）。
2. **三层入口一致性测试**：同一配置在 Builder/worker.yml/starter 三条装配路径产生相同 ChatRequest/运行时行为，纳入 Phase AG 式对照测试；缺省值以 Electron 共享 YAML 默认值为参照，**安全相关项（auto-loop、instructions、execute-js、sync-mode）默认取更严格一侧**。
3. **解除三处硬编码**（行动 #12/#15）：StreamIdleWatchdog 90s/15min、CLIENT_TYPE=device（§2.4）、Shell 超时装配断链。
4. **配置披露边界**：`client_context_dir` 等路径类字段沿用 §六.1 脱敏原则；permission.rules 不得被技能正文或模型参数反向修改。
5. Electron 独有且已排除的项（computer、chrome_session、whiteboard、桌面 prefs）**不预留假配置开关**，在配置文档中显式标注"后台不适用"，避免使用方误以为可用。

## 九、Agent 端必需服务端接口的本地 REST 封装核查（2026-11-19 补充，11-21 复核维持）

> 问题：Java SDK 是否对齐了 Agent 端必须的服务端接口本地 API 封装？
> 方法：以 Electron `src/api/*.js`（23 个 API 模块）+ 后端 `api/v1/*.py` 路由为参照，逐一比对 Java `transport/rest/*Client` 的方法与路径。
> 复核：后端 11-19 后新增 REST（monitor/dashboard、knowledge entity-extract records/retry/parse-record、apps/xeai）全部为管理台 UI/权限角色消费，Agent 运行时无依赖，显式排除；knowledge retry/parse-record 若数字人未来承担知识入库运维可升 P2 备选。

### 9.1 已封装且对齐（核心闭环完整）

| Java Client | 覆盖端点 | 对照结论 |
|-------------|----------|----------|
| ConfigClient + ServiceConfig | `GET /config` | 一致（类型化 + 原始 JSON 双轨）；新增 ontology 键需容忍 |
| FlowMetadataClient | `GET /run/{flow}/metadata` | 一致；features 新增 graph_search/ontology 需容忍 |
| FlowRunClient | `POST /run/{flow}`（同步/async 202）、`GET /run/tasks/{id}`、`POST .../cancel` | 一致 |
| ExecutionController | `POST /run/sessions/{id}/pause|cancel`、`GET /run/checkpoints/resumable` | 一致；retry/repeat 为会话级组合操作（AgentSessionImpl:221-237），非独立端点，与后端语义相符 |
| SessionClient | `GET /session_id`、flows/{id}/sessions 列表/详情/重命名/删除/批量删除、messages 查询/批量删除 | 与 Electron agents.js 会话族一致 |
| FileClient | `POST /agents/upload/{flow}`（流式 multipart）、`GET /agents/download/...` | 一致 |
| ToolResultReporter | `POST /agents/client/call/result` | 载荷逐字段一致（§2.3，MCPClientCallResult 现居 schemas.py:4549），重试+Outbox 强于 Electron |
| SkillClient | skills 列表/正文/上传（JSON+ZIP）/删除、installations 回报/查询/uninstall | 与 skills.js 全量对齐 |
| MemoryClient | memories 列表/创建/详情/forget/restore | 与 agent_memory.py 对齐 |
| ScheduleClient | client/schedules CRUD/enable/disable/runs/getRun/report | 与 schedule 路由对齐（含 run record 回报） |
| McpClient | mcp/servers CRUD/status | 与 agent-mcp.js 对齐 |
| CollaborationTaskClient | client/collaborations 创建/查询/list/cancel + running/success/failure/cancelled（带 expectedVersion 乐观锁） | 与 collaboration.js + agent_client.py 对齐，乐观并发控制完整；**子任务取消契约接线见行动 #16** |
| StreamClient/SseStreamClient | WS `/run/{flow}/websocket`、SSE `/run/{flow}/stream` | 一致 |

**结论：对话—工具—执行控制—任务回报主链路的 REST 封装无缺口。**

### 9.2 缺失封装（按对数字人的必要性分级）

| # | 端点（后端证据，11-21 复核行号） | Electron 用途 | Java 现状 | 必要性 |
|---|------------------|---------------|-----------|--------|
| 1 | `GET /agents/flows/{flow}/sessions/{sid}/messages/{mid}/tool-calls`（agent_chat.py:723-727，分页，支持 run_id/via_client/order_by 过滤） | 消息工具调用记录查询 | **无封装** | **P0：这是"平台对账"的权威查询入口**。AGENTS.md 约束 8/10 要求重启后以平台任务/检查点/结果对账、状态未知不重放；当前 Outbox 补偿失败进入 dead-letter 后（CheckpointToolResultsAdapter 只做本地 entry 比对），**没有 REST 手段查询平台侧该 call_id 是否已有结果**，对账闭环缺最后一环 |
| 2 | `GET /agents/client/notifications/recent`（client_notification.py:20-21）、`POST /agents/client/notifications/{id}/read`（:41-42） | notification-sync.js 通知补拉/已读 | 无封装 | **P1：与 §三 notification.v1 capability 虚标同源**。即使暂不实现 WS 推送，也应先补 REST 补拉，形成"重启后拉取未读通知"的兜底 |
| 3 | `GET /agents/flows/{flow}/models`（agent_user_config.py:217；Electron agents.js:355） | ChatModeMenu 模型列表（available_model_keys 范围） | 无封装 | **P1：§2.1 `model` 字段的前置依赖**——不查可用模型范围就传 model 会被后端拒绝 |
| 4 | `GET /agents/session-groups` 族（agent_session_groups.py:41；Electron agents.js:224-300：列表/创建/update/删除/归档会话） | 会话分组管理 | 无封装 | P1：§2.1 `group_id` 首帧字段的配套管理面；数字人会话归组需要 |
| 5 | `GET /agents/flows/{flow}/runs/{run}/intention`（agent_chat.py:642，含 relation_questions） | 意图识别结果展示 | 无封装 | P2：数字人可用于任务路由/澄清决策，非阻塞 |
| 6 | `GET /agents/client/online-desktops`、`GET /agents/client/principals`、notification-policy（agent_client.py:410、agent-principal.js） | 协同目标发现/主体通知策略 | 无封装 | P2：数字人作为**发起方**向桌面端派单/播报时需要；纯接活形态可缓 |
| 7 | `POST /agents/client/broadcast`（voice-broadcast.js） | 语音播报投递 | 无封装 | P2：随 dialogue"不做"边界，仅记录 |
| 8 | `POST /agents/client/release/report-status`（agent_client.py:1814） | 客户端升级投递回报 | CollaborationPlugin 收 `release.push` 但**无回报封装** | P2：容器化数字人升级走镜像发布，可显式声明不适用；但收了推送不回报应文档化 |
| 9 | `GET /agents/external-resources/{id}/resolve`（externalResources.js） | 受控产物（3d/医学影像等 fence）下载地址换取 | 无封装 | P2：数字人产出受控产物 artifact 时需要 |
| 10 | `GET /agents/agent-apps` 族（agent-app.js：列表/详情/plugins/install/uninstall） | AgentApp 生态 | 无封装 | P2：§2.1 `agent_app_id` 的配套管理面 |
| 11 | `/agents/plugins` 族（agent-plugin.js） | 平台插件安装/启停 | 无封装 | P2：与 AgentApp 联动，按需 |
| 12 | `/quotas/*`、`/credits/*`（credit.js） | 配额/积分查询 | 无封装 | P2：数字人长时间运行宜有配额预检，避免 4003 关闭码后才发现配额耗尽 |
| 13 | `GET /health_check`、`GET /version`（health.js/appinfo.js） | 健康检查/版本 | 无封装（RestClient 可裸调） | P2：低成本补齐，容器探针/兼容性检查有用 |
| 14 | `/agents/prompts` 族、`/agents/knowledge/bases|documents` 族、`/agents/shares`、`/asr/speech_to_text`、`/users/whoami/*`、`/exhibition/*`、**monitor/dashboard、knowledge entity-extract records/retry/parse-record、apps/xeai（11-21 新增）** | 提示词管理/知识库管理/分享/语音转写/个人资料/展会/运行监测大屏/知识处理流水/候选应用 | 无封装 | 排除或按需：知识库**检索**走首帧 knowledge_scopes（服务端能力），管理面非 Agent 运行时必需；whoami/exhibition/monitor 属 UI/运营 |

### 9.3 结论与行动

1. **主链路已对齐**：13 个 Client 覆盖对话、工具回传、执行控制、文件、技能、记忆、调度、MCP、协同任务全部必需端点，且带乐观锁/重试/Outbox 等强于 Electron 的可靠性语义。
2. **对账闭环有实质缺口（P0）**：`tool-calls` 查询封装缺失使"平台为权威"的对账原则在 dead-letter 场景无法落地——建议新增 `ToolCallRecordClient`（或并入 SessionClient），供 Outbox 补偿失败与重启对账时按 `run_id + via_client` 查询平台已收结果，再决定补报或标记完成。
3. **三个 P1 封装是 §二/§三 字段补齐的前置依赖**：models（model 字段）、session-groups（group_id 字段）、notifications REST（notification.v1 兜底）——应与对应首帧字段同一批次实施，避免"字段有了但配套查询没有"。
4. 其余按 P2/排除处理；`release.push` 收而不报、`notification.v1` 声明而不处理这两处"半接线"状态应优先消除（实现或显式撤声明）。

## 十、证据索引（11-21 复核更新行号）

- 后端首帧：`taiyiflow-dev/src/backend/base/taiyiflow/api/v1/schemas.py:1640-1958`（SimplifiedAPIRequest，extra="allow" :1643；client_ip :1804、user_identity_info :1914 服务端注入）、**:4549**（MCPClientCallResult，原 :4475 漂移）
- 后端 /config：`api/v1/endpoints.py:1762-1877`（返回字典 :1826-1874；配额键 :1851-1860；ontology 开关 :1841-1843）
- 后端协同事件：`services/client/service.py:117-120,1725-1769,2500-2543,3077-3135,3418-3439`（11-17 后零提交）；capability/事件注册表 `services/client/protocol.py:10-14,37-94`
- 后端结果回传：`api/v1/agent_client.py:1277-1330`
- 后端图谱/本体工具供应：`constants.py:505-546`（14 个 p_graph_*/p_ontology_*）、`process.py:2663-2690`、`tool_supply.py:159-247`（服务账号 fail-closed :192,237、builtin_graph.py:35-40）；features `endpoints.py:1523-1531,1555-1556`、`chat_meta.py:113,121`；对外 MCP `mcp_server.py:157,173`、`mcp_graph_server.py:78,96`（排除）
- 后端网关认证：`auth/utils.py:42-45,73-81,90-98,107-123,170-176`（X-Credential-*/X-App-Id、XEAI 标记、app_code 解析）、`dashserv_util.py:182`、`external_upload.py:70-73`、BUILD.yaml（27cf046718）
- 后端缺失端点：`api/v1/agent_chat.py:642,723-727`（intention/tool-calls）、`client_notification.py:20-21,41-42`（notifications）、`agent_client.py:410,1814`（online-desktops/release report-status）、`agent_user_config.py:217`（models）、`agent_session_groups.py:41`（session-groups）
- Electron 首帧：`src/stores/assits/assits-chat-core.js:689-736`（model :704、auto-loop :692-693、image-gen :706、group_id :713、relation_sessions :725-727、client_instructions :728、agent_app_id :730、resume 分支 :731-735、白板技能过滤 :716-719）；WS 复用 `assits-ws-lifecycle.js:433`
- Electron 子任务：`subtask-manager.js:314,438-444,574-581,620-625,643-646,741-768,813-821`、`subtask-tools.js:62-65`、`subtask-stream-store.js:311-337`、`subtask-stream-handler.js:176-196,236-250,535,582,585`、`api/collaboration.js:88-95`、`client-mcp.js:84-86`
- Electron 恢复：`agent-loop-recovery.js:13-14,113,185-192,237-284,316-341`（并行 resume、租约锁、建流 60s、退避 1/2/5/10/20/30s、补偿队列）
- Electron 审批/安全：`agent-runtime/index.js:308-337,638-639`、`AgentConfirmDialog.vue:243-248,583-608,652-661`、`security.js:529,611-628,711-731,884-946,961,1058-1066`、`shell-command-analysis.js:16-18,60-204`
- Electron 工具注册：`src-electron/agent-runtime/register-all.js:88-89`（零增减）、`browser-tools.js:265-266,278,282,296,398-412`、`file-tools.js:815,830,1135,1194,1356`、`computer-tools.js:28-49`
- Electron 媒体：`src-electron/agent-runtime/document-tools.js:30-50`、`http-client.js:314`
- Electron 扫描/占用：`shell-tools.js:481,983-1018,1030,1123`、`file-snapshot.js:204-208`、`chat-core.js:230-243,383-388`
- Electron 用户设置面：`src/components/SystemSettingDialog.vue:380-536,774-878,988,1078-1156,1209,1518-1703,1951`、`src/components/agent/AccessPolicySelector.vue:100-143`、`src/components/agent/ChatModeMenu.vue`
- Electron 配置消费：`src/utils/runtime-config.js:100-300`、`src/utils/stream-protocol.js:8-26`、`src-electron/agent-runtime/index.js:318-331`、`shell-tools.js:238-243`、`src/stores/inapp-browser.js:8-82`
- Java 首帧：`taiyiflow-sdk-core/.../transport/envelope/ChatRequest.java`（30 字段）
- Java 会话：`core/session/AgentSessionImpl.java:63,92-93,274-455,1431,1438,1456`；`core/session/StreamIdleWatchdog.java:75,87`（工具期切换上限）；测试 `StreamIdleWatchdogTest.toolExecutionUsesBoundedToolTimeout`
- Java 协同：`taiyiflow-sdk-collaboration/.../CollaborationPlugin.java:46,441`
- Java 浏览器：`taiyiflow-sdk-browser/.../BrowserRuntime.java:49,93-94,100,109-115,413`
- Java 媒体：`taiyiflow-sdk-plugins/.../media/MediaUnderstandingPlugin.java:59-67,193-197`
- Java 回传/对账：`taiyiflow-sdk-core/.../transport/rest/ToolResultReporter.java`、`core/report/{ReportOutbox,CheckpointToolResultsAdapter,FileReportOutbox}.java`
- Java 子任务：`core/subtask/SubtaskOrchestrator.java:42,199-200`
- Java 技能过滤：`core/skill/SkillSupplier.java:106-128`（visible()，仅 browser/chrome 过滤 :121-126）、`core/skill/SkillSupplyPolicy.java`（仅供需开关，无工具联动）
- Java 未知工具：`core/plugin/PluginManager.java:81`（泛化 "No handler for tool"）
- Java 配置面：`taiyiflow-spring-boot-starter/.../starter/TaiyiFlowProperties.java:40,61`、`DigitalWorkerProperties.java`、`deploy/worker.yml`
- REST 封装比对：Java `transport/rest/*Client.java`（13 个）↔ Electron `src/api/{agents,skills,collaboration,client_call,agent-mcp,files,flow,appinfo}.js`、`src/stores/notification-sync.js:52,102`

## 十一、2026-11-21 复核记录

> 范围：后端 277bb4beb9（11-19）→ af3825849a（11-21）；Electron → 9b306765（11-21）；Java 最后提交 e40cbf3（11-14），无新提交。方式：git 增量 diff + 三方代码交叉比对 + 关键反转结论主审亲验。

### 11.1 Java 侧逐项复查（全部维持 + 两处新查实缺陷 + 一处勘误）

- §2–§9 引用的 Java 现状全部属实：ChatRequest 缺失字段、capability 虚标（CollaborationPlugin.java:46）、CLIENT_TYPE=device、watchdog 硬编码、浏览器参数名、detached 抛异常、search_files 缺失、13 个 REST Client 无 tool-calls/models/notifications/session-groups。
- **勘误**：ChatRequest 实为 **30** 个字段（原报告写 31）。
- **新缺陷 1（P0，行动 #15）**：watchdog 工具期保护为有意设计（StreamIdleWatchdog.java:75,87 + 对照测试），但 15min 上限低于媒体理解 29/59min（MediaUnderstandingPlugin.java:64-65）与子任务同步等待 1h（SubtaskOrchestrator.java:42）；所有工具经 handleClientToolEvent 同一路径计时（AgentSessionImpl.java:1431,1456），合法长工具执行中会误报 TimeoutException。三层均无配置入口。
- **新缺陷 2（P1，行动 #17）**：SkillSupplier.visible() 仅按 `taiyiflow-in-chrome__` 前缀过滤 browser/chrome 类技能（SkillSupplier.java:121-126），白板技能未过滤；Electron 首帧有 filterWhiteboardSkillsByOpenState（chat-core.js:716-719）互为印证。
- 未知工具返回泛化错误（PluginManager.java:81），set_browser_type 建议显式"不支持"。

### 11.2 后端增量（协议面稳定，增量在管理台与网关）

- **首帧/协同 WS/流事件/关闭码/结果回传零改动**；SimplifiedAPIRequest 字段全集核对无新增（另注 client_ip/user_identity_info 为服务端注入、勿传）。MCPClientCallResult 行号 :4475→:4549（管理台模型插入），字段未变。
- **图谱/本体**（1adbe81a8a 等）：对外 MCP 端点（/mcp/{app}/flow、/graph）排除；14 个 `p_graph_*`/`p_ontology_*` 只读内置工具由服务端 merge 进 available_tools、服务端执行、走既有 built_in 事件，**不新增客户端义务**；服务账号无角色→fail-closed 不供应（DigitalWorker 服务账号拿不到，属预期）。FlowMetadata features 新增 `graph_search`/`ontology`、/config 新增 `ontology` 键，SDK 解析容忍即可（P2）。
- **服务账号认证**（XEAI/Dashserv 系列提交）：认证头不变（X-Credential-Username/Userid、X-App-Id），API Key/JWT 与 persistentToken 链路零改动，Java 客户端**无新头要求**；变化在服务端——X-App-Id 改作 Dashserv 调用身份 code 解析为 app_code、账号名=app_code、XEAI 禁用即 401。**部署侧 P1（行动 #20）**：经网关接入的 DigitalWorker，X-App-Id 必须是已注册 Dashserv consumer 且映射到启用应用（gateway_dashserv_* 五项配置），否则 401；写入部署文档。
- **新增 REST 全部管理台消费**（monitor/dashboard、knowledge entity-extract records/{id}/retry、documents/{id}/parse-record、apps/xeai），显式排除；knowledge retry/parse-record 可作未来知识运维 P2 备选。
- /config 行号漂移：get_config 现居 endpoints.py:1762-1877；配额键无变化。

### 11.3 Electron 增量（首帧修正 + 子任务/恢复/审批语义）

- **首帧字段修正（反转原报告 §2.1 部分表述，已亲验 chat-core.js:689-736）**：`message_id/parent_id/long_term_memory_enabled/cross_session_memory_enabled/agent_app_owner_id/current_context_dir` 均不在对话首帧（parent_id 仅子任务体；current_context_dir 仅设备通话 WS query；cross_session_memory_enabled 仅 YAML 设置项）。memory/owner_id/current_context_dir 降级 §2.2；子任务模型继承（ec1a2486）只改 features 快照与子任务体，未改首帧 model。
- **run_subtask 完整契约（新增 P0 取消 + P1 detached，行动 #16/#8）**：detached 仅远程生效、本地串行恒等待（Java 强制 wait=true 对本地形态不是缺陷）；远程取消走服务端受理端点 + request_id 幂等、禁止本地 PATCH 终态；在途工具 call_id 中止 + 迟到结果短路；executionId=call_id 透传；建流/启动竞态守卫；子任务继承 features.model、恒不传 execution_mode。
- **断网恢复（新增 P1，行动 #18）**：无 attach 步骤，发现检查点→Promise.all 并行 resume，单会话异常不中断整轮；per-checkpoint 租约锁、建流 60s 超时、退避 1/2/5/10/20/30s；resume 复用原 run_id+checkpoint_id/version。**无新协议字段/事件**，纯客户端并行化。另有回调补偿队列：400 确定性拒绝不重试、tool_call_paused 丢弃。
- **审批超时（P2→并入 #19）**：默认 300s 与 Java 一致；0=永不超时；**超时返回 'timeout' 区别于拒绝**（提示模型换方案）；排队失效项清队。会话名补全属 UI，排除。
- **opaque 豁免与高危守卫（新增 P1，行动 #19）**：auto/full 豁免静态不可解析命令确认，但高危守卫命中仍 deny，显式 deny/ask 规则先于豁免；守卫清单见 §8.3。Java 仅白名单不够。
- **扫描补报 edit 归属（P2，行动 #21）**：shell_exec 基线 diff 补报未声明产物，edit 仅补报本会话有内容快照的路径（hasFileSnapshot），排除外部进程修改；Java Phase X 产物补扫若存在需同口径。
- **同工作区占用（P2，行动 #21）**：纯本地状态比对（executingSessions+归一化目录），非阻断 toast，无服务端 API/锁；Java 可加非阻断日志警告。
- **工作区搜索修复均为 UI**：file-tools.js 零提交，search_files/glob_files 契约不变，Java 别名缺口维持（行动 #6）。
- **浏览器参数/工具注册表/配置矩阵维持**：register-all.js 零增减；runtime-config.js 无新增配置项；唯一变化「文档工具」设置卡隐藏（19044fba，SystemSettingDialog.vue:988），作为 §五残留 2 决策新证据。

### 11.4 闭环去向

差异清单（行动 #1–#21）已落入 `plan-taiyiflowJavaSdk-capability-alignment.prompt.md` **Phase AH–AL**（2026-11-21 新增，**同日 SDK 侧完成，真实验收待执行**；#10 真实环境验收为用户职责持续项）；roadmap §十 引用已同步。实施落点：#1/#2/#4/#11/#14→AH，#3/#13/#15/#16→AI，#18/#19→AJ，#5/#6/#8/#17→AK，#7/#9/#12/#20/#21→AL（output_language 经核实服务端无消费点不设字段；knowledge retry/parse-record 维持备选不封装；release.push 收而不报已文档化）。真实环境验收 1–9 项仍为发布前待办，不因本轮闭环而豁免。

> 审查局限：本报告基于静态代码比对与既有单测声称，未运行真实平台/浏览器联调；Electron 渲染进程拦截工具（schedule/session/todo/ask_user/run_subtask 的实际 UI 实现）以主进程占位定义与 store 链路为准。后端 `extra="allow"` 意味着字段缺失不会报错，所有 §2 缺口的实际业务影响需在真实 Flow 上验证。