# Plan: TaiyiFlow Java SDK Electron 后台能力对齐实施计划（Phase AA–AL）

> 创建时间：2026-11-14。本文是默认 Flow、Flow Metadata、媒体理解、浏览器、高价值工具与 SKILL 供应机制的唯一权威实施计划，不再嵌入运行时路线图。
> 实施状态：Phase AA–AG **SDK 侧已闭环**；Playwright Java、独立 browser 模块、flowId 缺省时自动回退默认 Flow、远程 CDP、完整 SKILL 供应及 worker/starter/示例/配置文档均已落地。真实平台、真实 Chromium/CDP、复杂 Docker 与所需 UI/平台验收仍由用户环境执行。
> **2026-11-21 增补：Phase AH–AL**。依据 `review-taiyiflowJavaSdk-capability-gap.prompt.md`（11-19 审查 + 11-21 复核，行动清单 #1–#21）新增差距闭环阶段：首帧请求模型、执行可靠性与平台对账、恢复并行化与权限语义、工具契约与技能过滤、配置矩阵与生态配套。AA–AG 范围与状态不变。
> 协议以 `../taiyiflow-dev` 后端代码为准，后台适用行为参照 `../taiyiflow-electron`；前置运行时能力以 `plan-taiyiflowJavaSdk-roadmap.prompt.md` 已完成的 Phase G–Z 为基础。
> 2026-11-14 本地验收：`./mvnw test` 全仓 401 个测试通过（32 + 210 + 82 + 9 + 57 + 11），`./mvnw package -DskipTests` 八个模块打包成功；这些结果不替代真实环境联调。

## 一、核查结论与范围

### 1.1 后端默认 Flow 与非流式运行协议已存在

权威来源为 `../taiyiflow-dev`，不是示例配置或文档。`src/backend/base/taiyiflow/api/v1/endpoints.py` 的 `GET /api/v1/config` 已返回以下字段：

| 配置字段 | 后端内置默认值（仅用于核查，不复制为 Java 兜底） | 用途 |
|----------|--------------------------------------------------|------|
| `default_flow_id` | `cc0d4d2f-bb23-49e0-9166-e9c9700ec361` | 未显式选择业务 Flow 时的服务端默认路由 |
| `doc_unstructured_flow_id` | `069f7497-8504-4223-8ddc-45f4c8b85031` | 文档解析/理解 |
| `image_flow_id` | `dd3024a8-eb8e-45de-9d11-a268094a5827` | 图片理解与截图理解 |
| `audio_flow_id` | `3b01b58d-564f-46ac-9ed4-8bb1ba5afc95` | 音频理解 |
| `video_flow_id` | `d1fcab16-25f5-4e9a-9b7d-2ddb03bfcdf7` | 视频理解 |

- 默认值定义于 `services/database/models/settings/utils.py` 与 `utils/constants.py`；线上配置可能覆盖。Java 必须读取当前服务 `/config`，禁止把上述 UUID 写成离线或请求失败时的本地替代值。
- 同接口还提供 `doc_exts`、`max_file_size_upload`、`once_max_file_count`、`text_summarize_flow_id`、`loop_decide_flow_id` 等。本计划只消费已明确需要的字段；不因读取了 `loop_decide_flow_id` 就启用自动续跑。
- 非流式同步运行：`POST /api/v1/run/{flow_id_or_name}`，响应含 `media_type/session_id/outputs`。
- 非流式异步运行：同一端点提交 `async=true`，HTTP 202 返回任务；轮询 `GET /api/v1/run/tasks/{task_id}`，状态为 `pending/running/success/failed/cancelled`；取消走 `POST /api/v1/run/tasks/{task_id}/cancel`。它与 Collaboration task、AgentSession run 是三种不同标识，不得混用。

证据：`api/v1/endpoints.py`、`api/v1/schemas.py`、`services/database/models/settings/utils.py`、`utils/constants.py`。

### 1.2 Electron 媒体理解的真实调用链

Electron `src-electron/agent-runtime/document-tools.js` 与 `http-client.js` 证实，四类理解不是把文件直接交给当前聊天 Flow 自行读取，而是两段调用：

1. 使用**当前业务 flowId + sessionId** 调 `POST /api/v1/agents/upload/{flowId}` 上传本地文件，取得平台文件路径。
2. 将上传路径作为输入交给 `/config` 指定的默认理解 Flow：文档同步运行，图片/音频/视频异步运行并轮询任务。
3. 将理解结果、媒体类型、会话和上传文件元数据作为客户端工具结果回传当前业务 Flow。

| 工具 | 输入与约束 | 理解 Flow 与运行方式 | 需要对齐的细节 |
|------|------------|----------------------|----------------|
| `document_unstructured` | `path/paths`、`working_directory`；本地沙盒；多文档；图片不能混入文档 | `doc_unstructured_flow_id`；同步 `runFlow` | 本地扩展名与服务端 `doc_exts` 交叉校验；返回 `count/items/media_type/session_id/uploaded_files`，按响应保留 `parsed_output` 或 `raw_output` |
| `image_understanding` | 单/多图片、沙盒与大小校验 | `image_flow_id`；异步 `runFlowAsync` | 默认 tweaks 为 `TextOutput-fXIUJ.output_format=text`；默认整体超时 29 分钟、轮询 2 秒 |
| `audio_understanding` | 音频文件与大小校验 | `audio_flow_id`；异步 | 默认整体超时 59 分钟；失败、取消、超时明确区分 |
| `video_understanding` | 视频文件与大小校验 | `video_flow_id`；异步 | 默认整体超时 59 分钟；禁止把轮询 HTTP 超时误当整体超时 |

截图理解复用图片理解链路，但使用 Electron 已验证的专用 tweaks：`Prompt-d2zsV`、`Prompt-GRXIq`、`TextOutput-fXIUJ`，图片压缩策略为 `passthrough`。截图成功而理解失败时，截图工具仍返回截图结果，并附 `image_understanding_error`，不能让附加理解失败抹掉已产生的截图。

### 1.3 Flow Metadata 是业务 Flow 能力发现入口

实际接口为 **`GET /api/v1/run/{flow_id_or_name}/metadata`**，不是独立 `/flow/meta`。后端 `FlowMetaResponse` 与 Electron `src/api/flow.js`、`stores/assits/assits-flow-features.js` 证实其同时承担智能体元信息和当前用户可见能力发现：

| 字段组 | 主要字段 | Java SDK 用途 |
|--------|----------|---------------|
| 身份与基本信息 | `id/owner_appid/name/description/enabled/is_chat` | 校验实际 Flow、提供只读智能体信息；不用于替代 worker 身份 |
| 输入与展示元数据 | `max_input_tokens/welcome_message/favicon/logo/avatar/ai_name/sub_head/questions/custom_fields` | 类型化保留供调用方使用；Java 后台不迁移 UI 渲染和 localStorage |
| 能力标记 | `features` | 决定本地理解工具是否出现在 `available_tools`，并向调用方暴露 reasoning/search/MCP 等服务端能力 |
| 知识与扩展 | `knowledges[{code,name,type}]/shadow_flows[{id,name,description}]` | 提供知识范围和影子智能体只读模型，不自动扩大知识或子任务权限 |

- 后端按 Flow 配置、模型能力、知识库、语音开关及用户配额计算 features；可能包含 `reasoning/internet_search/knowledge_search/image_understanding/audio_understanding/video_understanding/document_understanding/image_generation/support_input_optimizing/support_mcp_tools/support_agent_app/support_human_ai_dialogue/support_knowledge`。Java 必须保留未知新值，不能用封闭枚举丢弃前向兼容信息。
- Electron 会按 features 过滤本地 `image_understanding/audio_understanding/video_understanding`，而不是只用于 UI 展示。`knowledge_search` 还要求存在 knowledges；`support_mcp_tools/support_agent_app` 等仍是服务端/扩展能力标记，不代表 Java 自动获得额外权限。
- metadata 按 flow 维度缓存，会话切换不应串用其他 Flow 的元数据。Java 只使用 per-flow 内存缓存和显式刷新，不复制 Electron localStorage 持久缓存，不把元数据写进 workspace/state-dir。
- 当前 Java `ChatRequest` 虽有 `available_tools/reasoning/internet_search/knowledge_scopes` 字段，但没有 FlowMetadata Client；除子任务快照外，`AgentSessionImpl` 也不会自动从 PluginManager 生成 `available_tools`。因此本项必须同时补“元数据查询 + 工具 schema 注册 + 首帧工具过滤”，只增加 GET 封装不能形成实际能力。

证据：`taiyiflow-dev/.../api/v1/endpoints.py:1333-1567`、`schemas.py:2941-2991`、`tests/unit/api/v1/test_run_flow_metadata.py`；`taiyiflow-electron/src/api/flow.js`、`stores/assits/assits-flow-features.js`、`stores/assits/assits-tool-filter.js`。

### 1.4 SKILL 是“发现—过滤—首帧供应—按需加载”的完整链路

Electron 主进程与 Web 两条实现、`taiyiflow-dev` 的 AgentService 共同证实，SKILL 供应不是单纯调用 SkillClient，也不是只把 `requested_skill_names` 放进首帧：

| 环节 | 已核实行为 | Java SDK 必须对齐的语义 |
|------|------------|-------------------------|
| 本地来源与优先级 | 递归发现 `workspace/.snz1dp/skills`、`~/.snz1dp/skills`、兼容只读 `~/.agents/skills`，最后加载 bundled；同名按名称大小写不敏感去重，前面的来源优先 | 支持 workspace/user/兼容/bundled 来源、明确优先级和只读属性；目录可配置，不扫描任意 home/workspace 路径 |
| 文档模型 | `SKILL.md = YAML frontmatter + Markdown`；包含 name/description/category/metadata/files/prerequisites/enabled 等，附属文件可声明在 files | 建立类型化 SkillDefinition/SkillPackage/SkillSource；安全解析 YAML，不实例化任意 Java 对象，保留协议所需未知 metadata |
| 请求过滤 | 按 `enabled`、`metadata.request_types/request_type` 和工具类别可见性过滤；白板技能还随白板工具状态过滤 | 供应与工具能力使用同一策略上下文；技能文本不能反向开放已禁用工具 |
| 首帧字段 | 同时发送 `client_skill_packages` 和 `requested_skill_names`；技能正文只走前者，客户端工具仍走 `available_tools/client_mcp_servers` | ChatRequest 增加强类型包模型；两个字段不可互相替代，不从技能包推导工具权限 |
| 摘要与正文 | Electron 主进程先构建完整包，首帧过滤时 bundled 与 @选中技能发完整正文，其余非内置技能发 `summary + is_summary=true`；Web 对 @选中及默认加载的平台技能按需下载正文，其余只发摘要 | 实现分级供应和按需加载，不无差别上传所有本地 Markdown；显式选择与默认加载都触发完整正文 |
| 名称集合 | 首帧名称由过滤后的技能名、默认加载名和显式 @选择名合并去重；@选择用于补充和触发全文，不是把其他技能全部剔除的白名单 | 区分“本地供应开关”“显式附加名称”“默认加载名称”；null/空数组不擅自解释为关闭服务端内置技能 |
| 服务端解析 | 后端合并请求类型默认内置技能、client_type 技能和显式名称；每个名称按 client package → 平台 DB package → 服务端内置技能解析，缺失名称单独记录 | Java 不重复伪造服务端内置技能；本地包只覆盖同名客户端技能，平台技能仍由平台权威解析 |
| 会话与恢复 | requestedSkillNames 是会话级状态；首帧按目标会话快照过滤，子任务只继承允许的技能名和本地服务白名单 | per-session 选择不可串台；新 start 生成不可变供应快照，attach/resume 不重新扫描导致提示词漂移 |
| 技能工具与安装 | `skill_list/skill_fetch/skill_upload/skill_create/skill_delete`；上传 ZIP 含 SKILL.md 与附属文件，安装有路径逃逸检查；bundled/兼容目录不可删除 | 读取与变更分权；平台同步、安装、更新采用显式策略，DigitalWorker 无 UI 时不得假装完成人工确认 |

特别注意：Electron `summarizeSkillBody()` 的“后端仅接收摘要”注释描述的是渐进式披露意图，不代表所有请求都只发摘要；实际 `buildClientPackages()` 先携带完整 body，随后 `filterSkillPackages()` 再按 bundled/@选择降级。Java 应以实际首帧结果为准，不照搬矛盾注释。后端 `client_skill_packages` 还兼容对象/数组/JSON 字符串/存储路径，SDK 首期只产出类型化数组，不为了兼容而增加字符串协议分支。

证据：`taiyiflow-electron/src-electron/agent-runtime/skill-tools.js`、`src/stores/assits/{assits-skill-filter,assits-skill-sync,assits-chat-core,session-state}.js`、`src/stores/subtask-stream-store.js`；`taiyiflow-dev/.../api/v1/schemas.py:1854-1901`、`services/agent/service.py:556-612,718-875,961-994`。

### 1.5 Java 当前差距与前置缺陷

| 项目 | 当前代码事实 | 对实施的影响 |
|------|--------------|--------------|
| `/config` | `ConfigClient` 只返回 `JsonNode` | 需要类型化模型和必填 Flow ID 校验 |
| Flow Metadata | 没有 `/run/{flow}/metadata` Client、类型化模型或 per-flow 缓存 | 需要提供 Flow/Agent 元信息和 features 能力发现，且按 flow 隔离 |
| 首帧工具清单 | `ChatRequest.available_tools` 仅由调用方或子任务快照显式设置，PluginManager 只有 handler、没有工具 schema 清单 | 需要工具定义注册和按 Flow features/requestMode 自动过滤；不能让已注册工具天然等于已对模型开放 |
| 默认业务 Flow | `TaiyiAgent.Builder.build()` 强制要求 `flowId` | 需要显式、可审计的服务端默认 Flow 绑定方式 |
| 非流式运行 | `RestClient.post()` 只面向 JSON，无通用同步/异步 FlowRunClient | 需保留原始正文、Content-Type、Session Header、HTTP 202 与任务状态 |
| 文件上传 | `FileClient` 使用 `Files.readAllBytes(file)` | 大音视频上传前需改为流式 multipart，并执行服务端大小/数量限制 |
| SKILL 供应 | `ChatRequest` 只有 `requested_skill_names`，没有 `client_skill_packages`；SkillClient 只有平台 REST；RunContext/子任务快照没有技能包快照 | 需要本地/内置发现、分级正文、平台按需内容、首帧快照、会话选择、技能工具和安全安装，不能只增加一个 REST 方法 |
| 工具工作区 | `ToolExecutionContext` 有 `workspace` 字段，但 `AgentSessionImpl` 构建上下文时未注入 | 媒体与浏览器落盘前必须补齐 agent/worker workspace 传播 |
| worker 内置工具 | `DigitalWorker.newAgent()` 只接子任务编排，未像 starter 一样注册 Shell/File/Search | 需统一显式装配入口，避免 standalone、worker、starter 能力漂移 |
| 浏览器 | 没有浏览器模块或实现；`PermissionPolicy` 中已有 `taiyiflow-in-chrome__` 分类 | 该权限分类只是超前配置，不代表功能已存在；实现时必须沿用并补安全测试 |
| 部署 | `deploy/worker.yml` 无浏览器配置，Dockerfile 无 Chromium；容器以非 root 运行 | 浏览器默认关闭；镜像须预装固定版本浏览器及依赖，保持非 root，不默认加 `--no-sandbox` |

