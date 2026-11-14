# TaiyiFlow Java Agent SDK

> 面向 Java 后台的数字人运行时与智能体能力 SDK——`DigitalWorker` 可常驻接活、请示和回报，`TaiyiAgent` 可主动发起多会话并行执行，业务扩展统一通过 Hook 完成。

## 定位

**一句话定位**：Java 后台进程既可作为长期在线的数字员工，也可作为调用者主动驱动平台智能体。

| 维度 | JSSDK | Java SDK（本项目） |
|------|-------|-------------------|
| 场景 | 浏览器应用嵌入聊天窗口 | Java 后台程序主动驱动智能体 |
| 调用方 | 前端页面 | 服务端 / CLI / 微服务 |
| 扩展方式 | 插件注册 | **Hook 机制**（TaiyiAgent）+ **Worker SPI**（DigitalWorker） |
| 会话模型 | 单会话 | **多会话并行**（虚拟线程） |

### 核心原则

1. **客户端协同 WS 是可选能力**：默认不建立被动长连接；仅当需要"平台反向下发任务"时启用协同（`Builder.enableCollaboration()` 或 `DigitalWorker`）。
2. **多会话并行**：一个 `TaiyiAgent` 实例绑定一个智能体（flow），内部 `SessionManager` 管理多个并发 `AgentSession`，会话间隔离、可各自中断。
3. **Hook 是 TaiyiAgent 的唯一业务扩展面**：调用者按需挂 Hook，不挂则走内置默认行为；DigitalWorker 的身份登记/校验与任务接入走 Worker SPI，由使用方实现。
4. **平台是权威**：记忆、任务状态、工具结果、检查点与审批回执以平台为准；本地只保存最小执行状态与瞬态补传 Outbox。

### 不做的事

- UI / 前端渲染
- 语音 dialogue 链路
- LLM 编排（大脑仍在平台侧）
- workerId 登记（由第三方身份系统承担，SDK 不自注册）

## 模块结构

```
taiyiflow-javasdk/
├── taiyiflow-sdk-core/            # 核心：门面、Hook SPI、会话管理、工具分发、传输层、MCP、子任务编排
├── taiyiflow-sdk-plugins/         # 内置工具插件：Shell、File、Search、媒体理解与 SKILL 管理
├── taiyiflow-sdk-browser/         # 可选浏览器工具：无头 Chromium、远程 CDP、访问与资源限制
├── taiyiflow-sdk-collaboration/   # 协同 WS 插件（零 core 依赖，可独立使用）：CollaborationPlugin、SchedulePlugin
├── taiyiflow-sdk-worker/          # 数字人运行时：DigitalWorker、任务执行与对账、认证、worker.yml 容器入口
├── taiyiflow-spring-boot-starter/ # Spring Boot 3 自动装配：TaiyiAgent 与 DigitalWorker
└── taiyiflow-sdk-examples/        # 12 个可运行示例
```

依赖方向：`collaboration` 不依赖 core；`core` 依赖 `collaboration`；`plugins`/`worker`/`starter` 依赖 `core`。

## 快速开始

### 环境要求

- Java 17+（源码与编译基线）；**运行需要 Java 21+**（会话与任务执行使用虚拟线程）
- 无需本机安装 Maven，使用仓库内 `./mvnw`
- 可访问的 TaiyiFlow 后端服务

### 引入依赖

```xml
<dependency>
    <groupId>io.taiyiflow</groupId>
    <artifactId>taiyiflow-sdk-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- 需要本地工具执行时再引入 -->
<dependency>
    <groupId>io.taiyiflow</groupId>
    <artifactId>taiyiflow-sdk-plugins</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 最简示例（流式对话）

```java
// 1. 创建智能体实例
TaiyiAgent agent = TaiyiAgent.builder()
    .baseUrl("http://localhost:8080")
    .apiKey("your-api-key")
    .flowId("my-agent-flow")
    .build();

