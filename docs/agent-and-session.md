# 智能体与会话

`TaiyiAgent` 是 SDK 的主入口门面：一个实例绑定一个智能体（flow），内部以虚拟线程管理多个并发会话。`AgentSession` 表示一次会话执行，提供流式对话、执行控制与审批裁决。

## TaiyiAgent 构建器（Builder）

```java
TaiyiAgent agent = TaiyiAgent.builder()
    .baseUrl("http://localhost:8080")
    .apiKey("your-api-key")
    .flowId("my-agent-flow")
    .build();
```

### 连接与身份

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `baseUrl(String)` | 必填 | 平台地址，非空非 blank |
| `apiKey(String)` | 必填（与 accessToken 二选一） | 静态 API 密钥（API Key） |
| `accessToken(Supplier<String>)` | — | 动态令牌供应器，每次请求读取最新值 |
| `flowId(String)` | 必填 | 绑定的智能体 flow |
| `clientId(String)` | `~/.taiyiflow/client-id` 持久化标识 | 客户端标识 |
| `deviceId(String)` | 回退 collaborationDeviceId | 设备标识 |
| `connectTimeout(Duration)` | 10s | 连接超时 |
| `requestTimeout(Duration)` | 30s | REST 请求超时 |
| `credentialHeaders(Map<String,String>)` | 空 | 附加凭证头（防御性拷贝） |

### 会话与流

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `maxSessions(int)` | 100 | 并发会话上限，超限创建会话抛异常 |
| `eventQueueCapacity(int)` | 1024 | 会话事件队列容量，必须 > 0 |
| `streamProtocol(StreamProtocol)` | `AUTO` | `AUTO` / `SSE` / `WEBSOCKET`；AUTO 读取服务端 `/api/v1/config` 的 `stream_protocol`，读取失败回退 WebSocket |
| `sseFallbackEnabled(boolean)` | true | WS 失败时是否回退 SSE |
| `flowAdapter(FlowAdapter)` | 无 | 发送前定制 `ChatRequest` 的函数式接口 |

### 权限

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `permissionPolicy(PermissionPolicy)` | `READONLY` | `AUTO` / `CONFIRM` / `READONLY`，见 [hooks.md](hooks.md) |
| `permissionHook(PermissionHook)` | 无 | 策略级审批钩子（Hook） |
| `permissionTimeout(Duration)` | 300s | ASK 审批等待超时；`Duration.ZERO` 永不超时；其他有效值 10–6000s |

### 断线恢复

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `recoveryPolicy(RecoveryPolicy)` | `MANUAL` | `MANUAL` / `AUTO` |
| `recoveryBackoff(Duration min, Duration max)` | 2s / 60s | 要求 0 < min ≤ max |
| `recoveryMaxAttempts(int)` | 8 | 必须 > 0 |
| `autoRestartOnExecutionLoss(boolean)` | true | 确认执行丢失后是否允许自动启动新执行（start） |

详见 [execution-control.md](execution-control.md)。

### 工具运行时

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `toolRuntimeConfig(ToolRuntime.Config)` | 全局并发 8、单会话并发 2、队列 128、单工具超时 60s | 与 `toolRuntime(...)` 互斥 |
| `toolRuntime(ToolRuntime)` | SDK 自建 | 注入共享运行时（注入后 agent 不负责 close） |
| `restClient(RestClient)` | SDK 自建 | 高级传输注入 |

```java
TaiyiAgent agent = TaiyiAgent.builder()
    // ...
    .toolRuntimeConfig(ToolRuntime.Config.builder()
        .globalConcurrency(16)
        .perSessionConcurrency(4)
        .queueCapacity(256)
        .executionTimeout(Duration.ofSeconds(120))
        .build())
    .build();
```

所有数值必须为正，无无界回退值。