### 1.6 Electron 能力取舍

| 能力组 | 处理决定 | 原因/边界 |
|--------|----------|-----------|
| 四类媒体理解、截图后理解 | **对齐** | 纯后台能力，平台已有完整协议 |
| 本地无头浏览器、远程浏览器 | **新增** | Java 后台可承载；采用标准浏览器自动化和 CDP |
| Schedule、session_context、瞬态 todo | **高价值适配** | 可复用现有 Client/上下文；不引入本地权威任务历史 |
| SKILL 发现、首帧供应、按需加载、管理与同步 | **完整对齐后台适用链路** | 同时覆盖本地/内置与平台技能；不迁移 UI，不把技能文本当工具授权 |
| Shell/File/进程工具名称兼容 | **适配别名** | 降低 Flow 工具迁移成本；本地 process/task 句柄不得冒充平台任务 |
| `ask_user` | **仅 SPI 按需适配** | Electron 渲染进程 UI 不可照搬；没有交互实现时不注册工具 |
| MCP、后台进程、受控子任务、文件编辑 | **不重复建设** | Phase S–Z 已实现，仅做名称或接线核查 |
| internet/knowledge search、图片生成、Flow MCP/Agent App | **保持服务端 Flow 能力** | 属于 Flow features，不在 Java 再实现一套模型或服务 |
| Vue/Pinia/IndexedDB、BrowserWindow/WebContentsView | **排除** | UI 与桌面壳专有 |
| `taiyiflow-computer__`、桌面录屏/桌面元素控制 | **排除** | 不属于无头后台浏览器，安全边界也不同 |
| `chrome_session` Native Messaging/Chrome 扩展桥 | **排除** | 依赖 Electron CLI Native Host 和用户本机状态；远程模式只用标准 CDP |
| Electron 本地 `get_sso_token` | **排除** | 不把 WorkerAuth 凭证暴露给模型或普通工具结果 |
| 自动 loop decide、语音 dialogue | **本计划不启用** | 需要单独需求、协议与副作用审查 |

## 二、待审查的架构决定与非目标

1. **浏览器技术方案**：拟使用 Playwright Java，新建独立 `taiyiflow-sdk-browser` 模块，将驱动依赖、浏览器二进制和生命周期隔离在可选模块中；core 不依赖 Playwright。该选型需批准后实施。
2. **运行模式**：`DISABLED | HEADLESS | REMOTE_CDP`，默认 `DISABLED`。HEADLESS 由 SDK 管理完整浏览器生命周期；REMOTE_CDP 连接外部 Chromium，不接管用户既有浏览器。
3. **浏览器版本**：首期只产品化 Chromium。保留 Electron 的 `taiyiflow-in-chrome__` 工具前缀和同一套工具名，HEADLESS/REMOTE_CDP 共用，不另造一套远程工具协议。
4. **默认 Flow**：`.flowId(...)` 可选，未设置或 blank 时自动读取 `/config.default_flow_id`；保留显式 `useServerDefaultFlow()`，与 `.flowId(...)` 互斥。解析失败必须明确报错，不降级到任意 Flow。DigitalWorker 接到任务时仍要求任务 flowId 明确，禁止默认 Flow 把任务路由到错误智能体。
5. **工具覆盖**：首期对齐 17 个实际浏览器操作工具；`set_browser_type` 在仅有 Chromium 产品化时不伪装支持多引擎，待确有 Firefox/WebKit 需求再单独实施。
6. **网络安全**：默认限制 `file:`, `javascript:`, `data:` 等本地/脚本导航，仅允许 `http/https/about:blank`；内网、环回、链路本地与云元数据地址默认拦截，企业内网目标通过明确 allowlist 开放。远程浏览器还需部署侧网络出口策略，SDK 页面拦截不能冒充完整网络边界。
7. **不引入第二权威**：浏览器标签、截图、Todo、控制台和临时文件都是会话瞬态状态；任务、结果、产物、审批与记忆仍以平台为权威。
8. **不自动重放副作用**：CDP 断连、页面崩溃、异步 Flow 提交结果未知时，只查询/对账或报未知，不自动重复 navigate/click/type、上传整批文件或重新提交理解任务。
9. **能力发现分层**：`/config` 提供全局默认 Flow 与媒体执行配置，`/run/{flow}/metadata` 提供当前业务 Flow/当前用户的能力标记；两者不可互相替代。features 用于工具供应和只读展示，不替代 PermissionHook、Worker 身份或平台鉴权。
10. **SKILL 是不可信提示内容，不是授权凭证**：本地技能根目录只保存显式配置的技能内容，平台技能包与安装记录仍以服务端为权威；SDK 不把所有技能正文、环境变量或工作区文件默认注入请求，技能中的工具名、`requires_env` 和指令均不能扩大 available_tools、PermissionHook、沙盒或子任务权限。

## 三、阶段总览与依赖

| 阶段 | 优先级 | 内容 | 依赖 | 本地验收重点 |
|------|--------|------|------|--------------|
| **Phase AA** | P0 | 类型化 `/config` 与 Flow Metadata、默认 Flow 自动回退、工具 schema/首帧过滤、通用同步/异步 FlowRunClient、workspace 与内置工具接线、流式上传 | Phase G–Z | 协议字段、per-flow 能力隔离、available_tools 按 features 过滤、原始响应、202/轮询/取消、上下文隔离 |
| **Phase AB** | P0 | 文档/图片/音频/视频理解及截图理解公共服务 | AA | 双 Flow 调用链、tweaks、扩展名/大小限制、同步/异步结果、超时取消 |
| **Phase AC** | P1 | Playwright Chromium 本地无头浏览器、17 个工具、会话隔离、截图理解、安全与资源生命周期 | AA；截图理解依赖 AB | Page/Context 隔离、工具契约、URL/文件安全、并发串行化、清理 |
| **Phase AD** | P1 | 标准远程 CDP、外部实例归属、凭证脱敏、断连与资源语义 | AC | 不接管既有标签、不关闭外部 Browser、凭证不泄漏、断连不重放 |
| **Phase AE** | P2 | Schedule/session/todo/工具别名及可选 ask_user SPI | AA；复用既有 Client 与 S–Z | 无重复实现、无权限扩大、无本地权威历史、无 UI 依赖 |
| **Phase AF** | P0 | 完整 SKILL 供应：多来源发现、首帧包与名称、摘要/全文、会话快照、技能工具、平台同步与子任务继承 | AA；复用 SkillClient 与 S–Z | 来源优先级、协议字段、会话隔离、恢复稳定、路径/ZIP 安全、默认不提权 |
| **Phase AG** | P0 | worker/starter/Docker/示例/文档、可选依赖装配、完整测试矩阵和真实环境验收 | AA–AF 中批准实施的范围 | 默认关闭、非 root 镜像、配置生效、全量回归、真实平台/CDP/技能验收 |
| **Phase AH** | P0 | 首帧请求模型补齐（差距报告 §2.1 九字段）、RequestDefaults 值对象、client_instructions 加载器、models/session-groups REST 前置配套、三层入口首批 | AA–AG；差距报告行动 #1/#2/#4/#11/#14 | 协议字段逐项对照、快照不漂移、指令披露边界、三层入口一致 |
| **Phase AI** | P0 | watchdog 超时治理（长工具误判修复）、tool-calls 对账封装、notification.v1 诚信（实现或撤声明）、agent_loop.resume.v1 事件族、子任务取消契约 | 无前置（可与 AH 并行）；行动 #3/#13/#15/#16 | 媒体/子任务时长不误判、dead-letter 对账闭环、取消幂等与迟到短路、capability 与实现一致 |
| **Phase AJ** | P1 | 多会话并行 resume + 租约锁 + 回调补偿口径、AUTO 档高危 deny 清单与 opaque 命令策略、审批超时≠DENY、PermissionRule 细粒度与浏览器独立权限 | AI 同链路协同（权限部分可独立先行）；行动 #18/#19/#11 | 并行恢复不阻塞、高危命令 deny 矩阵对照、timeout 显式结果、规则不被反向修改 |
| **Phase AK** | P1 | 浏览器参数别名兼容、set_browser_type 显式不支持、search_files 完整 schema 别名、白板技能过滤、run_subtask 远程 detached + 模型继承 | AI 取消契约（detached 依赖）；行动 #5/#6/#8/#17 | 别名映射不放宽安全、白板技能不供应、detached 幂等对账、本地恒等待维持 |
| **Phase AL** | P2 | 配置矩阵剩余项、/config 配额消费与新键容忍、client_type/Shell 超时硬编码解除、REST 配套补齐、Dashserv 部署文档、扫描归属/占用提示口径 | AH/AI 同批配套，可拆分；行动 #7/#9/#12/#20/#21 | 配额合并语义对照、三层配置全矩阵一致、文档不伪装支持 |

实施主线为 **AA → AB → AC → AD → AG**；AE 可在 AA 后并行，AF 可在 AA 后优先于浏览器实施，二者均不得阻塞媒体主链路。若审查不批准独立 browser 模块或 Playwright，AC/AD/AG 的浏览器部分暂停，AA/AB/AE/AF 仍可独立交付。

**差距闭环主线（2026-11-21 新增）：AH ∥ AI → AJ ∥ AK → AL**。AH/AI 为 P0 可立即并行启动；AJ 权限部分不依赖 AI 可先行，恢复部分与 AI 的 resume 事件族同链路联调；AK 的 detached 子任务必须待 AI 取消契约落地后启用；AL 按需求拆分、不阻塞 P0/P1。真实环境验收 1–9 项（§五）对 AH–AL 同样适用，新增字段/事件/工具的实际业务影响须在真实 Flow 上验证（后端 `extra="allow"` 不报错）。

## 四、详细实施计划

### Phase AA：默认 Flow、Flow Metadata 与非流式运行底座

**目标**：形成能力发现、媒体和后续 Flow 扩展共用的协议底座，同时修复 workspace、上传内存、首帧工具清单和 worker 工具接线问题。

**实现**：

1. 将 `ConfigClient` 增加类型化 `ServiceConfig`，至少映射 `stream_protocol/default_flow_id/doc_unstructured_flow_id/image_flow_id/audio_flow_id/video_flow_id/doc_exts/max_file_size_upload/once_max_file_count`；保留原始 JSON 访问以兼容其他字段。必填字段缺失或空白时返回明确配置错误，不在 Java 填后端 UUID。
2. 新增 `FlowMetadataClient.get(flowIdOrName)`，调用 `GET /api/v1/run/{flow_id_or_name}/metadata`。类型化 `FlowMetadata` 覆盖后端 `FlowMetaResponse` 全部字段，并为 Knowledge/ShadowFlow 建立只读模型；`custom_fields` 以 JsonNode 保留，未知顶层字段进入受控 additionalProperties/raw JSON 访问，不能用封闭模型拒绝前向兼容或丢弃新 feature。
3. `FlowMetadata` 提供 `supports(String feature)` 及常用便捷判断，不使用封闭枚举拒绝新 feature。`reasoning/internet_search/knowledge_search/image_generation/support_mcp_tools/support_agent_app` 等只表示平台能力；SDK 不据此自动开启请求、自动启用 MCP/Agent App 或绕过权限。
4. `TaiyiAgent` 提供 `getFlowMetadata()` 与 `refreshFlowMetadata()`：默认 Flow 先解析实际 flowId 再查询；按 agent 绑定的 flow 做 single-flight 内存缓存，DigitalWorker 的 per-flow agent 天然隔离。刷新只影响后续新 start，attach/resume 沿用 RunContext 保存的首帧工具快照，避免恢复时能力清单漂移。
5. 启用自动工具清单时，metadata 获取失败必须让本次新 start 明确失败，不得退化为“全量能力可用”或静默使用其他 Flow 的缓存；纯显式 `available_tools` 的既有高级调用保持兼容，但本地理解工具仍须通过 features 校验。404/403/400 保留原始错误分类，不重试到另一个 Flow。
6. 扩展 PluginManager 的工具定义注册：新增不可变 `ToolDefinition{name,description,input_schema}` 和 `register(definition,handler)`，内置工具同时注册 handler 与 schema；既有 `register(name,handler)` 保持可执行，但没有定义的 handler 不自动广告给模型。禁止用空 schema 或猜测参数伪造工具定义。
7. `AgentSessionImpl` 在新 start 且调用方未显式提供清单时，从已注册 ToolDefinition 生成 `available_tools`，依次应用 enabled 配置、FlowMetadata features、requestMode/allowedTools 与本地只读静态分类；PermissionHook 仍在执行时独立生效。显式清单不得广告不存在的本地 handler；仅过滤本地内置理解工具，不影响 MCP 服务中的同名工具。
8. Flow features 与子任务 `features{requested_skill_names,client_mcp_server_names}` 是不同协议：前者来自平台 FlowMetadata，后者是子任务能力快照。RunContext 可保存首帧工具定义快照，但不得把 FlowMetadata 全量复制进子任务 command_payload、workspace 或日志。
9. `TaiyiAgent.Builder` 的 `flowId()` 改为可选；未设置或 blank 时自动使用服务端默认 Flow，保留显式 `useServerDefaultFlow()` 且与 `flowId()` 互斥。构建时使用同一 Base URL、认证和超时配置读取 `/config`，解析失败则构建失败并说明原因，不降级到任意 Flow。解析后的实际 flowId 可由 `getFlowId()` 观察。
10. DigitalWorker 的派单路径继续保持 flowId 必填；应用/standalone 与 starter 未配置 flowId/flow-id 时自动走服务端默认值，无需再显式配置 `use-default-flow=true`。
11. 新增 core 内部/公共只读 `FlowRunClient`：
    - 同步 `run(flowId, request)` 调用 `POST /api/v1/run/{flow}`，返回 HTTP 状态、原始正文、media type、`session_id`、outputs 和响应 Session Header；文本响应不强制转 JsonNode。
    - 异步 `submitAsync()` 只接受后端 202 和有效 task_id；`poll()` 处理 pending/running/success/failed/cancelled；`cancel()` 使用对应任务端点。
    - 单次 HTTP 请求超时与整体运行超时分离；轮询间隔、整体超时和取消等待可配置，取消/关闭时停止后续轮询。
    - 提交响应丢失或 task_id 未知时返回 `SUBMISSION_UNKNOWN` 类错误并进入对账，不自动重新 POST；已有任务只允许按 task_id 查询或取消。