// 2. 发起会话 + 挂 StreamHook 接收流式回复
AgentSession session = agent.newSession();
session.onStream(new StreamHook() {
    @Override
    public void onChunk(String sessionId, String chunk) {
        System.out.print(chunk);
    }
});

// 3. 发送消息
session.chat("你好，请介绍一下你自己");
session.await();
agent.shutdown();
```

### 注册本地工具插件

`TaiyiAgent` 默认不注册任何本地工具。需要执行 shell/文件/搜索工具时，显式注册内置插件：

```java
BuiltinPlugins.registerAll(
    agent.getPluginManager(),
    Path.of("/tmp/taiyiflow-sandbox"),          // 沙盒根目录
    List.of("ls", "cat", "echo"));              // shell 命令白名单（首 token 精确匹配）
// 只读场景：BuiltinPlugins.registerReadOnly(pm, sandboxRoot)
```

### 运行示例

```bash
export TAIYIFLOW_BASE_URL=http://localhost:8080
export TAIYIFLOW_API_KEY=your-api-key
export TAIYIFLOW_FLOW_ID=your-flow-id

./mvnw -pl taiyiflow-sdk-examples -am compile exec:java \
    -Dexec.mainClass=io.taiyiflow.sdk.examples.MinimalExample
```

示例清单见 [docs/examples.md](docs/examples.md)。

### 元数据、媒体、技能与浏览器

- `agent.getFlowMetadata()` 获取 flow/agent 名称、能力特性、知识库、欢迎语及扩展字段；`refreshFlowMetadata()` 强制刷新缓存。
- `BuiltinPlugins.registerMedia(...)` 显式启用文档、图片、音频和视频理解工具；媒体路径与上传仍受沙盒和权限链约束。
- `Builder.skillSupplier(...)` 将本地/内置/平台技能供应接入首帧 `client_skill_packages` 与 `requested_skill_names`；恢复沿用原快照。`BuiltinPlugins.registerSkills(...)` 注册 `skill_list/fetch/create/upload/delete`。
- 引入可选 `taiyiflow-sdk-browser` 后，通过 `BrowserToolProvider.register(...)` 启用无头 Chromium 或远程 CDP。默认禁用；主机/CIDR 白名单、页面数、文本、控制台与截图限额必须按部署环境收紧，JavaScript 执行默认关闭。
- 技能文本是不可信提示内容，不自动扩大工具、权限、沙盒、网络或子任务能力。浏览器断连、崩溃或超时后不自动重放导航、点击、输入、上传或 JavaScript。

五个完整示例见 [docs/examples.md](docs/examples.md)。

### 挂 Hook 定制行为

```java
TaiyiAgent agent = TaiyiAgent.builder()
    .baseUrl("http://localhost:8080")
    .apiKey("your-api-key")
    .flowId("my-agent-flow")
    // 工具执行拦截：接管特定工具的执行
    .onToolExecution(new ToolExecutionHook() {
        @Override
        public ToolResult takeover(String sessionId, ToolCall toolCall) {
            if ("get_weather".equals(toolCall.getName())) {
                ObjectNode weather = mapper.createObjectNode();
                weather.put("temperature", 22);
                return ToolResult.success(toolCall.getId(), weather);
            }
            return null; // 走内置插件
        }
    })
    // 权限审批：Shell 命令进入本地人工裁决
    .onPermission((sessionId, toolCall) -> {
        PermissionHook.Decision decision = "shell".equals(toolCall.getName())
            ? PermissionHook.Decision.ASK
            : PermissionHook.Decision.ALLOW;
        return CompletableFuture.completedFuture(decision);
    })
    .permissionTimeout(Duration.ofSeconds(300))
    .build();
