# Plan: TaiyiFlow Java SDK Electron 后台能力对齐实施计划（Phase AA–AG）

> 创建时间：2026-11-14。本文是默认 Flow、Flow Metadata、媒体理解、浏览器、高价值工具与 SKILL 供应机制的唯一权威实施计划，不再嵌入运行时路线图。
> 实施状态：Phase AA–AG **SDK 侧已闭环**；Playwright Java、独立 browser 模块、flowId 缺省时自动回退默认 Flow、远程 CDP、完整 SKILL 供应及 worker/starter/示例/配置文档均已落地。真实平台、真实 Chromium/CDP、复杂 Docker 与所需 UI/平台验收仍由用户环境执行。
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

实施主线为 **AA → AB → AC → AD → AG**；AE 可在 AA 后并行，AF 可在 AA 后优先于浏览器实施，二者均不得阻塞媒体主链路。若审查不批准独立 browser 模块或 Playwright，AC/AD/AG 的浏览器部分暂停，AA/AB/AE/AF 仍可独立交付。

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
7. 是否接受 Java 按后端完整语义用 `document_understanding` 同步门控 `document_unstructured`；Electron 当前 features 过滤器只覆盖图片/音频/视频，若要求完全复刻则文档工具可保持仅注册、不按 feature 过滤。
8. 是否接受 SKILL 沿用 `.snz1dp/skills` 与可选 `~/.agents/skills` 兼容来源、bundled classpath 资源，并默认省略首帧中的本机绝对路径；若必须逐字段复刻 Electron 的 path/root_dir，需要单独批准路径披露范围。
9. 是否接受平台技能同步默认 DISABLED、elevated-risk 默认拒绝，DigitalWorker 不自动安装/更新；如需 AUTO，应仅对明确白名单和非 elevated 技能开放。

| 阶段 | 当前状态 | 说明 |
|------|----------|------|
| Phase AA | SDK 侧完成 | 默认 Flow、Flow Metadata、首帧工具清单与非流式运行底座已实现并有协议/集成测试 |
| Phase AB | SDK 侧完成 | 四类媒体理解与截图理解公共服务已实现；真实媒体与理解 Flow 待环境验收 |
| Phase AC | SDK 侧完成 | 本地 Chromium 无头浏览器及安全/资源测试已实现；真实 Chromium 待环境验收 |
| Phase AD | SDK 侧完成 | 标准远程 CDP、归属与断连语义已实现；真实外部浏览器待环境验收 |
| Phase AE | SDK 侧完成 | Schedule/session/todo/别名与可选 ask_user SPI 已落地 |
| Phase AF | SDK 侧完成 | SKILL 发现、首帧供应、恢复快照、按需加载、管理同步与安全边界已落地 |
| Phase AG | SDK 侧完成，真实验收待执行 | worker/starter 装配、可选依赖隔离、Docker、12 个示例与文档已落地；全仓测试/打包通过 |

2026-11-14 已按用户指令完成实施，不再处于方案待批准状态。本地 mock、HTTP/SSE 集成和单元测试只证明 SDK 侧行为；第五节真实环境验收第 1–9 项仍是发布前待办，不得以全仓测试或打包成功替代。