12. 为 `RestClient` 增加受控原始响应模型和 per-request timeout，不改变现有 JSON Client 行为；URL path 参数继续正确编码，错误保留 HTTP status 与结构化 detail，日志不记录令牌、完整 metadata/custom_fields 或媒体正文。
13. `FileClient` 增加基于 Path 的流式 multipart 上传，避免 `Files.readAllBytes` 载入整个音视频；保留现有签名兼容入口但内部复用新实现。上传前校验普通文件、大小和数量，读取失败不生成半截合法成功结果。
14. `TaiyiAgent` 增加 workspace 配置并在 `AgentSessionImpl` 创建 `ToolExecutionContext` 时注入；DigitalWorker 将既有 worker workspace 传入每个 per-flow agent，starter 使用既有 sandbox/workspace 配置。未配置 workspace 的媒体/浏览器工具明确失败，不回退到进程任意当前目录。
15. 抽取受限的 workspace 路径解析能力供 File、媒体和浏览器复用，统一绝对路径归一化、真实父目录、符号链接和越界检查；不要复制三套只有字符串 `startsWith` 的沙盒。
16. 建立 standalone/worker/starter 共用的内置工具注册入口。worker 通过明确配置启用 Shell/File/Search，沿用现有沙盒、命令白名单和 PermissionPolicy；不得因升级而静默给既有 worker 开放 Shell 写权限。

**主要改动位置**：

- `taiyiflow-sdk-core/.../transport/rest/{ConfigClient,FlowMetadataClient,RestClient,FileClient,FlowRunClient}.java`
- `taiyiflow-sdk-core/.../transport/envelope/{FlowMetadata,ToolDefinition}.java`（或同层只读模型包）
- `taiyiflow-sdk-core/.../{TaiyiAgent,core/plugin/PluginManager,core/session/AgentSessionImpl,core/session/RunContext,core/dispatch/ToolExecutionContext}.java`
- `taiyiflow-sdk-plugins/.../BuiltinPlugins.java` 与共享沙盒路径组件
- `taiyiflow-sdk-worker/.../DigitalWorker.java`
- `taiyiflow-spring-boot-starter/.../{TaiyiFlowAutoConfiguration,TaiyiFlowProperties}.java`

**测试与验收**：

- `/config` 与 `/run/{flow}/metadata` 字段、路径和错误分类同后端对照；flow name 正确编码，未知 feature/字段可前向兼容，403/404 不回退其他 Flow。
- 两个 per-flow agent 的 metadata 和工具清单不串用；single-flight 只请求一次，显式刷新只影响新 start，attach/resume 保持原工具快照。
- features 缺失、为空或请求失败时不自动暴露相应本地理解工具；MCP 同名工具不受影响，metadata 不写入 workspace/state-dir。
- 自动 `available_tools` 的 name/description/input_schema 与注册定义一致；handler-only 工具不自动广告，requestMode/PermissionPolicy 不能通过显式清单绕过。
- 默认 Flow 显式启用才生效，flowId 与默认模式互斥，配置缺失/请求失败时无硬编码回退。
- 同步 JSON、同步纯文本、非 2xx、202、各任务状态、轮询超时、取消和迟到响应均有契约测试。
- 两个会话/两个 flow 的 workspace 不串用；越界路径和符号链接逃逸被拒绝。
- 大文件上传测试不把全文件读成单个 byte[]；超出 `max_file_size_upload` 或 `once_max_file_count` 在发请求前失败。
- DigitalWorker 任务缺失 flowId 仍失败；默认工具未显式启用时注册表不扩大。

### Phase AB：文档、图片、音频与视频理解

**目标**：按 Electron 已验证行为实现四类工具和截图理解公共服务，不改变平台 Flow 权威。

**实现**：

1. 在 `taiyiflow-sdk-plugins` 增加媒体理解服务和插件注册入口，注册 `document_unstructured/image_understanding/audio_understanding/video_understanding` 的 ToolDefinition 与 handler。四类工具分别声明 `document_understanding/image_understanding/audio_understanding/video_understanding` requiredFeature，由 Phase AA 在首帧生成时过滤；服务依赖 core 的 Config/FlowMetadata/File/FlowRunClient，不依赖 browser 模块。Java 拟按后端完整语义统一门控四类工具，Electron 当前只过滤后三类这一差异列入审查决策。
2. 工具只从 `ToolExecutionContext` 获取当前业务 flowId、sessionId、workspace 和 cancellation；忽略或拒绝模型参数中伪造的 flow_id/session_id。上传始终使用当前业务 flowId，理解始终使用 `/config` 中对应默认 Flow。
3. 文档工具支持 `path/paths/working_directory`，先一次性校验全部输入再上传：文档与图片不得混合；图片输入引导到 `image_understanding`；本地允许扩展名与服务端 `doc_exts` 取交集，空交集明确报错。
4. 文档使用同步运行，解析 `RunResponse.outputs`；根据实际响应保留结构化 `parsed_output` 或原始 `raw_output`，返回 `count/items/media_type/session_id/uploaded_files`。非 JSON 正文不得被误报为解析成功。
5. 图片使用异步运行并注入 `TextOutput-fXIUJ.output_format=text` 默认 tweaks；音频/视频复用异步执行器但使用各自 Flow ID 与 59 分钟默认整体超时。默认参数可用配置覆盖，配置非法时拒绝，不无限轮询。
6. 多文件上传前完成数量、扩展名、普通文件、沙盒和单文件大小校验，避免校验到一半已产生部分上传。上传或理解中途失败时返回已确认的部分元数据和明确失败阶段，不自动重放整批操作。
7. 异步任务成功返回文本与媒体/会话/上传元数据；failed/cancelled 保留平台错误；工具 cancellation 先 best-effort cancel 同一 task_id 并查询终态，取消结果未知时如实返回，不重新提交。
8. 提供包内/公共 `ImageUnderstandingService` 供 browser 模块复用。截图理解使用专用 Prompt/TextOutput tweaks 和 `passthrough`；普通图片理解不带截图专用提示，避免相互污染。
9. 返回文本和错误均有大小限制与脱敏；不把本地绝对路径、平台凭证或完整上传响应写入日志。上传文件和截图只是临时产物，平台结果仍通过既有工具结果/产物通道回报。

**主要改动位置**：

- `taiyiflow-sdk-plugins/.../media/`：`MediaUnderstandingPlugin`、各媒体 handler、`ImageUnderstandingService`、输入与结果模型
- `taiyiflow-sdk-plugins/.../BuiltinPlugins.java`
- `taiyiflow-sdk-core/.../transport/rest/{FileClient,FlowRunClient}.java` 的必要契约补充
- `taiyiflow-sdk-core/.../hooks/PermissionPolicy.java` 的媒体权限回归，不随意放宽既有 `document_unstructured` 分类

**测试与验收**：

- mock server 严格验证“当前业务 Flow 上传 → 默认理解 Flow 运行”，两类 Flow ID 不得颠倒。
- 分别构造四种 feature 开关，首帧只包含 metadata 允许的本地理解 ToolDefinition；无 feature 时不进入首帧，工具注册本身不等同于对模型开放。
- 文档同步、图片/音频/视频异步路径分别验证；图片默认 tweaks、截图 tweaks 与 Electron 逐项一致。
- 文档/图片混合、未知扩展名、服务端不支持扩展名、空文件、超限文件、多文件部分失败、路径逃逸均有负向测试。
- pending→success、failed、cancelled、轮询超时、取消响应丢失和提交结果未知均不会自动重交任务。
- 截图理解失败时仍保留截图结果并附 `image_understanding_error`；普通图片与截图配置互不串用。

### Phase AC：本地 Chromium 无头浏览器

**目标**：以可选模块提供后台可用的浏览器工具，按会话隔离页面和上下文，并建立资源、网络与文件安全边界。

**实现**：

1. 新建 `taiyiflow-sdk-browser` 并加入根 Maven modules；依赖 core、plugins 与批准版本的 Playwright Java。提供 `BrowserToolProvider/BrowserRuntime` 注册与关闭入口，core 不出现 Playwright 类型。
2. `BrowserSettings.mode=DISABLED` 时完全不启动驱动、不注册浏览器工具；`HEADLESS` 首次使用时启动 Chromium，运行参数显式受控，不默认加入 `--no-sandbox`，不自动下载未固定版本的浏览器。
3. 首期实现以下 17 个工具，名称和主要参数参照 `browser-tools.js`：
   - 上下文/标签：`tabs_context/tabs_create`。
   - 导航与页面：`navigate/set_view_size/wait_for`。
   - 元素动作：`click/type/hover/scroll/select_option/press_key/get_element_info/get_element_ref`。
   - 观察与高级能力：`screenshot/read_text/read_console/execute_js`。
   - `set_browser_type` 本阶段不注册，文档明确 Chromium-only，避免返回虚假的多引擎成功。
4. 浏览器身份键使用可信 `flowId + sessionId`，tabId 由 SDK 生成。每个会话独立 BrowserContext/Page 集合和当前 tab 指针；`tabs_context` 只列本会话页面，任何工具都不能凭其他会话 tabId 操作页面。
5. `get_element_ref` 返回会话/Page 代次绑定的 SDK ref；导航、页面关闭或 DOM 代次变化后旧 ref 失效。不得把任意 Java 对象、完整 DOM 或跨会话 selector 状态暴露给模型。
6. Playwright 句柄只在所属 BrowserRuntime 的有界专用执行器中使用；同会话操作严格串行，共享 Browser 的调用按其实现线程约束串行化。工具线程只等待有界 Future，排队/运行取消、超时和关闭均在 finally 释放调度许可与等待项。
7. 默认页面动作超时 15 秒；`read_text` 默认最多 8000 字符，`read_console` 最多 200 条且有单条/总字节上限；wait、截图、控制台和页面数量都有上限。超限返回截断标记或明确错误，不无界积累内存。
8. 截图只写入当前会话 workspace 的专用瞬态目录，使用 SDK 生成文件名、格式/大小/尺寸限制和真实路径校验。可选截图理解调用 AB 的公共服务；理解失败不抹掉截图结果。
9. 导航只接受 `http/https/about:blank`；对重定向和子资源执行同一 URL/主机/IP 策略，默认阻断本地文件、环回/私网/链路本地和云元数据目标，企业目标通过域名/CIDR allowlist 开放。DNS 解析失败、重绑定或策略无法判定时 fail closed。
10. 下载、文件选择器上传、剪贴板、持久化用户目录和任意本地文件 URL 默认禁用；后续若开放，只能读取/写入 workspace 沙盒并经过 PermissionHook。`execute_js` 除 CONFIRM 审批外还要求独立显式开关，默认关闭。
11. 沿用 `PermissionPolicy` 的 `taiyiflow-in-chrome__` 分类，不把 navigate/click/type/execute_js 降成只读；AUTO 模式也不能绕过独立脚本、下载和沙盒开关。
12. 增加会话真正关闭时的 `onClose` 生命周期通知（默认方法保持兼容），由 SessionManager 在会话移除/shutdown 时恰好调用一次；不能用每轮 chat 的 `onFinish` 误关多轮会话标签。agent/worker 关闭通过 `PluginManager.registerCloseable` 释放全部 Context、Page、Browser、驱动和执行器。
13. 页面崩溃、浏览器崩溃或操作超时后使相关 ref/tab 失效；清理后可由新工具显式建页，不自动重放最后一个 click/type/navigate。浏览器瞬态状态不写 Outbox，也不作为平台任务恢复依据。

**主要改动位置**：

- 新增 `taiyiflow-sdk-browser/.../{BrowserToolProvider,BrowserRuntime,BrowserSettings}.java`
- 新增会话/Page 注册表、URL 网络策略、元素 ref、截图服务和 17 个 handler
- 根 `pom.xml`、`taiyiflow-sdk-core/.../hooks/SessionHook.java` 与 SessionManager 关闭通知
- `taiyiflow-sdk-core/.../hooks/PermissionPolicy.java` 的浏览器权限测试

**测试与验收**：

- 纯单测覆盖配置、URL/IP 策略、会话/tab/ref 归属、限额、取消和生命周期；浏览器集成测试使用固定 Chromium，不在默认单测中联网下载。
- 两个会话相同页面标题/URL 仍不能互查或互操作；同会话多 tab 的 active tab 不影响其他会话。
- navigate/click/type 并发无 Playwright 跨线程访问；超时、取消和页面崩溃后无许可、Future、Context 或驱动残留。
- file/javascript/data URL、重定向到内网、DNS 重绑定、元数据地址、workspace 外截图、任意文件上传和未授权 execute_js 均被阻断。
- read_text/console/screenshot 超限行为明确；会话关闭和 worker 停止后无浏览器进程残留。
- 截图→图片理解成功及理解失败降级均验证；既有 Phase S–Z 工具调度和结果补偿测试无回归。

### Phase AD：远程 CDP 浏览器

**目标**：复用 AC 的工具与会话语义，连接用户/平台提供的标准 Chromium CDP 服务，而不接管外部浏览器。

**实现**：