### 协同（可选）

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `enableCollaboration()` | 关闭 | **Builder 方法**；build 时立即创建并启动协同 WS（角色 `collaboration_worker`） |
| `collaborationClientId(String)` | — | 协同连接 clientId |
| `collaborationDeviceId(String)` | — | 协同连接 deviceId |
| `collaborationInstanceId(String)` | — | primary_cli 角色必填 |

启用后通过 `agent.getCollaborationPlugin()` 获取插件并自行注册任务回调；`shutdown()` 时自动停止。详见 [collaboration.md](collaboration.md)。

### 全局钩子（Hook）

`onStream(StreamHook)`、`onToolExecution(ToolExecutionHook)`、`onPermission(PermissionHook)`、`onLifecycle(SessionHook)` 均可多次调用，作用于所有会话。会话级同名方法只作用于单个会话。

### build() 校验

- `baseUrl`、`flowId` 非空非 blank；`apiKey` 或 `accessToken` 至少一个。
- `toolRuntime` 与 `toolRuntimeConfig` 互斥；注入的 runtime 不能已关闭。
- `permissionTimeout`、`recoveryBackoff`、`recoveryMaxAttempts`、`eventQueueCapacity` 按上表规则校验。

## TaiyiAgent 实例方法

```java
// 会话
AgentSession newSession();                 // 随机 UUID sessionId
AgentSession newSession(String sessionId); // 指定 sessionId（重复抛异常）
void shutdown();                           // 停协同→关会话→关插件→关 executor→关自有 ToolRuntime

// REST 能力面（每次调用返回新客户端实例）
SessionClient sessions();                  // 会话/消息 CRUD
SkillClient skills();                      // 技能上传/安装管理
MemoryClient memories();                   // 长期记忆
ScheduleClient schedules();                // 定时任务
ConfigClient config();                     // 服务端配置
FileClient files();                        // 会话文件上传/下载
McpClient mcpServers();                    // 服务端 MCP 注册管理
CollaborationTaskClient collaborationTasks(); // 协同任务生命周期上报
ExecutionController executionControl();    // run 级暂停/取消/检查点查询

// 运行时组件
PluginManager getPluginManager();          // 工具注册表
ToolRuntime getToolRuntime();
SessionManager getSessionManager();
CollaborationPlugin getCollaborationPlugin(); // 未启用协同时为 null

// 流协议
StreamProtocol resolveStreamProtocol();
StreamProtocol refreshStreamProtocol();

// 补传本 flow 持久化的检查点首帧提交（不执行工具）
void flushResumeReports();
```

## AgentSession API

### 对话

```java
AgentSession chat(String message);
AgentSession chat(String message, Map<String, JsonNode> tweaks);
AgentSession chat(String message, List<String> inputFiles);   // FileClient 上传后的服务端路径
AgentSession chat(String message, Map<String, JsonNode> tweaks, List<String> inputFiles);
AgentSession chat(ChatRequest request);                       // 高级协议请求
```

所有重载返回 `this`，支持链式：`session.onStream(hook).chat("...")`。

### 执行控制

| 方法 | 语义 |
|------|------|
| `pause()` | 请求服务端暂停当前执行轮次（run，REST 控制接口），返回 `AgentLoopControlResult` |
| `cancel()` | 服务端取消 + 关闭本地流资源 |
| `attach()` | 重接（attach）仍在执行的原轮次，不启动新执行 |
| `resume()` | 自动发现并恢复当前可续跑检查点（checkpoint） |
| `resume(ResumableCheckpoint)` | 恢复指定检查点 |
| `retry()` | 新轮次：服务端已持久化则续做，否则重发原输入 |
| `repeat()` | 新轮次：复用上一轮输入、参数覆盖、文件与请求类型（input/tweaks/files/requestType） |
| `interrupt()` | 仅本地中断，不调服务端控制 API |

### 审批裁决

`PermissionHook` 返回 `ASK` 后，工具调用进入会话级审批等待：

```java
boolean accepted = session.approve(toolCallId);
boolean rejected = session.deny(toolCallId, "not allowed");
```