```

### Spring Boot 集成

```xml
<dependency>
    <groupId>io.taiyiflow</groupId>
    <artifactId>taiyiflow-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```yaml
# application.yml
taiyiflow:
  base-url: http://localhost:8080
  api-key: ${TAIYIFLOW_API_KEY}
  flow-id: my-agent-flow
  sandbox-root: /tmp/taiyiflow-sandbox
  allowed-shell-commands: [ls, cat, echo, date, whoami, pwd]
  plugins:
    shell: true
    files: true
    search: true
  collaboration-enabled: false  # 默认关闭
  worker:
    enabled: false              # 数字人自动装配，默认关闭
    auto-start: true            # context 启动后连接平台
    worker-id: backend-worker-1
    display-name: 后端数字员工
    workspace: /var/lib/taiyiflow/worker
    concurrency: 3
    approval-channel: PLATFORM
```

`base-url` / `api-key` / `flow-id` 三键齐备才装配 `TaiyiAgent` Bean，可直接注入使用；四类 Hook Bean 会被自动挂载。启用 `taiyiflow.worker.enabled=true` 时必须提供 `WorkerIdentityValidator` Bean。完整配置键见 [docs/spring-boot.md](docs/spring-boot.md)。

## Hook 体系

所有 TaiyiAgent 业务扩展点收敛为 4 个 Hook，可在 Builder（全局）或会话（单会话）上挂载：

| Hook | 接口方法 | 默认行为（不挂时） |
|------|---------|-------------------|
| `StreamHook` | `onChunk` / `onEvent` / `onError` / `onClose` | 消息流内部缓冲 |
| `ToolExecutionHook` | `before` / `after` / `takeover` | 内置插件直接执行 |
| `PermissionHook` | `onPermission` → ALLOW / DENY / ASK | 按 `PermissionPolicy` 决策（默认 READONLY：只读放行、写操作拒绝） |
| `SessionHook` | `onStart` / `onFinish` / `onInterrupt` | 无额外行为 |

工具调用时的执行顺序：

```
智能体发起工具调用
    │
    ▼
PermissionHook.onPermission()      ← PermissionPolicy + 自定义 Hook
    │ ALLOW
    ▼
ToolExecutionHook.before()         ← 返回 null 取消执行
    │
    ▼
ToolExecutionHook.takeover() ──→ 非空？──→ 返回调用者结果
    │ null                               │
    ▼                                    │
内置插件执行（ToolRuntime 调度）          │
    │                                    │
    ▼                                    ▼
ToolExecutionHook.after() ◄──────────────┘
    │
    ▼
结果经 REST 回传智能体
```

详见 [docs/hooks.md](docs/hooks.md)。

## 执行控制与恢复

`AgentSession` 提供完整的执行控制面：

```java
session.pause();                  // 服务端暂停当前 run
session.resume();                 // 自动发现并恢复可续跑检查点
session.attach();                 // 重接仍在执行的原 run（不创建新执行）
session.cancel();                 // 服务端取消
session.retry();                  // 新 run：已持久化时续做，未持久化时重发原输入
session.repeat();                 // 新 run：复用原始 input/tweaks/files/requestType
session.interrupt();              // 仅本地中断

boolean ok = session.approve(toolCallId);          // PermissionHook 返回 ASK 后的人工裁决
boolean no = session.deny(toolCallId, "reason");
```

断线恢复默认 `MANUAL`；设置 `.recoveryPolicy(RecoveryPolicy.AUTO)` 后按退避策略依次 attach → checkpoint resume，只有确认原执行丢失且无不确定工具副作用时才自动 start。详见 [docs/execution-control.md](docs/execution-control.md)。

## 数字人（DigitalWorker）

`DigitalWorker` 在协同连接上接收 `remote_task`、`dialogue_subtask` 和定时任务，默认路由到对应 flow 的 `AgentSession` 执行，结果通过平台 REST 回报：

```java
DigitalWorker worker = DigitalWorker.builder()
    .baseUrl(baseUrl)
    .auth(WorkerAuth.apiKey(apiKey))
    .identity(WorkerIdentity.of("worker-1", "后端数字员工"))
    .workspace(Path.of("/var/lib/taiyiflow/worker"))
    .stateDir(Path.of("/var/lib/taiyiflow/runtime-state")) // 瞬态 Outbox 持久目录
    .identityValidator(thirdPartyIdentityValidator)        // 必填：第三方身份校验
    .approvalHandler(platformApprovalHandler)              // PLATFORM 审批通道适配器
    .build();
worker.start();
```