1. `BrowserSettings.mode=REMOTE_CDP` 使用 Playwright `connectOverCDP`，支持经批准的 HTTP(S)/WS(S) endpoint 和必要 Header；仅 Chromium，不引入 Electron `chrome_session`、Native Messaging、CLI Native Host 或扩展状态文件。
2. endpoint、Header 和查询 token 支持 SecretProvider 名称引用；敏感 endpoint 可整体放 secret。配置模型、worker.yml、日志、异常和 Hook 不输出 userinfo/query/Header 原值，统一使用脱敏后的主机标识。
3. 为每个 SDK 会话新建独立 BrowserContext/Page；默认只暴露本次连接后由 SDK 创建的页面，既有 `browser.contexts()` 和用户标签不可见、不可枚举、不可通过 tabId 采用。若未来需要 adopt-existing，必须另立需求与授权模型。
4. 会话结束只关闭 SDK 拥有的 Context/Page；worker 停止只断开自己的连接并清理本地资源，不发送 Browser.close 协议命令关闭远程 Chrome，不清理其他客户端页面。以真实远程实例验证 Playwright close 语义，若某操作可能关闭外部 Browser 则禁用该 API。
5. 远程连接建立、健康检查、并发和重连均有上限；断连后当前 tab/ref 全部失效。重连只建立新会话资源，不根据 URL/title 认领旧页面，不自动重复导航、输入、点击、上传或 JS。
6. 截图仍写入本地 workspace 并复用 AB；远程页面中的文件路径不映射为 worker 本地路径。文件上传/下载默认继续禁用，不能把远程浏览器路径当沙盒路径。
7. SDK 对远程页面执行相同 URL 与工具权限策略；同时在部署文档要求远程浏览器所在网络配置 egress、DNS、元数据保护和租户隔离。远程服务若绕过 SDK 请求拦截，不能宣称仅靠 Java 完成 SSRF 防护。
8. 远程能力不可用时工具返回明确连接错误，媒体和普通聊天工具继续可用；不重连风暴，不因远程浏览器故障关闭整个 DigitalWorker。

**主要改动位置**：

- `taiyiflow-sdk-browser/.../remote/`：CDP 连接、凭证引用、远程资源归属和断连处理
- AC 的 BrowserRuntime/会话注册表复用，不复制第二套工具
- `deploy/worker.yml` 与 starter 的远程 secret 配置示例（Phase AG 完成）

**测试与验收**：

- 通过 CDP fake/测试替身验证 endpoint/Header 脱敏、资源归属、断开和关闭调用；真实远程 Chromium 由用户提供环境验收。
- 预置外部标签在 SDK `tabs_context` 中不可见；SDK 无法按 ID/selector 操作它。
- SDK 会话关闭和 worker 停止后远程 Browser 仍存活，其他客户端标签不受影响，SDK 自己创建的 Context/Page 被清理。
- 断连重连后无旧 tab/ref 复活、无页面动作重放；secret 不出现在配置快照、日志、异常和工具结果。
- 远程截图与图片理解链路可用；远程路径与本地 workspace 不混淆。

### Phase AE：高价值 Electron 工具适配（可与浏览器并行）

**目标**：复用已有 Java 能力补齐常用工具面，不重复实现 Phase S–Z，也不迁移桌面 UI。

**实现**：

1. **Schedule 工具**：按 Electron `schedule-tools.js` 适配 `schedule_list/create/update/delete/toggle/runs` 到现有 `ScheduleClient`；所有修改操作进入 PermissionHook，回报仍以平台 schedule/run record 为准，不另建本地调度器。
2. **`session_context`**：只返回 ToolExecutionContext 中可信的 flow/session/run/task/message/requestMode/capabilities，以及当前 FlowMetadata 的 `id/owner_appid/features/knowledges` 等只读能力信息；不包含 token、custom_fields 全量内容、其他会话、Electron UI 分组或本地隐私状态。FlowMetadata.features 与 capabilities 分字段返回，不混为子任务能力快照。
3. **瞬态 `todo_write`**：按 session 隔离，限制会话数、条目数、文本长度和内存总量；会话关闭清理。它只辅助当前执行，不写平台任务历史、不在重启后冒充可恢复任务，不与 Collaboration/Schedule task 混用。
4. **名称兼容**：为现有能力增加 Electron 常用别名，包括 `glob_files`、`shell_exec` 和本地后台进程 `task_list/task_output/task_stop/task_delete/task_cleanup`；别名复用同一 handler 和权限，不复制执行逻辑。文档明确这些 task 是 SDK 本地进程句柄，不是平台任务。
5. **权限同步**：把新增写操作别名纳入 `PermissionPolicy.isMutating`，保持现有 Chrome 只读名单不变；别名不能成为绕过 CONFIRM/READONLY 的路径。
6. **可选 `ask_user`**：定义 Java `UserInteractionHandler` SPI，支持 questions/images 的结构化请求、取消、超时和脱敏结果；仅在使用方注入实现时注册工具。默认平台审批通道不能假装支持任意问答，未实现时返回能力未配置或不注册，不创建 Electron 渲染进程。
7. 不重复注册已有 `c_{server}_{tool}`、受控子任务、文件编辑和 process_* 主工具；SKILL 完整供应链路统一由 Phase AF 实现；不实现 get_sso_token、桌面控制或 UI 状态工具。

**测试与验收**：

- Schedule 请求路径/载荷与现有后端 Client 契约一致；失败和重复操作不建立本地权威状态。
- session_context 无跨会话与凭证泄漏；Todo 达上限、会话清理和重启未知语义明确。
- 所有别名与主工具执行同一权限/调度链，READONLY 下没有别名绕过。
- 无 UserInteractionHandler 时工具不出现在 available_tools；取消/超时不会无限占用 ToolRuntime 许可。

### Phase AF：完整 SKILL 供应、技能工具与平台同步

**目标**：对齐 Electron 的本地/内置技能发现、分级正文供应、平台技能按需加载、会话选择、恢复快照和技能管理，同时保持平台权威、最小披露与工具权限边界。

**实现**：

1. 在 core 增加 `ClientSkillPackage/ClientSkillDefinition` 首帧模型，JSON 字段覆盖 `package_name/root_dir/is_bundled/skills[]` 及技能 `name/slug/description/homepage/category/prerequisites/files/metadata/summary/content/is_summary/path/skill_dir/root_dir/source/relative_dir/enabled`；`ChatRequest` 增加 `client_skill_packages`，使用强类型 `List<ClientSkillPackage>`，序列化对照后端数组协议。模型与内部不可变 SkillDefinition 分离，默认省略本机绝对 `path/root_dir/skill_dir`，以逻辑 package/source 标识替代；确有兼容需要时须显式开启并脱敏，不能把用户 home、容器路径或其他会话位置默认发给平台。
2. 定义 `SkillProvider` SPI 与 `SkillRegistry/SkillSupplier`：分别提供 workspace、user、兼容只读目录、bundled classpath 和平台可见技能；`TaiyiAgent.Builder` 可注入 provider、默认加载名和供应策略，零配置时只加载明确启用的 bundled/workspace 来源，不隐式扫描整个文件系统。使用方可以增加受控 provider，但不能借 provider 注入凭证或改变工具权限。
3. 文件系统来源按 `workspace/.snz1dp/skills > ~/.snz1dp/skills > ~/.agents/skills（兼容只读）> bundled` 去重，目录均可配置/关闭；同名使用 `slug/name` 大小写不敏感的稳定键，保持来源优先级和确定性排序。递归跳过隐藏目录、`node_modules` 和越界链接，限制深度、目录项、技能数、单文件与总字节；bundled 通过固定 classpath 资源加载，不依赖进程 cwd，兼容目录和 bundled 永远不可写/删。
4. 使用安全 YAML 解析器解析 `SKILL.md` frontmatter，禁止任意类型构造、别名膨胀和深嵌套；无/畸形 frontmatter、缺少 name、非法 files/prerequisites 时按策略跳过并给出受控诊断，不能让单个坏技能中断整个 worker。保留 `metadata.request_types/request_type` 与未知 metadata 的受控 JsonNode 访问；正文、description、summary 和附属文件均有长度/数量限制。
5. 摘要算法对齐 Electron：取正文首个有效段落，跳过标题、代码块标记和分隔线，清理引用/列表前缀并合并多行，超过 120 字符截断为 `...`。内部索引保留完整 body 与附属文件清单位置，但首帧只选择必要的 content；`requires_env` 只返回缺失变量名，绝不读取或上送变量值。
6. `SkillSupplyPolicy` 统一执行 `enabled`、requestMode、category→工具可见性和调用方 allowedSkills 过滤；request_types 未声明时保留，声明时只供应匹配模式。浏览器等工具被禁用或会话能力快照不包含对应工具时，其专属技能同步移除；不能只隐藏工具却继续注入教模型调用该工具的正文。通用技能不能因名称碰巧相同而被误过滤。
7. 增加会话级 `SkillSelection`，支持显式附加 `requestedSkillNames`、默认加载名和禁用本地供应；选择按 sessionId 隔离，下一轮换 flow/session 不复用旧选择。`null` 表示未指定，空列表只表示没有客户端附加名称，均不宣称能关闭后端按 request_type/client_type 合并的内置技能；完全禁用本地供应时省略 `client_skill_packages` 和客户端贡献的名称，服务端技能行为仍由平台决定。
8. 新 start 由 SkillSupplier 生成不可变 `SkillSupplySnapshot{packages,requestedSkillNames}`：先过滤，再将 bundled、显式 @选择和默认加载技能设为完整正文 `is_summary=false`，其他本地/平台可见技能只发摘要 `is_summary=true`；空包剔除。最终名称是过滤后的技能名、默认加载名与显式名称的稳定去重并集，不把 @选择误做成剔除其他技能的过滤条件。本地正文下载/读取失败时明确降级为摘要或缺失，不伪报全文已供应。
9. 平台 provider 复用 `SkillClient.list/getContent`，按当前认证主体、flowId、sessionId 查询可见技能，默认 `allow_elevated_risk=false`；只对默认加载和显式选择项按需取完整正文，缓存键包含主体/flow/session/skillId/version，有 TTL、大小上限与 single-flight，禁止跨用户、跨 flow 或跨会话复用正文。平台不可达时是否允许仅本地技能继续由显式策略决定，并返回可观察的降级状态，不能静默把平台技能标记为已加载。
10. `RunContext` 保存首帧 SkillSupplySnapshot 的深拷贝；attach/resume 原样复用包、正文摘要标志和名称，不重新扫描磁盘、下载新版本或混入当前会话新选择。显式 refresh/安装/更新只影响后续新 start；多轮新 start 是否刷新由统一策略控制。快照只在内存与既有恢复上下文使用，不写入普通 workspace、任务 command_payload、日志或长期结果库。
11. 实现只读 `skill_list/skill_fetch`：list 只暴露当前上下文可见的摘要、来源、能力和缺失前置条件；fetch 按 `skill_dir > slug > name` 大小写不敏感解析，返回正文与已声明附属文件的受控读取入口。模型不能直接传任意绝对路径，文件读取由 registry 使用内部句柄定位并再次执行沙盒/真实路径校验；ask/plan 可使用只读工具，但不能读取其他会话 workspace、隐藏凭证文件或未声明文件。
12. 实现变更工具与 Java 管理 API：`skill_create` 仅写显式 workspace/user scope，slug 只允许小写字母、数字和连字符，传入 name 必须与 frontmatter name 一致；`skill_upload` 将选定 SKILL.md 和声明的附属文件打 ZIP 调现有平台上传接口；`skill_delete` 区分本地与平台目标，bundled/兼容目录禁止删除。上传、创建、更新、安装、卸载和删除均进入 PermissionHook，READONLY 禁止，高风险操作默认 CONFIRM；平台卸载/安装记录仍以 SkillClient 响应为准。
13. 提供显式 `SkillSynchronizer` 对齐缺失/过期检测、安装记录回报、下载安装和刷新，支持 `DISABLED/CONFIRM/AUTO` 策略且默认 DISABLED；AUTO 也只能安装策略明确允许、非 elevated 的版本，DigitalWorker 无交互实现时 CONFIRM 返回待处理而不后台静默安装。同步不在每次 chat 无条件执行，不阻塞首帧，失败不重复产生安装副作用；平台本来可解析的 DB 技能不为了“对齐”而强制下载到本地。
14. ZIP 创建与解包统一限制条目数、单条/总解压大小、压缩比、路径深度和文件类型；拒绝绝对路径、`..`、Windows drive/UNC、NUL、符号链接/硬链接及真实路径逃逸，先完整预检再落盘，安装写入临时目录后原子移动，失败清理本次临时文件。上传前执行同样内容边界检查，不把整个 workspace、隐藏文件、凭证存储或其他会话文件打入技能包。
15. 技能文本一律视为不可信提示输入：不执行 frontmatter/Markdown 中的命令，不根据 `files/prerequisites/requires_env` 自动开放网络、Shell、File 或环境变量；技能中的工具名只作为文本，工具供应仍由 Phase AA 的 ToolDefinition/available_tools 和 PermissionHook 决定。返回错误、Hook 与日志只含技能逻辑名和来源类别，不包含正文全文、token、本地绝对路径或平台 artifact 敏感地址。
16. 扩展 `SubtaskCapabilitySnapshot`：子任务只能声明父快照有效技能名的子集，不能通过新的 requested_skill_names/client_mcp_server_names 提权；本地技能仅传递该子集所需的最小 ClientSkillPackage 内容，平台/服务端内置技能继续由后端按名称解析。子任务不继承本机路径、环境变量、凭证、其他无关技能正文或可写管理权限；父会话没有供应快照时按空能力处理，不临时全量扫描。
17. standalone、worker、starter 共用同一 SkillSupplier 与策略装配；默认不改变既有显式 `requested_skill_names` 调用，调用方提供完整 `client_skill_packages` 时仍须经过大小、路径和权限校验。Phase AG 再补配置元数据、示例和文档；本阶段不迁移 Pinia/UI、IndexedDB、Electron IPC，也不复制服务端 builtin skills 到 Java 冒充平台权威。

**主要改动位置**：

- `taiyiflow-sdk-core/.../transport/envelope/{ChatRequest,ClientSkillPackage,ClientSkillDefinition}.java`
- `taiyiflow-sdk-core/.../core/skill/`：SkillProvider/SkillRegistry/SkillSupplier、SkillDefinition/SkillSource/SkillSelection/SkillSupplySnapshot、过滤与安全解析
- `taiyiflow-sdk-core/.../core/session/{AgentSessionImpl,RunContext}.java`、`core/subtask/SubtaskCapabilitySnapshot.java`
- `taiyiflow-sdk-core/.../transport/rest/SkillClient.java` 的必要类型化契约与限流补充
- `taiyiflow-sdk-plugins/.../skill/`：五个技能工具、ZIP 与本地变更实现；`BuiltinPlugins.java` 显式注册入口
- `taiyiflow-sdk-worker` 与 starter 仅提供策略/SPI 接线，完整配置和示例归 Phase AG

**测试与验收**：