重复或未知回执返回 `false`。超时（默认 300s）形成失败结果并回传平台。

### 状态与等待

```java
String getSessionId();
boolean isActive();              // 是否正在流式/处理中
SessionState getState();         // 见下方状态机
RunContext getRunContext();      // 当前 run 上下文（runId 等）

void await() throws InterruptedException;
boolean await(Duration timeout) throws InterruptedException; // 超时返回 false
```

`SessionState` 枚举：`IDLE, CONNECTING, STREAMING, TOOL_CALLING, PAUSING, PAUSED, RESUMING, CANCELLED, DISCONNECTED, RESTARTING, DONE, INTERRUPTED, ERROR`。

### 会话级钩子（Hook）

```java
session.onStream(streamHook);
session.onToolExecution(toolHook);
session.onPermission(permissionHook);
session.onLifecycle(sessionHook);   // 注意：方法名是 onLifecycle，不是 onSession
```

## 多会话并行

一个 `TaiyiAgent` 可并发多个 `AgentSession`，每个会话一条独立流连接（独立 `session_id`），基于虚拟线程实现，会话间完全隔离、可各自中断：

```java
TaiyiAgent agent = TaiyiAgent.builder()
    // ...
    .maxSessions(10)
    .build();

List<AgentSession> sessions = new ArrayList<>();
for (int i = 0; i < 3; i++) {
    AgentSession s = agent.newSession("session-" + i);
    s.onStream(new StreamHook() {
        @Override public void onChunk(String sessionId, String chunk) {
            System.out.print("[" + sessionId + "] " + chunk);
        }
    });
    s.chat("任务 " + i);
    sessions.add(s);
}
for (AgentSession s : sessions) s.await();
agent.shutdown();
```

完整示例见 `MultiSessionExample`。

## 流空闲看门狗

会话内置空闲检测（当前实现固定值，不可通过 Builder 配置）：

- 无工具执行时：流空闲 **90 秒**触发超时处理；
- 有工具执行时：放宽到 **15 分钟**；
- 每次流活动、工具起止都会重置计时。

单工具执行超时另由 `ToolRuntime.Config.executionTimeout`（默认 60s）控制。

## REST 能力面速览

所有客户端基于 `RestClient`（`Authorization: Bearer` 鉴权），方法抛 `Exception`、返回 `JsonNode`（标注除外）：

| 客户端 | 主要方法 |
|--------|---------|
| `SessionClient` | `createSessionId()`、`list/get/rename/delete/deleteAll`、`messages`、`deleteMessages` |
| `MemoryClient` | `list(filters)`、`create`、`get`、`forget`、`restore` |
| `FileClient` | `upload(flowId, sessionId, Path[, remotePath])` → `UploadedFile(flowId, filePath)`、`download` → `byte[]` |
| `SkillClient` | `list`、`getContent`、`upload`、`uploadArchive`、`delete`、`reportInstallation`、`installations`、`uninstall` |
| `McpClient` | `list(flowId, clientType)`、`create`、`update`、`delete`、`setEnabled` |
| `ScheduleClient` | `list`、`create`、`update`、`delete`、`enable/disable`、`runs`、`getRun`、`report` |
| `CollaborationTaskClient` | `create`、`get`、`cancel`、`list`、`running`、`success`、`failure`、`cancelled`（支持 expectedVersion 乐观锁） |
| `ConfigClient` | `get()` |
| `ExecutionController` | `pause(sessionId, runId)`、`cancel(sessionId, runId)`、`listResumable(flowId, sessionId)` |

端点明细见 [protocol.md](protocol.md)。

## 文件上传与带文件对话

```java
FileClient.UploadedFile uploaded = agent.files()
    .upload(agent.getFlowId(), session.getSessionId(), Path.of("report.pdf"));
session.chat("请总结这份报告", List.of(uploaded.filePath()));
session.await();
```