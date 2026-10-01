# Plan: TaiyiFlow Java Agent SDK（多会话并行 + Hook 驱动的智能体能力运行时）

以 `../taiyiflow` Agent 后端为基础，规划 Java 版 SDK。**定位（区别于 JSSDK）**：JSSDK 解决"浏览器应用嵌入聊天窗口"；Java SDK 解决"**Java 后台程序作为调用者，主动驱动智能体并承接其全部能力**"——调用者几行代码即可获得：多会话并行的智能体实例、流式消息输出、可观察/可接管的插件化工具执行；一切扩展点收敛为 **Hook 机制**，确保"极简调用 + 充分可扩展"。

## 定位与边界

- **一句话定位**：面向 Java 后台的智能体能力 SDK——`TaiyiAgent` 实例可多会话并行执行，消息流实时输出给调用者，插件（工具）执行通过 Hook 与调用者交互。
- **三条修正后的核心原则**：
  1. **客户端协同 WS 是可选插件**：默认不建立被动长连接；仅当使用方需要"平台反向下发任务"（无头 CLI 模式）时启用 CollaborationPlugin。主通道是调用者主动发起的流式运行。
  2. **多会话并行**：一个 `TaiyiAgent` 实例绑定一个智能体（flow/agent + 认证），内部 `SessionManager` 管理多个并发 `AgentSession`（虚拟线程 + 会话注册表），会话间隔离、可各自中断。
  3. **Hook 是唯一扩展面**：消息流输出（StreamHook）、工具执行拦截（ToolExecutionHook）、权限审批（PermissionHook）、会话生命周期（SessionHook）——调用者按需挂 Hook，不挂则走内置默认行为。
- **开箱即用语义**：默认配置 + 内置插件集 + 零 Hook 即可跑通"发消息→收流式回复"；每多挂一个 Hook，就多一层定制能力。
- **不做的事**：UI、语音 dialogue 链路、LLM 编排（大脑仍在平台侧）。

## 架构总览

```text
taiyiflow-javasdk/
├── api/                          # 对外门面
│   ├── TaiyiAgent.java           # 智能体实例：builder 创建，绑定 flow + token
│   ├── AgentSession.java         # 一次会话执行：chat() 返回消息流，可中断
│   └── hooks/                    # Hook SPI（全部可选实现）
│       ├── StreamHook            # 消息流输出：onChunk/onEvent/onError/onClose
│       ├── ToolExecutionHook     # 工具执行拦截：before/after/takeover(接管)
│       ├── PermissionHook        # 敏感操作审批：onPermission(allow/deny/ask)
│       └── SessionHook           # 会话生命周期：onStart/onFinish/onInterrupt
├── core/
│   ├── session/                  # SessionManager：多会话并行（虚拟线程池+注册表）
│   ├── dispatch/                 # ToolDispatcher：工具调用统一入口，串接 Hook 链
│   ├── plugin/                   # Plugin SPI + PluginManager（内置/自定义工具注册）
│   ├── lifecycle/                # LifecycleManager：实例启停、优雅关闭
│   └── state/                    # 会话状态机（RUNNING/STREAMING/TOOL_CALLING/DONE）
├── transport/
│   ├── stream/                   # 流式运行（主通道）：WS 双向 + SSE 回退
│   ├── rest/                     # REST 客户端（会话/消息/技能/记忆查询）
│   └── envelope/                 # 消息封套模型 + 事件常量
├── capabilities/                 # 内置插件（默认注册，可移除）
│   ├── shell/                    # ShellPlugin：ProcessBuilder
│   ├── files/                    # FilePlugin：读写/细粒度编辑
│   ├── search/                   # SearchPlugin：文本/正则搜索
│   ├── skills/                   # SkillPlugin：技能注册与同步
│   └── tools/                    # 自定义 ToolHandler 注册入口
├── collaboration/                # 可选插件（默认不启用）
│   ├── CollaborationPlugin       # 协同 WS 被动模式：接收平台下发任务
│   └── SchedulePlugin            # 定时任务回调（依赖协同连接）
└── spring-boot-starter/          # 自动装配：逐插件/Hook 开关
```

## 关键协议对接（已从后端代码核实）

1. **流式运行（主通道）**：`WS /api/v1/run/{flow_id_or_name}/websocket`，连接后 10s 内发首条 JSON（`input_value/session_id/tweaks`）；服务端推 `{"event": "...", "data": {...}}`（message/heartbeat/error/close/custom）；关闭码 1000/4001/4002/4003/4004/4009。SSE 回退：`POST /run/{id}/stream`。**多会话并行 = 多条并发 WS 连接，各自独立 session_id**。
2. **工具调用回调通道（Phase A 需核实的关键点）**：流式 WS 为双向协议（后端文档注明可扩展 cancel/ack），工具调用请求预期以 `custom` 事件下发，SDK 在同一连接回传结果；若后端当前不支持流内工具回调，回退方案为协同 WS 或 REST 回调端点（`/agents` 下的回调工具路由）。
3. **客户端协同 WS（可选插件）**：`WS /api/v1/agents/client/websocket`，封套 `{type, event, payload}`，角色 `primary_cli`/`collaboration_worker`，事件 `task.dispatch/ack/status/result/error`、`tasks.fetch_recent`、`connection.kicked`、`release.push`；心跳 ping/pong，presence TTL 120s；断线重连后需主动 `tasks.fetch_recent` 补拉（后端 `fail_if_offline` 不自动补发）。
4. **REST 辅助面**：`/agents/*`（会话/消息/技能/记忆）、`/client-collaboration-manager`、`/client-schedule-manager`、`/config`。

