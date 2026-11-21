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
| `flowId(String)` | 服务端默认 Flow | 可选；未设置或 blank 时，build 自动读取 `/api/v1/config` 的 `default_flow_id` |
| `useServerDefaultFlow()` | 关闭 | 显式使用服务端默认 Flow；省略 flowId 时无需调用；与显式 flowId 互斥 |
| `clientId(String)` | `~/.taiyiflow/client-id` 持久化标识 | 客户端标识 |
| `deviceId(String)` | 回退 collaborationDeviceId | 设备标识 |
| `connectTimeout(Duration)` | 10s | 连接超时 |
| `requestTimeout(Duration)` | 30s | REST 请求超时 |
| `credentialHeaders(Map<String,String>)` | 空 | 附加凭证头（防御性拷贝） |

### 会话与流

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `maxSessions(int)` | 100 | 并发会话上限，超限创建会话抛异常 |
| `maxSessionConcurrency(Integer)` | null → 跟随服务端 | 执行中会话并发配额；显式值优先于 `/config` `client_max_session_concurrency`，0 阻止新对话，见 [execution-control.md](execution-control.md) |
| `clientType(String)` | `device` | 首帧 `client_type`（非空白）；服务端按此合并内置技能，单次请求显式值优先 |
| `requestDefaults(RequestDefaults)` | chat/chat、auto-loop 关、image AUTO | 首帧请求默认值；单次请求显式值优先；retry/repeat 复用快照 |
| `instructionDirectories(List<Path>)` | 空（关闭） | 显式目录才供应 `client_instructions`，不扫描 home |
| `eventQueueCapacity(int)` | 1024 | 会话事件队列容量，必须 > 0 |
| `streamProtocol(StreamProtocol)` | `AUTO` | `AUTO` / `SSE` / `WEBSOCKET`；AUTO 读取服务端 `/api/v1/config` 的 `stream_protocol`，读取失败回退 WebSocket |
| `sseFallbackEnabled(boolean)` | true | WS 失败时是否回退 SSE |
| `streamIdleTimeout(Duration)` | 1200s | 流空闲看门狗超时；`Duration.ZERO` 禁用空闲检测 |
| `toolExecutionTimeout(Duration)` | 自动推导 | 工具执行期的流空闲超时；缺省按注册工具最大时长 + 60s 推导且不低于 31min，显式值低于该下限会被抬升 |
| `flowAdapter(FlowAdapter)` | 无 | 发送前定制 `ChatRequest` 的函数式接口 |

`RequestDefaults` 语义要点：`knowledgeScopes`/`knowledgeDocs` 支持三层入口（Builder、worker.yml `request.knowledge-*`、starter `taiyiflow.request.knowledge-*`）；`longTermMemoryEnabled`/`crossSessionMemoryEnabled` 仅填补缺省（null 不下发，由服务端默认决定），显式值原样下发；`output_language` 在服务端无消费点，SDK 不设字段（如需透传可走 tweaks）。

### 权限

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `permissionPolicy(PermissionPolicy)` | `READONLY` | `AUTO` / `CONFIRM` / `READONLY` 三档兼容，见 [hooks.md](hooks.md) |
| `permissionSettings(PermissionSettings)` | 按 policy 构造 | 细粒度权限：规则/类别档位/写确认/浏览器模式；配置后替代裸档位成为默认 `PermissionGate`，见 [hooks.md](hooks.md) |
| `permissionHook(PermissionHook)` | 无 | 策略级审批钩子（Hook）；未挂时会话回落 `PermissionGate` |
| `permissionTimeout(Duration)` | 300s | ASK 审批等待超时；`Duration.ZERO` 永不超时；其他有效值 10–6000s；超时返回显式 timeout 结果（≠DENY） |

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
| `workspace(Path)` | 未配置 | 会话工作区根目录；`workspaceFor(flowId, sessionId)` 创建确定性的隔离子目录 |
| `skillSupplier(SkillSupplier, List<String>)` | 无 | 配置技能供应器与默认全文技能名单，见 [skills.md](skills.md) |

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

- `baseUrl` 非空非 blank；`apiKey` 或 `accessToken` 至少一个。
- `flowId` 可省略；省略或 blank 时自动读取平台 `/config` 的非空 `default_flow_id`。显式 `flowId` 与 `useServerDefaultFlow()` 互斥。
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
ConfigClient config();                     // 服务端配置、默认 Flow 与媒体理解配置
FileClient files();                        // 会话文件上传/下载
FlowRunClient flowRuns();                  // 非流式同步/异步运行、任务轮询与取消
FlowMetadata getFlowMetadata();            // Flow Metadata（进程内缓存）
FlowMetadata refreshFlowMetadata();        // 强制刷新 Flow Metadata
McpClient mcpServers();                    // 服务端 MCP 注册管理
CollaborationTaskClient collaborationTasks(); // 协同任务生命周期上报
ExecutionController executionControl();    // run 级暂停/取消/检查点查询
ToolCallRecordClient toolCallRecords();    // 平台工具调用记录对账
NotificationClient notifications();        // 通知 recent / markRead（REST 兜底）
ModelsClient models();                     // Flow 可用模型列表
SessionGroupClient sessionGroups();        // 会话分组 CRUD
IntentionClient intentions();              // run/message 意图记录兜底补查（404→null）
ClientDirectoryClient clientDirectory();   // 在线桌面/协同主体目录（发起方形态）
QuotaCreditClient quotaCredits();          // 配额/积分预检（4003 为 WS 关闭码）
PlatformProbeClient probes();              // 平台 /health、/api/health_check、/api/v1/version
AgentAppClient agentApps();                // 智能体应用只读目录
AgentPluginClient agentPlugins();          // 智能体插件只读目录

