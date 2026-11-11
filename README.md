# TaiyiFlow Java Agent SDK

> 面向 Java 后台的数字人运行时与智能体能力 SDK——`DigitalWorker` 可常驻接活、请示和回报，`TaiyiAgent` 可主动发起多会话并行执行，扩展统一通过 Hook 完成。

## 定位

**一句话定位**：Java 后台进程既可作为长期在线的数字员工，也可作为调用者主动驱动平台智能体。

| 维度 | JSSDK | Java SDK（本项目） |
|------|-------|-------------------|
| 场景 | 浏览器应用嵌入聊天窗口 | Java 后台程序主动驱动智能体 |
| 调用方 | 前端页面 | 服务端 / CLI / 微服务 |
| 扩展方式 | 插件注册 | **Hook 机制**（唯一扩展面） |
| 会话模型 | 单会话 | **多会话并行**（虚拟线程） |

### 核心原则

1. **客户端协同 WS 是可选插件**：默认不建立被动长连接；仅当需要"平台反向下发任务"时启用 `CollaborationPlugin`。
2. **多会话并行**：一个 `TaiyiAgent` 实例绑定一个智能体，内部 `SessionManager` 管理多个并发 `AgentSession`，会话间隔离、可各自中断。
3. **Hook 是唯一扩展面**：调用者按需挂 Hook，不挂则走内置默认行为。

### 不做的事

- UI / 前端渲染
- 语音 dialogue 链路
- LLM 编排（大脑仍在平台侧）

## 架构总览

```
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
├── worker/                       # 数字人运行时：身份、任务执行、平台回报与恢复对账
└── spring-boot-starter/          # 自动装配：逐插件/Hook 开关
```

## 快速开始

### 环境要求

- Java 17+；使用虚拟线程运行时需要 Java 21+
- 无需本机安装 Maven，使用仓库内 `./mvnw`
- 可访问的 TaiyiFlow 后端服务

### 最简示例（3 行代码跑通流式对话）

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

### 环境变量（示例程序）

```bash
export TAIYIFLOW_BASE_URL=https://api.taiyiflow.io
export TAIYIFLOW_API_KEY=your-api-key
export TAIYIFLOW_FLOW_ID=your-flow-id
```

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
  allowed-shell-commands:
    - ls
    - cat
    - echo
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
  recovery-policy: MANUAL       # MANUAL / AUTO
  recovery-min-backoff: 2s
  recovery-max-backoff: 60s
  recovery-max-attempts: 8
  auto-restart-on-execution-loss: true
  permission-timeout: 300s      # 0 表示永不超时，非零范围为 10–6000 秒
```

自动装配后可直接注入 `TaiyiAgent`：

```java
@Service
public class MyService {
    private final TaiyiAgent agent;

    public MyService(TaiyiAgent agent) {
        this.agent = agent;
    }

    public void chat(String message) {
        agent.newSession().onStream(...).chat(message);
    }
}
```

启用 `taiyiflow.worker.enabled` 时，必须提供 `WorkerIdentityValidator` Bean。默认复用 `taiyiflow.api-key`，也可注册 `WorkerAuth` Bean 使用 JWT 或 persistentToken；业务任务注册 `WorkerTaskHandler`，平台审批注册名为 `workerApprovalHandler` 的 `PermissionHook`，本地审批审计注册 `ApprovalAudit`，观测扩展注册 `WorkerHook`。`auto-start=false` 只装配不连接，便于测试或由应用自行控制生命周期。

## Hook 体系

所有扩展点收敛为 Hook 机制，调用者按需挂载：

| Hook | 接口方法 | 默认行为（不挂时） |
|------|---------|-------------------|
| `StreamHook` | `onChunk` / `onEvent` / `onError` / `onClose` | 消息流内部缓冲，`chat()` 返回完整结果 |
| `ToolExecutionHook` | `before` / `after` / `takeover` | 内置插件直接执行 |
| `PermissionHook` | `onPermission` → allow / deny / ask | 只读放行，写操作拦截 |
| `SessionHook` | `onStart` / `onFinish` / `onInterrupt` | 无额外行为 |

### Hook 执行顺序（工具调用场景）

```
智能体发起工具调用
    │
    ▼
PermissionHook.onPermission()
    │ allow
    ▼
ToolExecutionHook.before()
    │
    ▼
ToolExecutionHook.takeover() ──→ 非空？──→ 返回调用者结果
    │ empty                              │
    ▼                                    │
内置插件执行                              │
    │                                    │
    ▼                                    ▼
ToolExecutionHook.after() ◄──────────────┘
    │
    ▼
