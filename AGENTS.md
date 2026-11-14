# AGENTS.md — TaiyiFlow Java Agent SDK

## 项目定位

面向 Java 后台的**数字人（Digital Worker）运行时 SDK**：以 Java 进程实现的智能体作为"数字员工"长期在线——有身份（workerId/角色/能力清单）、可被平台调度接活（协同 WS）、可持续工作（暂停/恢复/重试/重复）、可请示汇报（审批/状态回报）、可自愈（断线恢复/任务补偿）。

同时保留 `TaiyiAgent` 主动调用形态：多会话并行执行、流式消息输出、可观察/可接管的插件化工具执行。TaiyiAgent 业务扩展使用 **Hook 机制**；DigitalWorker 的 workerId 注册/校验与任务接入使用 **Worker SPI**，由 SDK 使用方实现。

与 JSSDK 的区别：JSSDK 解决"浏览器嵌入聊天窗口"；本 SDK 解决"Java 后台数字人本体"及其主动驱动平台智能体的能力。

## 技术栈

- Java 17+ 源码基线；虚拟线程运行需要 Java 21+（开发环境实际为 JDK 25）
- Maven 多模块，使用 Maven Wrapper（**无需本机安装 Maven**）
- JSON：Jackson；WebSocket：JDK `java.net.http.WebSocket`；并行：虚拟线程
- 测试：JUnit 5 + Mockito（Java 25 下 Mockito 无法 mock 部分类时改用手写 stub，参考 `SchedulePluginTest`）

## 常用命令

```bash
./mvnw clean compile        # 编译全部模块
./mvnw test                 # 运行全部单元测试
./mvnw package              # 打包
./mvnw test -pl taiyiflow-sdk-plugins -am   # 单模块测试（-am 连带依赖模块）
```

## 模块结构

```
taiyiflow-javasdk/
├── taiyiflow-sdk-core/            # 核心：门面、Hook SPI、会话管理、工具分发、传输层
│   └── src/main/java/io/taiyiflow/sdk/
│       ├── TaiyiAgent.java        # 门面（Builder 模式），绑定 baseUrl/apiKey/flowId
│       ├── AgentSession.java      # 会话接口：chat()/interrupt()/await()/onXxx()
│       ├── hooks/                 # 4 个 Hook SPI（TaiyiAgent 业务扩展面）
│       │   ├── StreamHook.java            # onChunk/onEvent/onError/onClose
│       │   ├── ToolExecutionHook.java     # before/after/takeover（takeover 非空即接管）
│       │   ├── PermissionHook.java        # ALLOW/DENY/ASK
│       │   └── SessionHook.java           # onStart/onFinish/onInterrupt
│       ├── core/
│       │   ├── session/           # SessionManager（虚拟线程+注册表）、AgentSessionImpl
│       │   ├── dispatch/          # ToolDispatcher：Permission → before → takeover|插件 → after
│       │   ├── plugin/            # PluginManager：ToolHandler 注册与执行
│       │   └── state/             # SessionState 状态机
│       └── transport/
│           ├── envelope/          # 协议模型与事件常量（与后端字符串一一对照）
│           ├── stream/            # StreamClient（WS）、SseStreamClient（SSE）
│           └── rest/              # RestClient（裸 HTTP 封装）
├── taiyiflow-sdk-plugins/         # 内置插件：Shell（白名单+沙盒）、File（沙盒）、Search、BuiltinPlugins
├── taiyiflow-sdk-collaboration/   # 可选协同插件（默认不启用）：CollaborationPlugin、SchedulePlugin
├── taiyiflow-sdk-worker/          # 数字人运行时：DigitalWorker/Main、任务执行、认证、WorkerHook、worker.yml
├── taiyiflow-spring-boot-starter/ # TaiyiAgent 与 DigitalWorker 自动装配及生命周期
└── taiyiflow-sdk-examples/        # Minimal / ToolTakeover / MultiSession / Collaboration 示例
```

## 核心设计约束