// 运行时组件
PluginManager getPluginManager();          // 工具注册表
ToolRuntime getToolRuntime();
SessionManager getSessionManager();
Path getWorkspace();
Path workspaceFor(String flowId, String sessionId);
SkillSupplier getSkillSupplier();              // 未配置时为 null
List<String> getDefaultSkillNames();
CollaborationPlugin getCollaborationPlugin(); // 未启用协同时为 null

// 流协议
StreamProtocol resolveStreamProtocol();
StreamProtocol refreshStreamProtocol();

// 补传本 flow 持久化的检查点首帧提交（不执行工具）
void flushResumeReports();
```

### Flow Metadata 与非流式运行

`getFlowMetadata()` 读取 `GET /api/v1/run/{flow}/metadata`，提供名称、能力特性（features）、知识库和未建模扩展字段；首次成功后缓存，`refreshFlowMetadata()` 强制刷新。元数据只用于能力判断，不会自动开启本地工具或提升权限。

`flowRuns()` 走非流式 `POST /api/v1/run/{flow}`，支持同步结果和异步任务：

```java
ChatRequest request = new ChatRequest("生成摘要", "flow-run-session");
try (FlowRunClient runs = agent.flowRuns()) {
    FlowRunClient.RunResult synchronous = runs.run(agent.getFlowId(), request);

    FlowRunClient.Task submitted = runs.submitAsync(agent.getFlowId(), request);
    FlowRunClient.Task finished = runs.await(submitted.taskId(),
        FlowRunClient.PollOptions.defaults(Duration.ofMinutes(5)), null);
}
```

异步提交只接受 HTTP 202 和有效 taskId；网络失败、非 202 或缺少 taskId 时抛 `SubmissionUnknownException`，提交结果未知，不得盲目重发。`await` 超时抛 `FlowRunTimeoutException` 并尽力请求取消；取消结果仍以平台任务状态为准。

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
| `resume()` | 自动发现并恢复当前可续跑检查点（checkpoint）；受 per-checkpoint 租约锁保护 |
| `resume(ResumableCheckpoint)` | 恢复指定检查点；租约被占用时抛 `IllegalStateException`；建流 60s 无首个事件按可恢复错误重试 |
| `retry()` | 新轮次：服务端已持久化则续做，否则重发原输入 |
| `repeat()` | 新轮次：复用上一轮输入、参数覆盖、文件与请求类型（input/tweaks/files/requestType） |
| `interrupt()` | 仅本地中断，不调服务端控制 API |

### 审批裁决

`PermissionHook` 返回 `ASK` 后，工具调用进入会话级审批等待：

```java
boolean accepted = session.approve(toolCallId);
boolean rejected = session.deny(toolCallId, "not allowed");
```

重复或未知回执返回 `false`。超时（默认 300s）形成显式 timeout 失败结果（≠DENY，提示模型换方案）并回传平台。

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

会话内置空闲检测，可通过 Builder 配置（DigitalWorker / Spring / worker.yml 有同名配置项，默认值一致）：

- 无工具执行时：流空闲超过 `streamIdleTimeout`（默认 **1200 秒**）触发超时处理；`Duration.ZERO` 禁用；
- 有工具执行时：放宽到 `toolExecutionTimeout`；缺省自动推导 = 已注册工具最大执行时长 + 60s，且不低于 **31 分钟**；显式设置低于该下限的值会被抬升到下限；
- 每次流活动、工具起止都会重置计时。

单工具执行超时另由 `ToolRuntime.Config.executionTimeout`（默认 60s）控制，与看门狗相互独立。

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
| `ToolCallRecordClient` | `list(flowId, sessionId, messageId, Query)` → `Page`、`byCallId(...)` → `Map<String, Record>`（`resultReceived`/`frozenPaused`/`frozenOffline`） |
| `NotificationClient` | `recent([limit])`（limit 1–100，默认 100）、`markRead(notificationId)` |
| `ModelsClient` | `list(flowId[, clientType])` |
| `SessionGroupClient` | `list(flowId[, clientId, keyword, orderBy, offset, limit])`、`create`、`get`、`update`、`delete` |

端点明细见 [protocol.md](protocol.md)。

## 文件上传与带文件对话

```java
FileClient.UploadedFile uploaded = agent.files()
    .upload(agent.getFlowId(), session.getSessionId(), Path.of("report.pdf"));
session.chat("请总结这份报告", List.of(uploaded.filePath()));
session.await();
```