结果回传智能体
```

## 执行控制、恢复与审批

`pause()` / `cancel()` 调用服务端控制接口；`attach()` 重接仍在执行的原 run，`resume()` 通过服务端检查点恢复。两者都不会主动创建新执行。

```java
session.pause();
session.resume();                 // 自动发现并恢复可续跑检查点；重接活跃 run 可改用 attach()
session.await();

session.retry();                  // 新 run：已持久化时续做，未持久化时重发原输入
session.await();
session.repeat();                 // 新 run：复用原始 input/tweaks/files/requestType
session.await();
```

断线恢复默认 `MANUAL`。设置 `.recoveryPolicy(RecoveryPolicy.AUTO)` 后，SDK 按退避策略依次对账并选择 attach、checkpoint resume；只有确认原执行丢失且不存在 waiting/pending/uncertain 工具副作用时，才允许自动 start。状态未知或平台不可达时不会盲目重发。可通过 `recoveryBackoff(min, max)`、`recoveryMaxAttempts(n)` 和 `autoRestartOnExecutionLoss(boolean)` 调整行为。

`PermissionHook` 返回 `ASK` 时，工具调用进入会话级审批等待，由调用者裁决：

```java
boolean accepted = session.approve(toolCallId);
boolean rejected = session.deny(toolCallId, "not allowed");
```

批准后才执行工具；拒绝或超时形成失败结果并通过 REST 回传。默认审批超时为 300 秒，`permissionTimeout(Duration.ZERO)` 表示永不超时，其他有效值为 10–6000 秒。重复或未知回执返回 `false`。未确认的工具结果仅保存在进程内存中：attach 前补偿重报，resume 时随检查点提交，不写入本地权威结果库。

完整示例见 `PauseResumeExample`、`RetryRepeatExample` 和 `LocalApprovalExample`。

## 数字人任务执行与平台结果

`DigitalWorker` 在协同连接上接收 `remote_task`、`dialogue_subtask` 和定时任务。未配置 `.onTask(...)` 时，这些任务默认路由到对应 flow 的 `AgentSession`；同一 flow + session 串行执行，结果中的 `session_id`、`run_id` 和文本通过平台任务或定时任务 REST 接口回报。自定义 `.onTask(...)` 始终优先。

```java
DigitalWorker worker = DigitalWorker.builder()
    .baseUrl(baseUrl)
    .auth(WorkerAuth.apiKey(apiKey))
    .identity(WorkerIdentity.of("worker-1", "后端数字员工"))
    .workspace(Path.of("/var/lib/taiyiflow/worker"))
    .identityValidator(platformIdentityValidator) // 必须连接平台预登记校验
    .approvalHandler(platformApprovalHandler)     // 默认 PLATFORM 通道的适配器
    .build();
worker.start();
```

也可以直接使用配置化入口。`deploy/worker.yml` 提供完整示例，密钥只配置环境变量名称：

```bash
./mvnw -pl taiyiflow-sdk-worker -am package
java -cp 'taiyiflow-sdk-worker/target/*-all.jar:build/adapters/*' \
  io.taiyiflow.sdk.worker.DigitalWorkerMain --config deploy/worker.yml