## Steps

1. **Phase A：Hook SPI 与协议模型冻结。** 定义四类 Hook 接口、`AgentSession`/`SessionManager` 模型、封套与事件常量（与后端字符串一一对照）；**核实工具调用回调通道的实际格式**（流内 custom 事件 vs REST 回调），冻结工具调用协议。阻塞全部后续。
2. **Phase B：传输层。** 流式 WS 双向客户端（首条消息、事件流解析、取消/中断、SSE 回退、关闭码异常映射）+ REST 客户端（鉴权、统一错误）。依赖 A。
3. **Phase C：内核。** `TaiyiAgent` 门面 + builder、`SessionManager`（虚拟线程并行、会话注册表、中断传播）、`ToolDispatcher`（Hook 链编排：before → 执行(内置插件或 takeover) → after）、PluginManager。依赖 B。
4. **Phase D：内置插件与 Hook 语义落地。** Shell/File/Search/Skills/自定义 ToolHandler；验证"零 Hook 默认跑通、挂 ToolExecutionHook 可接管、挂 PermissionHook 可审批"三种形态。依赖 C，插件可并行。
5. **Phase E：可选协同插件。** CollaborationPlugin（被动接收任务模式）+ SchedulePlugin，默认关闭，`enableCollaboration()` 显式开启。依赖 C（与 D 并行）。
6. **Phase F：Starter、示例与文档。** Spring Boot 自动装配（逐插件/Hook 开关）、minimal 示例（3 行代码跑通流式对话）、工具接管示例、协同模式示例。依赖 D/E。

## Relevant files

- `/Users/neeker/工作/技术开发平台/人工智能平台/太乙智启/taiyiflow/src/backend/base/taiyiflow/api/v1/endpoints.py` — 流式运行 WS/SSE 端点、事件与关闭码权威来源。
- `/Users/neeker/工作/技术开发平台/人工智能平台/太乙智启/taiyiflow/doc/websocket-stream-api.md` — 流式协议文档（含双向扩展说明）。
- `/Users/neeker/工作/技术开发平台/人工智能平台/太乙智启/taiyiflow/src/backend/base/taiyiflow/services/client/service.py` — 协同 WS 协议（可选插件用）。
- `/Users/neeker/工作/技术开发平台/人工智能平台/太乙智启/taiyiflow/src/backend/base/taiyiflow/api/v1/agent_client.py` — 协同/定时/回调 REST 路由。
- `/Users/neeker/工作/技术开发平台/人工智能平台/太乙智启/taiyiflow-jssdk/src/lib/ClientMCPServers.js` — 工具注册/分发语义参考。
- `/Users/neeker/工作/技术开发平台/人工智能平台/太乙智启/taiyiflow-jssdk/src/core/plugin/PluginManager.js` — 插件生命周期参考。

## Verification

1. 协议单测：封套解析、事件常量与后端字符串全量对照、流式首条消息超时（10s）与关闭码映射。
2. 多会话并行测试：同一 `TaiyiAgent` 并发 N 个会话，验证会话隔离、独立中断、连接资源回收。
3. Hook 链测试：零 Hook 默认路径、StreamHook 收到全量流事件、ToolExecutionHook takeover 后内置插件不执行、PermissionHook deny 后工具调用返回拒绝结果。
4. 工具回调集成测试：对接本地 taiyiflow 后端，跑通"调用→智能体发起工具调用→插件执行→结果回传→最终回复"全链路（按 Phase A 核实的通道）。
5. 协同插件（可选路径）测试：启用后接收 `task.dispatch`、执行、回报 `task.result`；未启用时确认无被动连接建立。
6. 开箱即用验收：minimal 示例仅填 baseUrl/token/flowId 即运行。

## Decisions

- Java 17+，Maven 多模块；JSON 用 Jackson；WS 用 JDK `java.net.http.WebSocket`；并行用虚拟线程。
- 协同 WS 明确降级为可选插件，默认不连接——SDK 默认形态是"调用者主动驱动"，不是常驻被动端点。
- 消息流输出不引入 Reactive Streams 依赖，`StreamHook` 用简单回调 + 可选 `Flow` 适配器，降低集成门槛。
- 工具执行默认走内置插件；`ToolExecutionHook.takeover` 返回非空即视为调用者接管，内置实现跳过。
- 安全默认：内置 Shell/File 插件默认工作目录沙盒 + 白名单，PermissionHook 缺省放行只读、拦截写操作。

## Further Considerations

1. 工具回调通道是最大不确定性：若流式 WS 的 custom 事件不承载工具调用，需要后端补协议或 SDK 拉起临时回调端点（HTTP server），Phase A 必须先验证。
2. 多会话并行的背压：会话数上限、每会话事件队列积压策略（丢弃 heartbeat、阻塞或丢弃 message）需配置化。
3. 协同插件与主动模式可共存（同一进程既主动调用又被动接任务），但共享 token 与连接数需独立计量。
4. 长期：DialoguePlugin（人机对话 WS）可在同一 Hook 体系上扩展。