- `client_skill_packages` 的字段名、嵌套数组、summary/content/is_summary 与后端 AgentService 解析对照；首帧名称并集、大小写去重、request_types、enabled、默认加载和显式 @选择均有参数化测试。
- 构造 workspace/user/compat/bundled 同名及畸形技能，验证优先级、只读属性、确定性排序和单技能失败隔离；摘要输出与 Electron 代表性样例一致。
- bundled/@选择/默认加载发全文，其他技能只发摘要；平台正文缓存不跨主体、flow/session 或 version，下载失败不伪报成功。
- 两个会话的选择、workspace、技能包和恢复快照不串用；安装/refresh 后旧 run attach/resume 不漂移，新 start 才看到新版本。
- 子任务不能新增父快照外技能，收到最小本地包且不含绝对路径/环境值/凭证；ask/plan 不能调用变更工具，AUTO/CONFIRM 不绕过后台权限。
- 符号链接、绝对路径、`..`、UNC、ZIP slip、压缩炸弹、超限正文/文件数、隐藏凭证文件和跨会话读取均被阻断；安装失败无半成品权威记录。
- 技能正文包含工具调用或恶意指令时，不增加 available_tools、不改变 PermissionPolicy、不自动执行命令；日志与异常无正文和敏感路径泄漏。
- mock server 验证平台列表、按需正文、上传、安装记录、更新与卸载；真实平台的可见范围、服务端内置合并、同名优先级和缺失技能由 Phase AG 验收。

### Phase AG：装配、容器、示例与验收

**目标**：将批准的能力以安全默认交付到 standalone、DigitalWorker 和 Spring Boot，不提前宣称真实环境闭环。

**实现**：

1. `taiyiflow-sdk-worker` 增加媒体插件与 SkillSupplier 装配；浏览器通过可选依赖和条件注册接入，保证不需要 browser 的使用方不被迫下载 Playwright 驱动。standalone 仍可直接 `BuiltinPlugins`/BrowserToolProvider 注册，worker/starter/standalone 使用相同技能过滤与首帧快照逻辑。
2. `DigitalWorkerMain/worker.yml` 增加内置工具、媒体超时、browser 和 skills 配置；skills 包含来源开关/显式目录、默认加载名、正文与包限额、平台缓存、同步策略和 elevated-risk 开关，默认不自动安装、不上传、不开放 elevated。远程 secret 只写 SecretProvider 引用，技能目录与 credential/state 目录隔离。starter 使用 `@ConditionalOnClass`/`@ConditionalOnProperty` 装配浏览器和技能供应，提供配置元数据，browser 默认 DISABLED、平台技能同步默认 DISABLED。
3. `deploy/worker.yml` 示例保持 browser 默认关闭，给出 HEADLESS 与 REMOTE_CDP 的互斥配置片段、workspace/state/credential/skills 目录分离，以及网络 allowlist、页面/并发/截图和技能正文/附属文件限额；用户级与兼容技能目录在容器中默认关闭，只挂载明确批准的技能卷。
4. Dockerfile 在构建阶段安装与 Playwright Java 版本锁定的 Chromium 和系统依赖，设置固定 `PLAYWRIGHT_BROWSERS_PATH`，运行阶段保持非 root 和目录最小权限；禁止容器启动时静默联网下载浏览器，禁止默认 `--no-sandbox`。
5. 若提供独立 browser 镜像或 profile，基础 worker 镜像保持精简；browser 镜像只增加浏览器及依赖，不改变 workerId 单实例、认证和恢复机制。
6. 增加最小示例：`FlowMetadataExample`、`MediaUnderstandingExample`、`HeadlessBrowserExample`、`RemoteCdpBrowserExample`、`SkillSupplyExample`；全部通过环境变量读取 Base URL/API Key/Flow ID/CDP secret，示例失败时不自动重放页面动作或技能安装。FlowMetadataExample 展示实际智能体信息、features/knowledges 和最终首帧工具清单，不打印 custom_fields 全量内容；SkillSupplyExample 展示来源过滤、@选择触发的摘要→全文、首帧快照和 skill_fetch，不打印凭证、无关技能正文或本机绝对路径。
7. README/配置文档说明默认 Flow 与四个理解 Flow 的区别、Flow Metadata 与 `/config` 的职责分工、features 与首帧工具过滤、上传使用当前业务 Flow、同步/异步运行、工具清单、Chromium-only 边界、远程资源归属、SKILL 两字段分工、来源优先级、摘要/全文策略、会话选择/恢复、平台同步和安全限制及真实验收要求。
8. 更新 `PermissionPolicy`、ChatRequest/ClientSkillPackage、协议模型和工具注册测试；涉及配置或协议字符串必须逐项对照后端/Electron，不以“编译通过”代替功能验收。
9. 实施完成后按模块执行 `./mvnw test -pl <模块> -am`，收尾执行 `./mvnw test` 和 `./mvnw package`；复杂 Docker 构建、真实平台、真实浏览器和 UI 验证由用户环境执行并提供结果。

**交付配置与回滚**：

- 默认 Flow 回退默认关闭；浏览器默认 DISABLED；execute_js、下载和文件选择器默认关闭；平台技能同步默认 DISABLED，本地技能管理写操作默认需审批。关闭功能后不得残留启动驱动、网络连接、后台同步或正文缓存刷新任务。
- AA/AB 可独立回滚媒体工具注册而不影响既有聊天；AC/AD 可通过 DISABLED 回滚且无需卸载其他模块；AE 别名可独立移除；AF 可关闭自动 SkillSupplier 而保留显式 requested_skill_names 与既有 SkillClient。
- 配置解析失败、浏览器版本不匹配或远程凭证缺失应明确报错，不静默切换到其他 Flow、其他远程实例或有头模式。

### Phase AH：首帧请求模型与项目指令（P0）

> **状态（2026-11-21）：SDK 侧完成，真实验收待执行。** 实现范围：ChatRequest 新增 12 字段（含 reasoning/internet_search 复用既有字段）、RequestDefaults 值对象（fill-only-null、auto-loop 显式 false、image AUTO 不下发、relation_sessions ≤5 fail-fast）、RunContext 请求快照（retry/repeat/自动重启复用，attach/resume 不重发选项）、ClientInstructionsLoader（CLAUDE.md/AGENTS.md/copilot-instructions 及 .local.md、priority 20/25、256KB 跳过/1MB 截断、相对路径披露、默认关闭）、ModelsClient/SessionGroupClient、TaiyiAgent.Builder + worker.yml request/instruction-directories + starter taiyiflow.request/instructions 三层入口。新增/更新单测覆盖协议对照、快照不漂移、披露边界与三层一致性；全仓测试通过。偏差说明：knowledge-scopes/knowledge-docs 三层配置入口已于 AL 补齐（AH 交付时仅 RequestDefaults.builder() 编程入口）；真实 Flow 上字段行为验证（后端 extra="allow"）待执行。

**目标**：补齐 ChatRequest 缺失的能力字段，使数字人不再静默丧失项目指令、自动续跑、图片生成、模型选择、会话分组、关联会话与 AgentApp 能力；建立请求默认值对象与三层配置入口首批。依据差距报告 §2.1/§8.1，行动 #1/#2/#4/#11/#14。

**实现**：

1. ChatRequest 新增九字段（序列化逐项对照后端 schemas.py:1640-1958 与 Electron chat-core.js:689-736）：`client_instructions`、`relation_sessions`（上限 5、空列表不序列化）、`agent_auto_loop_enabled`/`agent_auto_loop_max_rounds`、`image_generation_enabled`、`model`（**序列化名必须是 model**，Pydantic alias model_key 仅服务端兼容）、`group_id`、`agent_app_id`（本地应用语义传 null 走 instructions）、`client_context_dir`。`input_type/output_type` 会话默认 `chat`/`chat`（决策点 14 已定案，Builder 可覆盖，真实环境验证提示词组装差异后生效）。§2.2 字段（memory 双开关、agent_app_owner_id、current_context_dir、runtime_strategy、command_execution_target、max_iterations、agent_continue_windows、agent_inputs、master_flow_id、output_component、principal_id、actor_principal_id）按需暴露，不默认发送；**服务端注入字段 client_ip/user_identity_info 勿传**。协议对照单测同步更新。
2. 新增 `RequestDefaults` 不可变值对象：Builder 持有请求级默认（requestType/reasoning/model/internetSearch/knowledgeScopes/imageGeneration AUTO|ON|OFF/autoLoop/groupId/agentApp），`AgentSession.chat()` 逐次覆盖；attach/resume 沿用 RunContext 快照，不因配置热更漂移（与 SKILL 快照同一原则）。`relation_sessions` 为会话级 API，不进 Builder 全局默认。
3. `client_instructions` 加载器：`Builder.instructions(dirs...)` 显式目录开关，**默认关闭**；AGENTS.md/CLAUDE.md 分层加载 + merged 文本限长；不默认扫描 home；路径披露沿用 §二.10 脱敏边界。auto-loop **默认关闭**、按任务显式开启（决策点 10 已定案，副作用约束见差距报告 §六.3）。
4. 前置配套 REST：`GET /agents/flows/{flow}/models`（model 字段可用范围校验）、`/agents/session-groups` 族（group_id 管理面），与字段同批交付，避免"字段有了但配套查询没有"。
5. 三层入口首批：Builder / worker.yml（execution.default-request-type、model-key、auto-loop.*、instructions.*、group-id、agent-app 等）/ starter 属性同批暴露，默认值与安全语义一致；安全相关项默认取更严格一侧。WorkerTaskHandler 派单参数透传 relation_sessions/group_id/model。

**验收重点**：协议字段逐项对照单测；RequestDefaults 快照不漂移测试；instructions 披露边界（显式目录、限长、不扫 home）测试；三层入口一致性对照测试；真实 Flow 上验证字段实际生效（后端 extra="allow" 不报错，须以行为验证）。

### Phase AI：执行可靠性与平台对账（P0）

> **状态（2026-11-21）：SDK 侧完成，真实验收待执行。** 实现范围：① watchdog 三层可配——`streamIdleTimeout` 默认 1200s（0 禁用）、`toolExecutionTimeout` 缺省自动推导（注册工具最大时长+60s 且 ≥31min，低于下限抬升），Builder / worker.yml `execution.*` / starter `taiyiflow.*` 与 `taiyiflow.worker.*` 默认值一致；② ToolCallRecordClient（list/byCallId 分页，run_id/via_client/order_by 过滤）+ PlatformResultReconciler 三裁决（RECEIVED 删除 / DISCARD_PAUSED 丢弃 / UNKNOWN 保留人工对账），客户端死信与检查点死信按平台权威清理，只分类不重放；③ notification.v1 最小 WS 事件集（deliver/ack/read/fetch_recent/recent，投递自动 ack）+ NotificationClient REST 兜底（recent limit 1–100 / markRead）；④ agent_loop.resume.v1 capability 声明 + resume_requested→resume_notification_ack（accepted 仅首个流事件后回执，already_running/ignored_stale/ignored_stopped），checkpoint_state/origin_available 观察回调，DigitalWorker 虚拟线程处理链路（AgentSessionTaskHandler.resumeAgentLoop），CLI 隧道事件族（persistence_activate/persistence_active/agent_loop.event）显式排除并记录于文档；⑤ 子任务取消契约——pause 受理后才中止在途工具、迟到结果按代次+取消标记+状态短路不回传、resume/attach/restart 单点重置取消，TaskExecutor pending-cancel 登记（有界 LRU）+竞态三查（启动入口/running 回报后开流前/success 回报前），SessionControl 注册同步重放待定取消 + 200ms 有界 await，SubtaskOrchestrator 创建窗口后再查 + 轮询线程可被取消立即中断；远程等待子任务禁止发起方本地 PATCH 终态。新增/更新单测覆盖时长比例缩放、死信对账闭环、取消幂等/迟到短路/竞态守卫、capability 与入站处理一致性、watchdog 三层一致；全仓测试通过。文档同步：agent-and-session / execution-control / collaboration / protocol / digital-worker / deployment / spring-boot / docs/public/worker.yml。偏差说明：真实平台通知投递、跨实例 resume 与媒体 29/59min、子任务 1h 真实时长验收待执行。

**目标**：修复 watchdog 对合法长工具的误判断流；打通 tool-calls 平台对账最后一环；消除 notification.v1 虚标；接线子任务取消契约。依据差距报告 §8.2/§9.2#1/§三/§4.1，行动 #3/#13/#15/#16。

**实现**：

1. **watchdog 超时治理**：`streamIdleTimeout`（0=禁用）与 `toolExecutionTimeout` 三层可配，解除 AgentSessionImpl.java:92-93 硬编码（90s/15min）。工具期超时须 ≥ 注册工具最大合法时长（媒体理解 29/59min、子任务同步等待 1h），复刻 Electron"链超时 ≥ 工具超时+60s 且 ≥31min"原则（runtime-config.js:216-232）；默认 idle 1200s 对齐 Electron、工具期按工具注册表自动推导（决策点 11 已定案）。保留既有工具期保护机制（StreamIdleWatchdog 切换上限）与代次守卫，只修上限与可配性。
2. **ToolCallRecordClient**：封装 `GET /agents/flows/{flow}/sessions/{sid}/messages/{mid}/tool-calls`（分页，run_id/via_client/order_by 过滤）。Outbox 补偿失败进入 dead-letter 与重启对账时，按 run_id+via_client 查询平台已收结果，再决定补报或标记完成；状态未知不重放（AGENTS.md 约束 8/10）。CheckpointToolResultsAdapter 本地比对之上叠加平台权威查询。
3. **notification.v1 诚信**：实现 `notification.deliver/ack/read` 最小 WS 事件集 + `GET /agents/client/notifications/recent`、`POST .../{id}/read` REST 兜底（重启后拉取未读）（决策点 12 已定案：最小实现 + REST 兜底，不走撤声明路径）。禁止"声明而不处理"的中间态存续。
4. **agent_loop.resume.v1**：声明 capability 并实现 `resume_requested→resume_notification_ack` 事件族；`checkpoint_state/origin_available` 作为跨实例接管时机依据按需处理。`agent_loop.persistence_*/agent_loop.event` CLI 隧道事件族显式排除并记录。
5. **子任务取消契约**（对齐 Electron subtask-manager/subtask-stream-store 链路）：远程取消走 `POST /agents/client/collaborations/{id}/cancel` 服务端受理 + request_id 幂等，**禁止本地 PATCH 终态**（会使服务端受理幂等跳过）；在途工具登记 call_id、取消经 cancelTool 中止；abort/父会话暂停后迟到结果短路不回传；executionId=call_id 透传（含 built_in 回退）；建流准备窗口入口+监听注册后同步检查、本地启动前/running 更新后/success 前三查取消的竞态守卫；轮询可被 abort 立即中断。