```

`extensions.identity-validator` 必须指向业务方实现的平台预登记校验类；任务、审批和指标分别通过 `task-handler`、`approval-handler`、`approval-audit`、`hooks` 注入。`WorkerHook` 提供 ready、任务开始/结束、错误和停止事件，可接日志、指标或追踪系统。配置路径也可通过 `TAIYIFLOW_WORKER_CONFIG` 或 `taiyiflow.worker.config` 指定。

workerId、角色和能力必须由平台预先登记，SDK 不自动注册。认证可使用 `WorkerAuth.apiKey(...)`、`WorkerAuth.jwt(...)`，或 `WorkerAuth.persistentToken(...)`；持久令牌只能放入独立加密 `CredentialStore`，不能写入普通 workspace。所有长期流式连接都会读取最新 accessToken，401 换牌按认证代次合并，每个操作最多重试一次。

### 任务可靠性与恢复

- `TaskExecutor` 先成功回报 running 才执行任务，避免平台未接收状态时产生副作用；成功/失败回报失败会进入 `REPORT_UNKNOWN`，不会通过重跑任务补救。
- 重连后通过 `tasks.fetch_recent` 分页补拉。只有平台明确为 `pending` 且未取消的任务会自动执行；`dispatched`、`acknowledged`、`running` 只对账，不重放；平台终态用于校正本地状态。
- 同一 workerId 只部署一个容器副本，由容器平台负责心跳检测、故障摘除和快速拉起。SDK 不实现主备、选举或 fencing 协议。
- 进程内的会话断线按 attach → checkpoint resume → 明确执行丢失后的安全 start 恢复。容器重启后的跨进程恢复仍以平台任务、run/checkpoint 和已接收结果为权威；缺少关联或状态不明时保持 `REPORT_UNKNOWN`，等待人工处理，不盲目重放。
- 原 taskId 关联新 runId 的自动重建、跨实例执行接管和审批断点恢复依赖 P-RECOVERY、P-RESULT、P-APPROVAL 后端能力；当前单元测试不代替真实平台联调。

### 记忆、产物与审批

`worker.memories()` 和 `worker.files()` 使用平台 REST 接口，并统一支持 401 换牌重试。本地产物通过 `worker.uploadArtifact(flowId, sessionId, path)` 上传；workspace 只保存 instance_id、最小运行标识和临时文件，不保存权威业务结果、工具结果或长期任务历史。

审批默认使用 `ApprovalChannel.PLATFORM`，写操作进入审批，只读操作直接放行。平台通道需要显式 `approvalHandler` 对接真实审批服务，未配置时需要审批的工具会失败关闭。使用 `LOCAL_CALLBACK` 时必须同时配置 `approvalAudit`，本地 ALLOW/DENY 只有在平台审计完成后才返回工具执行链。

底层协同插件的任务幂等执行与平台回报示例见 `CollaborationExample`。

### 单实例容器

根目录 `Dockerfile` 构建非 root 的可执行 Worker 镜像，业务适配器 JAR 挂载到 `/app/lib`。本地执行 `make image` 构建镜像，`make test` / `make build` 运行测试和打包；`BUILD.yaml` 提供 linux/amd64 与 linux/arm64 发布描述，可通过 `make docker`、`make package`、`make publish` 调用 `snz1dpctl`。部署手册见 `deploy/single-instance-container.md`，示例配置见 `deploy/worker.yml`。同一 workerId 只允许一个副本，更新使用 Recreate，进程退出由容器平台快速拉起；不要把容器重启等同于已完成任务恢复，仍需按平台状态对账。

## 传输协议

### 流式运行（主通道）

- **协议**：WebSocket 双向（`WS /api/v1/run/{flow_id}/websocket`）
- **首条消息**：连接后 10s 内发送 `{input_value, session_id, tweaks}`
- **服务端推送**：`{"event": "message|heartbeat|error|close|custom", "data": {...}}`
- **关闭码**：1000（正常）/ 4001 / 4002 / 4003 / 4004 / 4009
- **SSE 回退**：`POST /run/{id}/stream`

### 多会话并行

一个 `TaiyiAgent` 实例可并发多个 `AgentSession`，每个会话对应一条独立 WS 连接（独立 `session_id`），基于虚拟线程实现，会话间完全隔离。

### REST 辅助面

- `/agents/*`：会话/消息/技能/记忆查询
- `/client-collaboration-manager`：协同管理（可选）
- `/client-schedule-manager`：定时任务管理（可选）
- `/config`：配置查询

## 内置插件

| 插件 | 功能 | 默认状态 |
|------|------|---------|
| `ShellPlugin` | 执行 Shell 命令（ProcessBuilder） | 启用（工作目录 + 白名单） |
| `FilePlugin` | 文件读写、细粒度编辑 | 启用 |
| `SearchPlugin` | 文本/正则搜索 | 启用 |
| `SkillPlugin` | 技能注册与同步 | 启用 |
| `CustomToolHandler` | 自定义工具注册入口 | 按需 |

`ShellPlugin` 的工作目录不是操作系统级文件系统沙盒；应按最小权限配置命令白名单。文件路径隔离由 `FilePlugin` 和 `SearchPlugin` 提供。

## 可选插件

| 插件 | 功能 | 启用方式 |
|------|------|---------|
| `CollaborationPlugin` | 接收平台反向下发任务（无头 CLI 模式） | `agent.enableCollaboration()` |
| `SchedulePlugin` | 定时任务回调 | 依赖 CollaborationPlugin |

## 技术选型

| 维度 | 选择 | 理由 |
|------|------|------|
| Java 版本 | 17+；虚拟线程运行需 21+ | 后台多会话与任务并行 |
| 构建 | Maven 多模块 | 标准企业级 |
| JSON | Jackson | 生态成熟 |
| WebSocket | `java.net.http.WebSocket` | JDK 内置，零额外依赖 |
| 并行 | 虚拟线程 | 高并发会话，低资源开销 |
| 消息流 | 简单回调 + 可选 `Flow` 适配器 | 不引入 Reactive Streams 依赖 |

## 开发计划

| 阶段 | 内容 | 依赖 |
|------|------|------|
| **Phase A** | Hook SPI 与协议模型冻结；核实工具调用回调通道 | — |
| **Phase B** | 传输层：流式 WS + REST 客户端 | A |
| **Phase C** | 内核：TaiyiAgent / SessionManager / ToolDispatcher / PluginManager | B |
| **Phase D** | 内置插件与 Hook 语义落地 | C |
| **Phase E** | 可选协同插件（CollaborationPlugin + SchedulePlugin） | C（与 D 并行） |
| **Phase F** | Spring Boot Starter、示例与文档 | D/E |

## 验证策略

1. **协议单测**：封套解析、事件常量与后端字符串全量对照、超时与关闭码映射
2. **多会话并行测试**：并发 N 个会话，验证隔离、独立中断、资源回收
3. **Hook 链测试**：零 Hook 默认路径 / StreamHook 全量事件 / ToolExecutionHook takeover / PermissionHook deny
4. **工具回调集成测试**：对接本地后端，跑通全链路
5. **协同插件测试**：启用后接收/执行/回报；未启用时无被动连接
6. **开箱即用验收**：minimal 示例仅填 baseUrl/token/flowId 即运行

## 项目结构

```
taiyiflow-javasdk/
├── pom.xml                              # Parent POM
├── taiyiflow-sdk-core/                  # 核心模块
│   └── src/main/java/io/taiyiflow/sdk/
│       ├── TaiyiAgent.java              # 智能体门面（Builder 模式）
│       ├── AgentSession.java            # 会话接口
│       ├── hooks/                       # Hook SPI（4 个接口）
│       │   ├── StreamHook.java
│       │   ├── ToolExecutionHook.java
│       │   ├── PermissionHook.java
│       │   └── SessionHook.java
│       ├── core/
│       │   ├── session/                 # SessionManager + AgentSessionImpl
│       │   ├── dispatch/                # ToolDispatcher（Hook 链编排）
│       │   ├── plugin/                  # PluginManager
│       │   └── state/                   # SessionState 状态机
│       └── transport/
│           ├── envelope/                # 协议模型 + 事件常量
│           ├── stream/                  # StreamClient (WS) + SseStreamClient
│           └── rest/                    # RestClient
├── taiyiflow-sdk-plugins/               # 内置插件
│   └── src/main/java/io/taiyiflow/sdk/plugins/
│       ├── BuiltinPlugins.java          # 一键注册
│       ├── shell/ShellPlugin.java       # Shell 命令执行（白名单 + 工作目录）
│       ├── files/FilePlugin.java        # 文件读写（沙盒限制）
│       └── search/SearchPlugin.java     # 文本/正则搜索
├── taiyiflow-sdk-collaboration/         # 可选协同插件
│   └── src/main/java/io/taiyiflow/sdk/collaboration/
│       ├── CollaborationPlugin.java     # 协同 WS（被动接收任务）
│       └── SchedulePlugin.java          # 定时任务
├── taiyiflow-sdk-worker/                # 数字人运行时
│   └── src/main/java/io/taiyiflow/sdk/worker/
│       ├── DigitalWorker.java           # 常驻运行时、任务路由与平台客户端
│       ├── DigitalWorkerMain.java       # worker.yml 配置化容器入口
│       ├── WorkerConfig.java            # YAML 配置模型
│       ├── WorkerHook.java              # 生命周期与任务观测扩展
│       ├── AgentSessionTaskHandler.java # 默认会话任务执行
│       ├── TaskExecutor.java            # 幂等执行与平台快照对账
│       └── auth/                        # 双认证、换牌与加密凭证存储
├── taiyiflow-spring-boot-starter/       # Spring Boot 自动装配
│   └── src/main/java/io/taiyiflow/sdk/starter/
│       ├── TaiyiFlowProperties.java     # 配置属性
│       └── TaiyiFlowAutoConfiguration.java
└── taiyiflow-sdk-examples/              # 示例
    └── src/main/java/io/taiyiflow/sdk/examples/
        ├── MinimalExample.java          # 最简示例
        ├── ToolTakeoverExample.java     # 工具接管 + 权限拦截
        ├── MultiSessionExample.java     # 多会话并行
        ├── CollaborationExample.java    # 协同模式
        ├── PauseResumeExample.java      # 暂停与检查点恢复
        ├── RetryRepeatExample.java      # 重试与重复执行
        └── LocalApprovalExample.java    # 本地人工审批
```

## License

TBD