1. **扩展面分层**：TaiyiAgent 新增业务定制优先挂 Hook，不在门面堆参数；DigitalWorker 的身份注册/校验和任务接入走 Worker SPI，不硬编码第三方系统。
2. **零 Hook 开箱即用**：默认配置 + 内置插件即可跑通"发消息→收流式回复"。
3. **多会话并行**：一个 `TaiyiAgent` 绑定一个 flow，多会话各一条独立连接，会话间隔离、可各自中断。
4. **协同 WS 是可选插件**：默认不建立被动长连接；仅 `enableCollaboration()` 时启动。
5. **安全默认**：Shell/File 插件沙盒目录 + 命令白名单；PermissionHook 缺省放行只读、拦截写操作。
6. **数字人身份由第三方实现登记与校验**：workerId 与认证主体、角色、能力由 SDK 使用方的第三方身份实现绑定和治理，智能体 Agent 后台不承担 workerId 登记；SDK 规划新增 `WorkerRegistrar` 注册 SPI，并保留必填 `WorkerIdentityValidator`，不内置第三方注册逻辑。认证支持后台 API Key/JWT 与用户 persistentToken + deviceId，后者只用于换发 accessToken。
7. **任务扩展由使用方实现**：`WorkerTaskHandler`、`TaskProgress`、`TaskReporter`、`WorkerHook` 等 Worker SPI 用于任务执行、进度、结果回报和观察扩展；不得绕过 taskId 幂等、并发、取消和平台对账。
8. **仅单实例容器运行**：同一 workerId 只运行一个容器副本，由容器平台心跳检测并快速拉起；SDK 不实现多实例协调或选举代次，重启后以平台任务、检查点和结果对账，状态未知时不得重放副作用操作。
9. **审批默认走平台**：平台事件上报与人工回执为默认通道，本地异步 Hook 为可选通道；本地决策也须回报平台形成审计记录。
10. **记忆与结果均在服务端**：全部记忆走 MemoryClient，任务进度、工具结果、产物、运行检查点和审批回执以平台为权威；本地 workspace 只保存最小执行状态和临时工作文件，不保存权威结果或长期任务历史。
11. **凭证与执行状态隔离**：API Key/JWT/accessToken/persistentToken 不写入普通 workspace；持久令牌仅可放独立加密凭证存储，日志与异常默认脱敏。

## 协议对接（重要：以 taiyiflow-dev 后端代码为准，文档可能过时）

- 对话通道：**SSE `POST /run/{flow}/stream` 或 WS `/run/{flow}/websocket`**，默认由服务端 `/config` 的 `stream_protocol` 决定，调用者可覆盖。
- WS 首帧：`SimplifiedAPIRequest`（input_value/session_id/run_id/message_id/tweaks/files/request_type/...），10s 内发送。
- 消息正文在 **`data.chunk`**；事件类型共 11 种（message/heartbeat/error/close/custom/bytes/tool_call/file_written/client_tool/mcp_tool/built_in）。
- 工具调用走 **`client_tool`/`mcp_tool`/`built_in`** 流内事件；结果回传走 **REST `POST /agents/client/call/result`**（首帧后服务端不再读 WS，禁止 WS 回传）。
- 鉴权：REST 用 `Authorization: Bearer`；WS 用 query `__TOKEN__`；后端身份实际取自 `X-Credential-*`（网关注入）。
- 协同 WS：`{type,event,payload}` 封套；握手传 client-id/device-id（primary_cli 必须 instance_id），收 `connection.ready`；无 register 首帧。
- 服务端实例断连恢复：AUTO 按 attach → resume → 确认原执行不可恢复后自动 start；优先复用 session_id 并换新 run_id，原会话失效才新建会话。状态未知或有不确定副作用时禁止重发；跨实例恢复依赖平台共享状态/故障接管与幂等。

## 开发约定

- 修改协议模型（envelope/EventConstants/CloseCodes）必须同步更新对照单测，与后端字符串逐一比对。
- 新增工具插件：实现 `PluginManager.ToolHandler`，在 `BuiltinPlugins` 提供注册入口，并补沙盒/白名单测试。
- 测试文件放各模块 `src/test/java` 同包路径下；涉及文件系统用 `@TempDir`；涉及进程超时注意先 `waitFor` 再读输出（参考 ShellPlugin 修复）。
- 示例通过环境变量配置：`TAIYIFLOW_BASE_URL` / `TAIYIFLOW_API_KEY` 必填，`TAIYIFLOW_FLOW_ID` 可选（缺省使用服务端默认 Flow）。

## 计划文档

- `.github/prompts/plan-taiyiflowJavaSdk.prompt.md` — 初始规划（Phase A–F 已完成）
- `.github/prompts/plan-taiyiflowJavaSdk-roadmap.prompt.md` — 后续运行时实施唯一权威总计划：协议纠偏、执行控制、数字人形态与运行时增强（Phase G–Z 已完成）
- `.github/prompts/plan-taiyiflowJavaSdk-capability-alignment.prompt.md` — Electron 后台能力对齐独立计划：默认 Flow、Flow Metadata、媒体理解、浏览器与 SKILL 供应（Phase AA–AG 待审查）

## 参考工程

- 后端：`../taiyiflow-dev`（Python，协议权威来源）
- 前端：`../taiyiflow-electron`（Electron 桌面智能体，执行控制与工具运行时参照）