**验收重点**：媒体 29/59min 与子任务 1h 时长比例缩放测试不误判（真实时长待环境验收）；dead-letter→平台查询→补报/完成闭环测试；取消幂等、迟到短路、竞态守卫测试；capability 声明与入站处理一致性测试；watchdog 配置三层一致。

### Phase AJ：恢复并行化与权限语义（P1）

> **状态（2026-11-21）：SDK 侧完成，真实验收待执行。** 实现范围：① 恢复并行化——`ResumeLeaseRegistry` per-checkpoint 进程内租约（键 sessionId/checkpointId、默认 5min、原子获取/持有者释放/过期接管，单实例容器下等价 Electron Web Locks），三条检查点恢复路径（AUTO 对账、resume(checkpoint)、resume() 发现）统一受保护；RESUMING 建流 60s 无首个流事件按可恢复错误退避重试（对照 RESUME_STREAM_TIMEOUT_MS）；DigitalWorker connection.ready 先 flushReports 再 `recoverDisconnectedSessions` 单飞并行恢复轮（每会话独立虚拟线程、单会话失败计 pending 不中断整轮，MANUAL 下同样生效）；回调补偿口径 400 确定性拒绝不重试、tool_call_paused 丢弃（ClientToolResultAdapter 既有语义补测，CheckpointToolResultsAdapter.failed 分类补测）。② AUTO 档高危守卫——ShellCommandAnalysis 候选命令匹配 + 高危 deny/allow 清单对照 security.js（shutdown/reboot/killall/sudo/halt/poweroff/mkfs*、dd of=/dev/、rm 根/家/未展开变量、fork bomb、反向 shell、敏感文件、下载落盘、eval/env -S 嵌套、深层 shell -c 剥离）；opaque 命令显式决策（AUTO 仍过守卫，其余 ASK 附原因），显式 deny/ask 先于 AUTO 豁免。③ 审批超时语义——ToolDispatcher 超时返回显式 timeout 错误文案（工具名+等待时长+换方案提示，≠DENY），0=永不超时维持，排队失效项清队。④ PermissionGate 细粒度——PermissionSettings 不可变（policy/rules/writeConfirmation/browserPermissionMode/categoryPolicies），PermissionRule.of 归一（非法 action→ASK、空选择器丢弃、deny>ask>allow），WriteConfirmation/BrowserPermissionMode/ToolCategory/PermissionPolicy 配置解析器取更严格一侧；决策顺序对照 evaluateApproval（浏览器/计算机分支优先→类别覆盖→写确认→显式 deny/ask→opaque→显式 allow→AUTO 守卫→非写 ALLOW→ASK）；PermissionHook 新增 PermissionResult(reason)+default evaluate 向后兼容，DENY reason 回传模型；WorkerApprovalHook 以 gate 为主评估器、仅 ASK 进审批通道；TaiyiAgent/DigitalWorker Builder.permissionSettings + worker.yml permission:/tools.browser.permission-mode + starter taiyiflow.permission.*/taiyiflow.worker.permission.* 三层接线，默认值一致（READONLY/CONFIRM/ASK）。已记录偏差：无未分类→confirm 桶（按 isMutating）、守卫恒生效（无沙盒关闭豁免）、不移植 skill_fetch 审查、READONLY 浏览器写 DENY 更严格、OS 沙盒不伪装支持。规则仅配置层可改，技能正文/模型参数不得反向修改（不可变 settings + 配置来源单一）。新增/更新单测：PermissionGateTest 36 项（三档兼容/规则优先级/opaque/写确认/浏览器模式/类别覆盖/防篡改/dispatcher reason）、ResumeLeaseRegistryTest 6 项、AgentSessionRecoveryTest 建流超时重试与租约占用、AgentSessionTaskHandlerTest 重连恢复轮并行与失败隔离、ReportOutboxTest 检查点失败分类、WorkerConfigLoaderTest/AutoConfigurationTest/BuilderTest 三层一致；core 348/worker 71/collaboration 40/plugins 83 全绿。文档同步：hooks / agent-and-session / execution-control / digital-worker / deployment / spring-boot / docs/public/worker.yml。偏差说明：真实平台高危命令矩阵与跨实例租约/并行恢复验收待执行。

**目标**：对齐 Electron 断网恢复并行化与回调补偿口径；补齐 AUTO 档高危守卫与 opaque 命令策略；审批超时语义与细粒度权限规则。依据差距报告 §十一.3/§8.3，行动 #18/#19/#11。

**实现**：

1. **恢复并行化**：多会话并行 resume（单会话异常计 pending 不中断整轮）+ per-checkpoint 租约锁 + 有界退避 + 建流超时；resume 复用原 run_id+checkpoint_id/version。无新协议字段，纯客户端行为；与既有 AUTO attach→resume→start 策略和"状态未知不重放"约束联合审查，不放大副作用重放。回调补偿队列口径：400 确定性拒绝不重试、tool_call_paused 丢弃。
2. **AUTO 档高危守卫**：高危 deny 清单对照 Electron security.js（shutdown/reboot/killall/sudo/halt/poweroff/mkfs*、dd of=/dev/、rm 根/家目录/含未展开变量、fork bomb、反向 shell、敏感文件、下载落盘、破坏性操作、eval 与 env -S 嵌套、深层 shell -c 剥离）；opaque（静态分析无法完全解析：命令替换/变量展开/未闭合引号/heredoc/超深度）命令策略显式决策；**显式 deny/ask 规则先于 auto 豁免**。仅命令白名单不足以等价 Electron AUTO 安全语义。
3. **审批超时语义**：超时返回显式 `timeout` 结果（≠DENY，工具回错误结果提示模型换方案）；支持 0=永不超时；排队失效项清队。permissionTimeout 默认 300s 维持。
4. **PermissionRule 细粒度**：按工具类别（shell/file/browser/skill/schedule）独立 AUTO/CONFIRM/DENY；`browser.permission-mode` 独立于全局；`permission.write-confirmation: CONFIRM|DIRECT`；`permission.rules[]`（工具名/模式→ALLOW/DENY/ASK），PermissionHook 链承载；规则不得被技能正文或模型参数反向修改。保留 AUTO/CONFIRM/READONLY 三档兼容。OS 沙盒不伪装支持，文档明确差异。

**验收重点**：并行恢复与租约锁测试；高危命令 deny 矩阵逐项对照；opaque 判定与豁免顺序测试；timeout≠DENY 测试；三层权限配置一致性；规则防篡改测试。

### Phase AK：工具契约兼容与技能过滤（P1）

> **状态（2026-11-21）：SDK 侧完成，真实验收待执行。** 实现范围：① 浏览器参数别名——type append/press_enter↔clear/submit、select_option text↔label、read_text max_chars↔max_characters、screenshot input_value↔input，`path` 显式拒绝并提示（会话 workspace 收紧维持）；兼容层只做参数名映射，不放宽 URL/网络/execute_js 安全策略。② set_browser_type 返回明确"不支持（Chromium-only）"结果，替代泛化 No handler。③ search_files 完整 schema 别名——纯 Java 对齐 file-tools.js：pattern 必填、caseSensitive 默认 true、isRegexp 默认 false（字面量）、includeHidden 默认 false、outputMode content/files_with_matches/count、offset 分页、maxResults 默认与上限 200、has_more/total_matches 停数语义、SKIP_DIRS/隐藏文件/二进制扩展名+512 字节 NUL 探测、不跟随符号链接、自研 glob 翻译（`**/` 零目录匹配+花括号展开，PathMatcher 不等价）；已记录偏差：不解析 .gitignore。④ 白板技能过滤——SkillSupplier.visible() whiteboard 类（metadata.tool_category 优先）要求至少一个非 whiteboard_open 的 whiteboard_* 工具（对齐 filterWhiteboardSkillsByOpenState 关闭态只注入入口工具的语义），availableTools 为 null 不过滤，browser/chrome 过滤与 elevated-risk 拒绝维持。⑤ run_subtask 远程 detached（决策点 16）——wait_for_completion=false 且非本机目标时创建平台任务后立即返回（detached:true+平台当前状态原样+child/source 标识，不轮询终态，创建响应丢失仍走幂等对账）；detached 不登记父执行取消链，父取消/SDK 关闭不取消其平台任务；子项覆盖顶层（仅显式布尔 false 关闭等待）；本地恒等待维持；批量统计 detached 计为已受理成功；command_payload 携带 wait_for_completion；能力快照新增 model 继承（ChatRequest.model→features.model→子任务首帧 setModel），子任务体恒不传 execution_mode（对照单测断言）。新增/更新单测：SearchPluginTest 21 项、SkillSupplierTest 7 项、SubtaskOrchestratorTest 15 项（detached 立即返回/对账/本地恒等待/父取消不达/批量受理/子项覆盖/model 继承与 execution_mode 不变量）、BrowserRuntimeTest 别名与 path 拒绝；core 356/worker 71/collaboration 40/plugins 95/starter 17 全绿。文档同步：plugins / getting-started / index / skills / digital-worker / execution-control。偏差说明：真实 Electron schema 参数生成对照、白板开关联动与 detached 平台任务续跑/对账验收待执行。

**目标**：消除同一 Flow 下模型按 Electron schema 生成参数在 Java 侧落空的问题；修复白板技能供应缺口；启用远程 detached 子任务。依据差距报告 §4.1/§4.2，行动 #5/#6/#8/#17。

**实现**：

1. **浏览器参数别名**：`type` 兼容 append/press_enter↔clear/submit；`select_option` 兼容 text↔label；`read_text` 兼容 max_chars↔max_characters；`screenshot` 兼容 input_value↔input，`path` **显式拒绝并提示**（会话 workspace 收紧维持，不静默忽略）。兼容层只做参数名映射，不放宽 URL/网络/execute_js 安全策略。
2. **set_browser_type**：返回明确"不支持（Chromium-only）"结果，替代泛化 "No handler for tool"（PluginManager.java:81），避免模型反复重试。
3. **search_files 别名**：完整 schema 对齐 file-tools.js（pattern 必填，glob/includePatterns/excludePatterns/caseSensitive/isRegexp/outputMode/offset/maxResults≤200，返回 has_more/total_matches），复用 SearchPlugin handler 与权限。
4. **白板技能过滤**：SkillSupplier.visible() 按 tool_category↔availableTools 联动过滤（whiteboard 类技能在无白板工具时不供应，对齐 Electron filterWhiteboardSkillsByOpenState 语义）；维持 browser/chrome 过滤与 elevated-risk 拒绝。
5. **run_subtask 远程 detached**：`wait_for_completion=false` 且目标为远程时创建协同任务后立即返回（决策点 16 已定案：AI 取消契约落地后启用；依托平台幂等/对账，不本地伪造）；**本地串行恒等待语义维持**（与 Electron 一致，非缺陷）；子任务继承所选模型（features.model）；子任务体恒不传 execution_mode。

**验收重点**：Electron schema 参数别名落地对照测试；path 显式拒绝测试；白板技能过滤测试（无白板工具不供应正文）；detached 幂等/对账/取消测试；本地等待语义回归。

### Phase AL：配置矩阵收尾与生态配套（P2）

> **状态（2026-11-21）：SDK 侧完成，真实验收待执行。** 实现范围：① AL-1 配置矩阵剩余——knowledge-scopes/knowledge-docs 三层入口补齐（RequestDefaults.builder()/worker.yml request.knowledge-*/starter taiyiflow.request.knowledge-* 与 worker.request.*）；output_language 经后端源码核实**无消费点**，不设字段（如需透传走 tweaks，文档化）；记忆双开关确认服务端消费点存在（schemas.py + components/helpers/memory.py），维持 fill-only-null、缺省不下发由服务端默认。② AL-2 /config 配额消费——ServiceConfig 访问器：max_parallelism_subtask（缺失→本地 4、0→禁用 run_subtask 供应、其余取 min 且上限 32）、client_max_session_concurrency（缺省 3、显式 0 保留）、agent_auto_loop_max_rounds（开启且未显式指定时回落）、max_input_tokens（服务端权威仅访问器不下发）、ontology（非布尔容忍 false）；会话并发守卫仅在 chat() 新执行入口计数（CONNECTING/STREAMING/TOOL_CALLING/PAUSING/PAUSED/RESUMING/RESTARTING，DISCONNECTED 不算），达上限抛 IllegalStateException，resume/attach/retry/repeat 豁免；**/config 拉取失败回落 -1 不限（Electron config 缺失回落默认 3 收紧，Java 选择不阻断后台任务，文档化偏差）**；FlowMetadata features graph_search/ontology 解析容忍，未知键不报错。③ AL-3 硬编码解除——client_type 默认 device + Builder.clientType/worker.yml request.client-type/starter 三层可配（决策点 13），单次请求显式值优先且跨 resume/retry 持久，服务端按 client_type 合并内置技能的影响已文档化（协同 WS client-type=text 有意不变）；Shell 命令超时三层（ShellPlugin.register/BuiltinPlugins.registerAll 暴露 timeout + worker.yml tools.shell.command-timeout + starter plugins.shell-command-timeout/worker.shell-command-timeout，缺省插件 30s）；subtask 装配暴露（Builder subtaskMaxTasks 16/subtaskMaxParallelism 4/subtaskTimeout 1h + yml tools.subtask.* + starter worker.subtask-*）。④ AL-4 REST 配套——六个薄客户端：IntentionClient（run/message 意图，404→null、403 抛出）、ClientDirectoryClient（online-desktops/principals，派发前过滤 can_receive_task=false 已文档化）、QuotaCreditClient（quotas usage/check、credits balance/check；4003 是 WS 关闭码非 HTTP）、PlatformProbeClient（/health 与 /api/health_check 免鉴权 + /api/v1/version）、AgentAppClient/AgentPluginClient（只读目录）；TaiyiAgent +6 访问器、DigitalWorker +3；release.push 收而不报（协同默认分支 debug 日志，容器升级不适用已文档化）；knowledge retry/parse-record 不封装（服务账号恒 403 fail-closed、Milvus 门控，备选已文档化）。⑤ AL-5——Dashserv 网关身份部署文档（X-App-Id 为 consumer 调用身份 code 非应用 code，enabled+app_code 校验与四级服务账号校验、XEAI 禁用即 401、consumer 缓存 60s/请求超时 5s、网关不可达错误传播非 401、gateway_dashserv_* 五项配置、credentialHeaders 直连说明）；扫描 edit 补报按会话快照归属过滤（对齐 hasFileSnapshot：shell 声明 + write_file/file_edit 成功写入经共享 WorkspaceAudit 登记快照，按会话分桶 10000 FIFO，create/delete 不受过滤，前台与后台补扫两通道同口径）；同工作区占用非阻断 WARN（chat 入口、自动续跑/恢复不提示，无协议变更）。⑥ AL-6 文档同步——deployment/spring-boot（修正 knowledge「仅编程入口」过时句）/protocol（client_type 行、/config 配额键、REST 表 +6 客户端）/execution-control（并发配额与 auto-loop 轮次节）/digital-worker（Builder 表 +6 行）/agent-and-session（Builder 表 +4 行、RequestDefaults 语义段）/plugins + deploy/worker.yml 与 docs/public/worker.yml。新增/更新单测：EcosystemClientsTest 6 项（后端契约逐项对照）、AgentSessionConcurrencyQuotaTest 5 项（服务端配额/显式覆盖/0 阻断/失败不限/占用警告/auto-loop 回落）、ShellPluginTest 归属过滤与跨插件快照、WorkspaceAuditTest 会话分桶、三层配置矩阵测试（worker.yml/starter/Builder）；全仓测试通过：core 373/worker 76/plugins 99/starter 19/collaboration 40/browser 13，共 620 项 0 失败；`./mvnw package` 全模块打包通过。偏差说明：-1 不限偏差、output_language 不设字段、knowledge 备选未封装、release.push 收而不报均已文档化；真实平台配额/积分预检、Dashserv 网关 401 矩阵与探针验收待执行。