要点：

- **身份由第三方治理**：`WorkerIdentityValidator` 必填；workerId 可预置（`identity`）或经 `WorkerRegistrar` 向第三方系统注册，SDK 不自注册。
- **认证**：`WorkerAuth.apiKey(...)` / `jwt(...)` / `persistentToken(...)`；持久令牌只能放独立加密 `CredentialStore`（AES-256-GCM），401 按认证代次合并换牌、每操作最多重试一次。
- **可靠性**：先回报 running 再执行；回报失败进入 `REPORT_UNKNOWN` 只补传不重跑；重连后 `tasks.fetch_recent` 对账，仅明确 pending 的任务自动执行。
- **受控子任务**：内置 `run_subtask` 工具，支持单 prompt 与批量、模式降级（agent → plan → ask）、能力快照冻结与 MCP 配置按名解析。
- **单实例容器**：同一 workerId 只部署一个副本，`make image` 构建非 root 镜像，业务适配器 JAR 挂载 `/app/lib`。

详见 [docs/digital-worker.md](docs/digital-worker.md) 与 [docs/deployment.md](docs/deployment.md)。

## 传输协议

- **流式运行（主通道）**：WS `/api/v1/run/{flow_id}/websocket` 或 SSE `POST /api/v1/run/{flow_id}/stream`；默认 `StreamProtocol.AUTO`，由服务端 `/api/v1/config` 的 `stream_protocol` 决定，可显式覆盖；WS 不可用时可回退 SSE（`sseFallbackEnabled` 默认 true）。
- **首帧**：连接后 10s 内发送 `ChatRequest`（input_value/session_id/tweaks/...）；消息正文在 `data.chunk`。
- **工具调用**：流内 `client_tool` / `mcp_tool` / `built_in` 事件；结果回传走 REST `POST /api/v1/agents/client/call/result`（首帧后服务端不再读 WS）。
- **协同 WS**：`/api/v1/agents/client/websocket`，`{type,event,payload}` 封套，`connection.ready` 后收任务。
- **鉴权**：REST 用 `Authorization: Bearer`；WS 用 query `__TOKEN__`。

事件类型（11 种）与关闭码见 [docs/protocol.md](docs/protocol.md)。

## 内置插件与 MCP

| 插件 | 工具 | 说明 |
|------|------|------|
| `ShellPlugin` | `shell`/`bash`/`execute_command`、`process_list`/`process_output`/`process_stop`/`process_cleanup` | 白名单 + 工作目录校验；前台 30s 超时；后台进程句柄隔离、每流 64Ki 字符尾部缓冲 |
| `FilePlugin` | `read_file`、`write_file`、`file_edit`、`list_files` | 沙盒路径校验、原子写、`file_edit` 带 PATCH_CONFLICT 冲突协议 |
| `SearchPlugin` | `search`、`grep` | 字面量/正则搜索，100 条截断 |

MCP 支持两种形态：Streamable HTTP（`McpToolClient`）与本地 stdio 子进程（`StdioMcpManager` + `StdioMcpPolicy` 命令/env 白名单）。工具统一以 `c_{server}_{tool}` 注册，经过同一条权限、Hook、并发与取消链。详见 [docs/plugins.md](docs/plugins.md) 与 [docs/mcp.md](docs/mcp.md)。

## 技术选型