**目标**：完成 §8 配置矩阵剩余项与硬编码解除，补齐生态配套 REST 与部署文档。依据差距报告 §2.2/§8/§9.2/§十一.2，行动 #7/#9/#12/#20/#21。

**实现**：

1. 配置矩阵剩余：defaultKnowledgeScopes、output_language 透传（确认服务端消费点后定字段）、长期记忆开关（**先确认服务端消费语义**，Electron 未随首帧发送，不复刻不存在的行为）、§2.2 剩余字段按需暴露。
2. **/config 配额消费**：max_parallelism_subtask 服务端收紧合并（**0=禁用 run_subtask 工具供应**）、client_max_session_concurrency、agent_auto_loop_max_rounds、max_input_tokens；`ontology` 新键与 FlowMetadata features `graph_search`/`ontology` 解析容忍（服务端 p_graph_*/p_ontology_* 内置工具为服务端执行，不新增客户端义务；服务账号 fail-closed 属预期）。
3. **硬编码解除**：CLIENT_TYPE 维持 device 默认 + Builder/yml/starter 可配置 + 文档化服务端技能合并影响（决策点 13 已定案）；Shell 命令超时三层打通（BuiltinPlugins.register 暴露 + worker.yml + starter）；subtask timeout/max-parallelism 装配暴露。
4. REST 配套：intention、online-desktops/principals（发起方形态）、quotas/credits 配额预检（避免 4003 后才发现耗尽）、health_check/version（容器探针）、agent-apps/plugins 按需；`release.push` 收而不报显式文档化为容器升级不适用；knowledge retry/parse-record 仅数字人承担知识运维时备选。
5. **Dashserv 网关身份部署文档**：经网关接入的 DigitalWorker，X-App-Id 必须是已注册 Dashserv consumer 且映射到启用应用（gateway_dashserv_* 五项配置），否则 401；XEAI 禁用即 401；认证头本身无变化（X-Credential-Username/Userid、X-App-Id）。
6. 口径项：扫描 edit 补报按会话快照归属过滤（排除外部进程改动，对齐 hasFileSnapshot 口径，若 Phase X 补扫存在）；同工作区占用非阻断日志警告（纯本地状态，无协议变更）。

**验收重点**：配额合并语义对照（含 0=禁用）；三层配置全矩阵一致性；新键容忍不报错；部署文档审查；不预留假配置开关（computer/chrome_session/whiteboard/桌面 prefs 显式标注后台不适用）。

## 五、平台配套、真实验收与责任边界

| 工作项 | 当前结论 | SDK/使用方责任 |
|--------|----------|----------------|
| 默认及四类理解 Flow | 后端已有 `/config` 与内置默认值 | 平台保证线上 Flow 存在、模型/组件可用且 ID 与配置一致；SDK 不创建或硬编码 Flow |
| Flow Metadata | 后端已有 `/run/{flow_id_or_name}/metadata`，按 Flow 和当前用户计算 features | 平台保证 metadata 与实际 Flow 配置、配额一致；SDK 按 flow 缓存并过滤本地工具，不用本地配置伪造能力 |
| 非流式运行与异步任务 | 后端已有同步 run、202、轮询和 cancel | 真实环境验证各状态、错误正文、取消和跨实例任务查询；状态未知不重交 |
| 文件上传 | 后端已有当前 Flow 上传接口 | 平台提供真实大小/扩展名限制；SDK 预检并流式上传，结果以平台返回为准 |
| 工具下发 | 既有 available_tools/client_tool 闭环可复用 | 业务 Flow 需允许相应客户端工具；验证新工具名能进入流内调用并正确回传 |
| SKILL 解析与平台包 | 后端合并客户端包、DB 包与服务端内置技能，并提供列表/正文/上传/安装记录接口 | 平台保证可见范围、风险级别、默认内置合并和同名优先级；SDK 只供应授权的客户端内容，不上传本地绝对路径或伪造服务端技能 |
| 远程 CDP | 不是 taiyiflow-dev 新 REST 能力 | 使用方提供 Chromium/CDP、网络隔离和凭证；SDK 只管理自身连接和页面 |
| 本地 Chromium | SDK/镜像运行时依赖 | 使用方验收镜像架构、系统依赖、非 root 沙盒和资源限额 |
| ask_user | 无通用 Electron UI 可迁移 | 使用方实现 SPI；没有真实交互通道时不作为已交付能力 |
| 平台结果与审批 | 延续 P-RESULT/P-APPROVAL | 媒体/截图/浏览器结果通过既有工具结果与产物通道保存；本地不建结果库 |

真实环境验收至少包括：

1. 不填业务 flowId/flow-id 时自动进入 `/config.default_flow_id`，显式 flowId 仍覆盖默认值；默认 Flow 缺失或解析失败时明确报错，worker 派单缺 flowId 时明确失败。
2. 查询两个能力配置不同的 Flow，核对 metadata 的 id/owner_appid/features/knowledges 和实际首帧 available_tools；同会话 attach/resume 不漂移，跨 Flow 缓存不串用，metadata 故障时不得全量开放工具。
3. 各选一种受支持文档、图片、音频、视频跑通上传与理解；检查实际调用的业务/理解 Flow、输出、平台工具结果与失败提示。
4. 大文件边界、服务端不支持扩展名、理解 Flow 失败、异步取消和平台不可达；不得出现自动重交或本地伪造结果。
5. HEADLESS 完成新建标签、导航、读取、点击、输入、截图和截图理解；两个会话并行验证隔离，worker 停止验证无进程残留。
6. REMOTE_CDP 在预置外部标签的 Chromium 上验证不可见/不可操作；SDK 停止后外部 Browser 与其他标签仍存活；断网恢复不重复动作。
7. 验证内网 allowlist、元数据地址阻断、execute_js 默认关闭、路径逃逸、日志脱敏、READONLY/CONFIRM 权限和会话清理。
8. 用 bundled、本地和平台技能验证来源优先级、@选择/默认加载全文、其余摘要、服务端内置合并与缺失提示；同会话恢复不漂移，两个用户/会话不共享正文，子任务不能增加技能；上传/安装/更新/卸载经审批且 ZIP 与敏感文件检查生效。
9. Docker 非 root 启动、固定浏览器版本、技能卷只读/最小权限、资源上限和关闭回滚由用户验收；本地 mock/单测不能替代这些项目。

## 六、统一测试策略与完成定义

1. **协议对照**：`/config`、FlowMetaResponse、RunResponse、FlowRunTaskResponse、上传字段、异步状态和取消路径与后端代码逐项比对；features→available_tools、媒体 tweaks、SKILL 摘要/全文和首帧名称与 Electron 对照，client_skill_packages 与后端 AgentService 解析对照。
2. **轻量自动化**：配置/路径/权限/状态机/归属/限额/YAML/ZIP/技能过滤与快照使用 JUnit + 手写 stub 或 mock server；文件系统使用 `@TempDir`，不要求本机浏览器或真实平台即可运行默认测试。
3. **浏览器集成**：单独 profile 运行固定 Chromium 测试；失败时区分驱动、浏览器、页面和断言问题，不通过跳过来宣称通过。
4. **并发与资源**：覆盖同会话串行、跨会话隔离、Playwright 线程约束、队列取消、页面崩溃、CDP 断连、技能 single-flight/缓存和 worker shutdown，检查线程、进程、Context、Page、pending Future、正文缓存和临时文件。
5. **安全负向**：路径/URL/重定向/DNS/元数据/脚本/上传下载/跨会话 tab/技能包路径/ZIP 炸弹/凭证日志均有测试；任何别名或技能文本都不能绕过权限。
6. **恢复负向**：提交未知、轮询断连、CDP 断连、工具结果已执行未回传等场景只走对账或既有结果补偿，不重新执行副作用；技能安装或新版本刷新不改变旧 run 的首帧快照。
7. **回归**：保持 Phase G–Z 测试与示例无回归；浏览器可选依赖缺失或 SkillSupplier 关闭时 core/worker 基础功能仍可构建运行。
8. **真实验收**：由用户执行真实 taiyiflow-dev、平台技能、远程 CDP、Docker 和所需 UI/平台检查；文档明确区分“SDK 本地完成”和“真实环境完成”。

每个新增 Phase 只有满足以下条件才可标记“SDK 侧完成”：批准的实现、单测、配置、必要示例和文档均已落地；全量既有测试无回归；安全与资源清理测试通过；真实依赖仍有明确待办；本计划更新状态。不得把本节的规划文字当作能力已实现的说明。

## 七、审查决策点与实施状态

实施前请确认或调整以下决策：

1. 是否批准 **Playwright Java + 独立 `taiyiflow-sdk-browser` 模块**。
2. 是否接受默认业务 Flow 采用**flowId 缺省时自动回退**，保留显式 `useServerDefaultFlow()`，且 worker 派单不回退默认 Flow。
3. 是否接受首期 **Chromium-only、17 个工具、`set_browser_type` 暂缓**。
4. 是否接受远程浏览器只支持**标准 REMOTE_CDP**，不迁移 Electron Native Messaging/Chrome 扩展。
5. 是否接受内网默认阻断、allowlist 开放，以及 execute_js/下载/文件选择器默认关闭的安全基线。
6. Phase AE 是否全部保留，或优先只交付 Schedule、session_context 和名称别名；ask_user 可独立后置。
7. 是否接受 Java 按后端完整语义用 `document_understanding` 同步门控 `document_unstructured`；Electron 当前 features 过滤器只覆盖图片/音频/视频，若要求完全复刻则文档工具可保持仅注册、不按 feature 过滤。**已定案（2026-11-21，随决策点 15）：接受后端完整语义门控。**
8. 是否接受 SKILL 沿用 `.snz1dp/skills` 与可选 `~/.agents/skills` 兼容来源、bundled classpath 资源，并默认省略首帧中的本机绝对路径；若必须逐字段复刻 Electron 的 path/root_dir，需要单独批准路径披露范围。
9. 是否接受平台技能同步默认 DISABLED、elevated-risk 默认拒绝，DigitalWorker 不自动安装/更新；如需 AUTO，应仅对明确白名单和非 elevated 技能开放。

**2026-11-21 差距闭环新增决策点（Phase AH–AL，已于 2026-11-21 逐项确认全部定案，采纳建议默认值）：**

10. **auto-loop 默认关闭**、按任务显式开启（数字人副作用约束，差距报告 §六.3；Electron agent 模式默认开启，Java 取更严格一侧）。**已定案：默认关闭，派单参数/会话级显式开启。**
11. **watchdog 默认值**：idle 1200s（0=禁用，对齐 Electron）、工具期超时按注册工具最大时长+60s 且 ≥31min 自动推导（媒体 29/59min、子任务 1h 不被误判）。**已定案：1200s + 工具注册表自动推导。**
12. **notification.v1 处置**：实现最小 WS 事件集（deliver/ack/read）+ REST recent/read 兜底 vs 先撤 capability 声明后续再实现。**已定案：最小实现 + REST 兜底（AI 阶段）；禁止"声明而不处理"中间态存续。**
13. **client_type**：维持 `device` 默认 + Builder 可配置 + 文档化对服务端内置技能合并的影响 vs 改 `desktop`。**已定案：device 默认 + 三层可配置 + 文档化技能合并影响（AL 阶段），显式定案替代隐式硬编码。**
14. **input_type/output_type 会话默认**：`chat`/`chat`（对齐 Electron）vs 维持后端默认 `any`。**已定案：chat/chat，Builder 可覆盖；真实环境验证提示词组装差异后生效。**
15. **document_understanding 门控（决策点 7 的新证据）**：Electron 已隐藏「文档工具」设置卡（19044fba，SystemSettingDialog.vue:988），服务端文档解析类型配置入口下线。**已定案：维持 Java 按后端完整语义门控文档工具；决策点 7 据此一并定案，Electron 只过滤图/音/视的行为不再作为对齐目标。**
16. **detached 远程子任务启用时机**：AK 阶段、AI 取消契约落地后启用；本地串行恒等待语义维持（与 Electron 一致）。**已定案：AI 取消契约落地后于 AK 启用远程 detached，本地恒等待维持。**

| 阶段 | 当前状态 | 说明 |
|------|----------|------|
| Phase AA | SDK 侧完成 | 默认 Flow、Flow Metadata、首帧工具清单与非流式运行底座已实现并有协议/集成测试 |
| Phase AB | SDK 侧完成 | 四类媒体理解与截图理解公共服务已实现；真实媒体与理解 Flow 待环境验收 |
| Phase AC | SDK 侧完成 | 本地 Chromium 无头浏览器及安全/资源测试已实现；真实 Chromium 待环境验收 |
| Phase AD | SDK 侧完成 | 标准远程 CDP、归属与断连语义已实现；真实外部浏览器待环境验收 |
| Phase AE | SDK 侧完成 | Schedule/session/todo/别名与可选 ask_user SPI 已落地 |
| Phase AF | SDK 侧完成 | SKILL 发现、首帧供应、恢复快照、按需加载、管理同步与安全边界已落地 |
| Phase AG | SDK 侧完成，真实验收待执行 | worker/starter 装配、可选依赖隔离、Docker、12 个示例与文档已落地；全仓测试/打包通过 |
| Phase AH | SDK 侧完成，真实验收待执行 | ChatRequest 12 字段补齐并有后端字符串对照单测；RequestDefaults（决策点 10/14：chat/chat、auto-loop 显式 false、image AUTO）、retry/repeat/自动重启快照复用、client_instructions 加载器（显式目录、256KB/1MB 限长、相对路径披露）、ModelsClient/SessionGroupClient、Builder/worker.yml/starter 三层入口与文档已落地；全仓测试通过。knowledge-scopes/knowledge-docs 三层配置入口已于 AL 补齐；真实 Flow 行为验证待执行 |
| Phase AI | SDK 侧完成，真实验收待执行 | watchdog 三层可配（决策点 11：idle 1200s、工具期自动推导 ≥31min）、ToolCallRecordClient+PlatformResultReconciler 死信对账闭环、notification.v1 最小实现+REST 兜底（决策点 12）、agent_loop.resume.v1 事件族与 worker 恢复链路（CLI 隧道显式排除）、子任务取消契约（pending-cancel 登记、竞态三查、迟到结果短路、轮询可中断、禁发起方 PATCH 终态）；单测与八处文档已同步，全仓测试通过。真实平台通知/跨实例恢复/长时长工具验收待执行 |
| Phase AJ | SDK 侧完成，真实验收待执行 | 恢复并行化（per-checkpoint 租约锁、60s 建流超时、connection.ready 先补传再单飞并行恢复轮、MANUAL 同样生效）、回调补偿口径（400 不重试/tool_call_paused 丢弃）、AUTO 高危守卫与 opaque 命令策略（显式 deny/ask 先于豁免）、审批超时显式 timeout≠DENY、PermissionGate 细粒度（规则/类别/写确认/浏览器模式，PermissionResult reason 回传，三层配置一致，规则防篡改，OS 沙盒差异文档化）；单测与七处文档已同步，core/worker/collaboration/plugins 全绿。真实高危命令矩阵与跨实例恢复验收待执行 |
| Phase AK | SDK 侧完成，真实验收待执行 | 浏览器参数别名与 path 显式拒绝、set_browser_type 显式不支持、search_files 完整契约（纯 Java，不解析 .gitignore 偏差已记录）、白板技能按非入口工具过滤、run_subtask 远程 detached+model 继承（本地恒等待维持，execution_mode 恒不传）；单测与六处文档已同步，core/worker/collaboration/plugins/starter 全绿。真实 schema 对照与 detached 平台续跑验收待执行 |
| Phase AL | SDK 侧完成，真实验收待执行 | knowledge 三层入口、output_language 无消费点不设字段、记忆开关 fill-only-null 确认；/config 配额消费（subtask 收紧/0 禁用、会话并发守卫、auto-loop 轮次回落、max_input_tokens 服务端权威、ontology/features 容忍，-1 不限偏差文档化）；client_type device 默认三层可配（决策点 13）、Shell 超时三层、subtask 装配；六个生态 REST 客户端（intention/目录/配额积分/探针/应用插件目录）、release.push 收而不报、knowledge 备选不封装；Dashserv 网关身份部署文档、扫描 edit 归属过滤（hasFileSnapshot 口径）、同工作区占用非阻断警告；单测与七处文档已同步，五模块全绿。真实配额预检/网关 401 矩阵/探针验收待执行 |

2026-11-14 已按用户指令完成 AA–AG 实施，不再处于方案待批准状态。本地 mock、HTTP/SSE 集成和单元测试只证明 SDK 侧行为；第五节真实环境验收第 1–9 项仍是发布前待办，不得以全仓测试或打包成功替代。

2026-11-21 依据 `review-taiyiflowJavaSdk-capability-gap.prompt.md`（§十一 复核记录）新增 Phase AH–AL，覆盖差距报告行动清单 #1–#21；复核确认后端 Agent 协议面自 11-19 后稳定，新增缺口以 Electron 行为演进（子任务取消契约、并行 resume、审批/高危守卫语义）与 Java 侧查实缺陷（watchdog 超时冲突、白板技能未过滤）为主。AH、AI、AJ、AK、AL 已于 2026-11-21 SDK 侧完成（真实验收待执行）；决策点 10–16 已于 2026-11-21 逐项确认全部定案（采纳建议默认值，旧决策点 7 随 15 定案），引用处已全文同步；真实环境验收对 AH–AL 同样适用。

## 十二、实施缺陷审查与修复记录（2026-11-21）

AH–AL SDK 侧完成后，按用户指令对全部未提交改动做**实现缺陷核查**（区别于 §十一 的协议差距审查），查实约 26 项（3 高 / 11 中 / 约 12 低），已全部修复并补回归测试。全仓六模块测试连续两轮全绿（collaboration 42 / core 402 / plugins 101 / browser 11 / worker 79 / starter 19，共 654）。本节仍属 SDK 侧行为证明，第五节真实环境验收不被替代。

### 12.1 高危（3）

- **H1 租约未贯穿执行期**：`ResumeLeaseRegistry` per-checkpoint 租约改为覆盖整个恢复执行期，流活动续租，终态/暂停/断连转换释放；专用叶子 `leaseLock`（不在 executionLock 获取内嵌套、不跨 I/O 持有）。`AgentSessionImpl` 三处 resume 调用点不再立即释放。
- **H2 恢复通知 dedup 吞重投**：`CollaborationPlugin.handleResumeRequested` 改为原子认领（`seenResumeNotifications.add`）+ handler 异常时移除认领，使平台重投不被吞没；下游租约与 per-key 闸保证重试安全。
- **H3 AUTO 高危守卫绕过**：`DangerousCommandGuard` 补齐附着重定向、`rm` 无尾斜杠、`eval` 嵌套等 6 类绕过。**实证：Electron `security.js` 共享全部 6 类缺口**，故本修复是刻意严于 Electron 的加固（非对齐），数字人副作用约束下取更严格一侧。

### 12.2 中危（11）

- M1 并发原子预留、M2 PAUSED 计数、M3 clientType 快照（retry/repeat/restart 复用首帧快照）。
- M4 JDK WebSocket `sendText` 串行化（`sendLock` + `join`，杜绝重叠发送 `IllegalStateException`）。
- M5a `connection.ready` 重 handler 移出监听线程（taskExecutor 异步，轻量 WS 补传仍同步）；M5b `recover()` 阻塞 REST 探针移出 executionLock（三段式：守卫→锁外探针→锁内复核重守卫，probeError 在锁内裁决）。
- M7 resume accepted-ack 仅在首个流事件后发出（`AtomicReference` ack + CAS，去除每次 resume 新建 hook 的泄漏）。
- M8 detached 创建窗口取消隔离；M9 子任务归属判定 `targetsCurrentClient` 对齐 Electron 双层 AND 语义（忽略 delivery_policy、空 target=本地、平台派发=远程）。
- M11 快照清理；`WorkerApprovalHook` 策略评估 `join()` 加 30s 超时界（超时≠DENY，显式 failedFuture）。
- `TaskExecutor.pendingCancels` LRU 驱逐加 warn（超容量驱逐最旧取消可能放行未检查任务）。

### 12.3 低危（约 12）

- `relation_sessions` 规范化对齐后端校验器（strip 空白、保序去重、上限 5、空收敛 null 省略字段）。
- `/config` 一次失败不再永久缓存：失败负缓存 ~30s 后重试，成功永久缓存（`serverConfigNegativeCacheMs`）。
- 探针鉴权：`/health`、`/api/health_check` 走 `getUnauthenticated`（不带 Bearer/凭证头），`/api/v1/version` 保持鉴权。
- 对账重放过滤：`CheckpointToolResultsAdapter` 混合 RECEIVED/DISCARD_PAUSED 场景仅重放仍未确认（outstanding）条目，剔除已接收/暂停冻结 call_id（paused 丢弃语义）；全 RECEIVED 删除/全 PAUSED 丢弃裁决不变。
- 插件低危：glob 字符类、`select_option` 空 label、search 字段口径注释纠正等。

### 12.4 误报降级（仅文档对齐，不改逻辑）

- **M6 PermissionGate 写分支 DENY→DIRECT→ask**：与 Electron `evaluateApproval`（security.js:1042-1046）一致，非缺陷，维持现状。
- **M10 白板**：Java 后台端白板仅以排除项 + 防御性技能过滤出现，前端白板能力不纳入计划，无需改动。
- **ClientInstructionsLoader 1MB 限长**：按 UTF-16 content 长度计、超限丢弃剩余层（非切片）、merged 头部不计入预算——与 Electron `toPackage`（instructions.js:369-402）逐字一致；仅补 warn 日志，逻辑不变。
- **search_files 字段口径**：Java 代码已正确输出沙盒相对路径（对齐 rg 引擎相对输出，非 node 兜底绝对路径），仅 doc 注释误称绝对，已纠正注释，代码与测试不变。

### 12.5 回归测试新增

- `CollaborationProtocolTest`：handler 异常→平台重投、并发 send 串行化（16 虚拟线程×8 帧，overlaps=0）。
- `AgentSessionTaskHandlerTest`：accepted-ack 不早于首个流事件。
- `TaskExecutorTest`：pendingCancel LRU 驱逐 warn。
- `PlatformReconciliationTest`：混合 RECEIVED 与混合 DISCARD_PAUSED 重放载荷不含已解决 call_id。
- `TaiyiAgentServerConfigTest`：`/config` 失败负缓存窗口后重试成功并永久缓存。
- `PlatformProbeClientTest`：开放探针不带鉴权、version 带鉴权。
- `ChatRequestTest`：`relation_sessions` 规范化与空省略。
- `ResumeLeaseRegistryTest`、`AgentSessionConcurrencyQuotaTest`、`DangerousCommandGuardTest`/`PermissionGateTest` 等随 H1/H3/M1 落地。

## 十三、授权到期处理核查与增强（2026-11-22）

按用户指令核查 SDK 是否考虑平台**产品授权（license）到期**处理，以 `../taiyiflow-dev` 后端为权威。结论：重试安全此前已被动覆盖（4003/403 归非可恢复终态，不会重试风暴），但缺少**专门识别、可操作告警与到期预警**。本次按最小改动补齐，不新增 SPI、不改动平台权威。

### 13.1 后端权威行为（核查依据）

- 授权硬限制守卫 `api/v1/license_guard.py:verify_license_active` 以**入口显式依赖**挂在对话/智能体执行入口（`POST /run/{flow}`、`/run/{flow}/stream`、chat build），到期抛 **HTTP 403**，detail 为纯中文文案（无稳定机器码）：`LICENSE_EXPIRED_DETAIL="产品服务授权已到期，请联系厂商购买授权"` 或 `LICENSE_NOT_STARTED_DETAIL="产品服务授权尚未生效，请核对授权开始时间"`（`settings/utils.py:78-80`）。
- Run WS 入口（`endpoints.py:1212-1220`）到期先推 `error` 事件再关连接：`_send_entry_error_and_close` → `StreamData(event="error", data={error:<中文>, type:"license_expired", persisted:false})`（`processing/process.py:_build_error_event_data`，机器码在 **data.type**），随后 `close(code=4003, reason=<中文>)`。
- 在线终端数超限（license 派生）走 WS `error_code="license_terminal_limit"`（`services/client/service.py:622`）。
- **自救入口天然放行**：`/version`、`/config`、`/settings`、登录不挂守卫。`/config.license` 透出 `{start_time,end_time,user_limit,user_count,terminal_limit,expired,licensed}`（`endpoints.py:1829-1842`、`schemas.py:2799-2812`），契约规定**仅展示/预警**，到期硬拦截在运行入口（`doc/backend/api/运行时配置接口.md`）。`expired` 含宽限期（`LICENSE_GRACE_DAYS=7`）耗尽与授权尚未开始；`end_time=None` 表永久。

### 13.2 SDK 侧增强（最小改动）

- **专门识别**：新增 `transport/envelope/LicenseExpiredException`（镜像后端 `license_expired`/`license_terminal_limit` 机器码与两条中文 detail 常量；`isLicenseCode`/`isLicenseDetail`/`fromCloseReason`；纯 envelope 类，不反向依赖 rest，避免包环）。`StreamEvent.getErrorType()` 读 `data.type`。
- **三通道归一为独立非可恢复终态**（`AgentSessionImpl`）：① 流内 `error` 事件 `data.type` 命中 license 码 → `terminalStreamError=LicenseExpiredException`；② REST/SSE **HTTP 403** detail 命中两条常量 → `asLicenseError` 包装后 `failRecovery`；③ WS **close-only 回退**（未先收到 error 事件）`fromCloseReason` 从 4003 reason 提取。三者均置 `ERROR` 终态、不重试，`StreamHook.onError` 收到可区分类型与可操作中文文案，与配额/权限（generic `RestException`/`RuntimeException`）区分。
- **到期预警访问器**：`ServiceConfig.license()/licenseExpired()/licenseEndTime()` 读 `/config.license`（仅展示/预警，缺省 false/null）。经既有 `config().getServiceConfig()` 可达，与 `quotaCredits()/probes()` 同为**可选预检**模式，不自动强制。
- **数字人启动预警**：`DigitalWorker.start()` 复用已拉取的 `ServiceConfig`，`licenseExpired()` 为真时经**既有 `WorkerHook.onError("license", …)`** 发非阻塞预警（不新增 SPI、不阻断启动——`/config` 为自救入口、授权可恢复）；运行期每个任务对话仍由运行入口 403/4003 硬拦截并归 license 终态。

### 13.3 自救与恢复

- 授权续期后，豁免的 `/config`（`license.expired=false`）与 `/version` 报告恢复；新发起的对话/任务即恢复正常。已终态 `ERROR` 的会话不自动重放（状态未知/副作用约束），由使用方重新发起。
- 协同控制 WS（任务派发）本身不挂 license 守卫；硬拦截发生在任务内的运行/对话执行入口，故 SDK 执行路径已全覆盖。

### 13.4 回归测试新增（24）

- `StreamEventTest`：`getErrorType()` 读机器码与缺省 null。
- `LicenseExpiredExceptionTest`：码/文案/close-reason 识别精确匹配，非 license 不误判。
- `ServiceConfigLicenseTest`：license 摘要/到期/永久 null/缺省/防御性拷贝。
- `AgentSessionLicenseExpiryTest`：SSE 403 到期/尚未开始、流内 `license_expired`/`license_terminal_limit` error 事件 → 独立 license 终态（ERROR、不重试）；非 license 403 与 `quota_session_exceeded` error 事件保持 generic，验证不过度归类。

### 13.5 责任边界

本节为 SDK 侧行为证明（本地 HttpServer 模拟后端两通道）；真实环境授权到期/续期验收仍属第五节用户职责，不被替代。