| 维度 | 选择 | 理由 |
|------|------|------|
| Java 版本 | 17+ 编译；运行需 21+ | 虚拟线程支撑多会话与任务并行 |
| 构建 | Maven 多模块 + Wrapper | 标准企业级，无需本机 Maven |
| JSON | Jackson 2.17 | 生态成熟 |
| WebSocket | `java.net.http.WebSocket` | JDK 内置，零额外依赖 |
| 并行 | 虚拟线程 | 高并发会话，低资源开销 |
| 消息流 | 简单回调 + 可选 `Flow` 适配器 | 不引入 Reactive Streams 依赖 |
| Spring Boot | 3.2.x（starter，optional 依赖） | 自动装配 TaiyiAgent 与 DigitalWorker |

## 构建与测试

```bash
./mvnw clean compile                          # 编译全部模块
./mvnw test                                   # 全部单元测试
./mvnw -pl taiyiflow-sdk-plugins -am test     # 单模块测试（连带依赖模块）
./mvnw package                                # 打包（worker 模块产出 *-all.jar）
make image                                    # 构建 Worker 容器镜像
```

## 文档站点（VitePress）

文档站是位于 `docs/` 的**自包含构建单元**，拥有独立的 `package.json`、`Dockerfile.docs` 与 `BUILD.yaml`，与 Worker 运行时镜像相互独立，可作为独立服务组件发布。

```bash
make docs-init      # 安装文档依赖（在 docs/ 下执行 npm install）
make docs-dev       # 本地开发预览
make docs-build     # 构建（Docker 渠道，base=/taiyi/javasdk/docs/）
make docs-build-gh  # 构建（GitHub Pages 渠道，base=/taiyiflow-javasdk/）
make docs-image     # 构建文档站镜像（构建上下文为 docs/）
make docs-run       # 运行文档站容器（默认端口 5002）
make docs-stop      # 停止并删除容器
```

也可直接在 `docs/` 目录下用 `snz1dpctl` 按独立组件发布：

```bash
cd docs
snz1dpctl make docker     # 构建镜像 snz1.cn/taiyiflow/javasdk-docs
snz1dpctl make publish    # 发布到慧码至一组件仓库
```

**运行时上下文路径**：镜像内置 base 为 `/taiyi/javasdk/docs/`，启动时由 `docs/scripts/40-replace-context-path.sh` 按 `WEB_CONTEXT_PATH` 环境变量重定位静态产物与 nginx 配置，无需重新构建即可挂到任意网关路径下（见 `docs/BUILD.yaml` 的 `service.envs` 与 `ingress`）。

## 文档索引

| 文档 | 内容 |
|------|------|
| [docs/getting-started.md](docs/getting-started.md) | 安装、环境、最简示例、示例运行 |
| [docs/agent-and-session.md](docs/agent-and-session.md) | TaiyiAgent Builder 全量配置、AgentSession API、多会话、REST 能力面 |
| [docs/hooks.md](docs/hooks.md) | 4 个 Hook、PermissionPolicy、执行顺序与审批 |
| [docs/plugins.md](docs/plugins.md) | 内置工具插件、后台进程、工作区审计、自定义工具 |
| [docs/mcp.md](docs/mcp.md) | HTTP 与 stdio MCP 接入及安全策略 |
| [docs/execution-control.md](docs/execution-control.md) | 暂停/恢复/重试/重放、断线恢复、结果补传 Outbox |
| [docs/digital-worker.md](docs/digital-worker.md) | DigitalWorker、身份与认证、任务可靠性、受控子任务、worker.yml |
| [docs/collaboration.md](docs/collaboration.md) | CollaborationPlugin / SchedulePlugin 底层协同 API |
| [docs/spring-boot.md](docs/spring-boot.md) | Starter 全量配置键与 Bean 装配 |
| [docs/protocol.md](docs/protocol.md) | 流式/协同协议、事件与关闭码、REST 端点 |
| [docs/deployment.md](docs/deployment.md) | 单实例容器构建与部署 |
| [docs/examples.md](docs/examples.md) | 7 个示例说明与运行命令 |

部署运维手册另见 [deploy/single-instance-container.md](deploy/single-instance-container.md)，示例配置见 [deploy/worker.yml](deploy/worker.yml)。

## License

TBD