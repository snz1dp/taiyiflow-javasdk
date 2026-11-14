# DigitalWorker 数字人运行时

`DigitalWorker`（`taiyiflow-sdk-worker`）让 Java 进程成为长期在线的数字员工：有身份（workerId/角色/能力）、可被平台调度接活（协同 WS）、可持续工作（暂停/恢复/取消）、可请示汇报（审批/状态回报）、可自愈（断线恢复/结果补传）。

## 最小可用示例

```java
DigitalWorker worker = DigitalWorker.builder()
    .baseUrl(baseUrl)
    .auth(WorkerAuth.apiKey(apiKey))
    .identity(WorkerIdentity.of("worker-1", "后端数字员工"))
    .workspace(Path.of("/var/lib/taiyiflow/worker"))
    .stateDir(Path.of("/var/lib/taiyiflow/runtime-state")) // 独立瞬态 Outbox 持久目录
    .identityValidator(thirdPartyIdentityValidator)        // 必填：第三方身份登记与绑定校验
    .approvalHandler(platformApprovalHandler)              // PLATFORM 审批通道适配器
    .build();
worker.start();
worker.await();   // 常驻；SIGTERM 时 stop()
```

未配置 `.onTask(...)` 时，`remote_task`、`dialogue_subtask` 与定时任务默认路由到对应 flow 的 `AgentSession`：同一 flow + session 串行执行，结果中的 `session_id`、`run_id` 和文本通过平台任务/定时任务 REST 接口回报。自定义 `.onTask(...)` 始终优先。

## Builder 配置

### 必填项

| 方法 | 说明 |
|------|------|
| `baseUrl(String)` | 平台地址 |
| `auth(WorkerAuth)` | 认证（见下文） |
| `workspace(Path)` | 工作区目录 |
| `identityValidator(WorkerIdentityValidator)` | **第三方身份校验，硬门槛**；SDK 从不自行登记 workerId |
| `identity(WorkerIdentity)` 或 `workerRegistrar(WorkerRegistrar)` | 二选一：预置身份，或经第三方注册器注册（此时 `displayName` 必填） |

### 可选项与默认值

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `displayName(String)` | — | 注册路径必填 |
| `role(WorkerRole)` | `COLLABORATION_WORKER` | 另有 `PRIMARY_CLI`（持久化 instanceId） |
| `abilities(Ability...)` | 空集 | `Ability.shell()/files()/search()/of(name)`；只声明已实现的能力 |
| `stateDir(Path)` | null → 内存 Outbox | 非 null → `FileReportOutbox`，重启后可补传 |
| `outboxLimits(ReportOutbox.Limits)` | 1000 条 / 1MiB / 16MiB / 32 次 | 见 [execution-control.md](execution-control.md) |
| `concurrency(int)` | 3 | 任务并发（同时作为工具运行时的全局/会话并发）；排队容量 128 |
| `recoveryPolicy(RecoveryPolicy)` | `AUTO` | 注意与 TaiyiAgent 默认 MANUAL 不同 |
| `permissionPolicy(PermissionPolicy)` | `CONFIRM` | 写操作进入审批 |
| `approvalChannel(ApprovalChannel)` | `PLATFORM` | 另有 `LOCAL_CALLBACK` |
| `approvalHandler(PermissionHook)` | null | ASK 决策且无 handler 时运行期失败关闭 |
| `approvalAudit(ApprovalAudit)` | null | LOCAL_CALLBACK 通道下运行期强制要求 |
| `onWorker(WorkerHook)` | 空 | 可多次追加 |
| `onTask(WorkerTaskHandler)` | null → 内置会话路由 | 自定义任务处理器 |
| `subtaskMcpResolver(SubtaskMcpResolver)` | null → 返回空配置 | 子任务 MCP 按名解析 |
| `taskReporter(TaskReporter)` | null → `PlatformTaskReporter` | 结果回报器 |
| `clientId(String)` / `deviceId(String)` | 随机 UUID / auth.deviceId() | 连接标识 |
| `connectTimeout(Duration)` | 15s | 连接就绪等待 |
| `drainTimeout(Duration)` | 30s | 停机排空 |
| `requestTimeout(Duration)` | 30s | REST 超时 |

## 实例方法

```java
worker.start();                       // 只能启动一次
worker.pause();  worker.resume();     // 暂停/恢复接活
worker.drain(Duration.ofSeconds(30)); // 停接新任务并等待在途任务完成
worker.stop();                        // 幂等停机
worker.await();

WorkerState getState();               // ONLINE / BUSY / PAUSED / OFFLINE
int getActiveTaskCount();
TaskExecutionState getTaskState(task); // QUEUED/RUNNING/PAUSED/SUCCESS/FAILED/CANCELLED/REPORT_UNKNOWN
boolean isConnected();
String getInstanceId();               // 仅 PRIMARY_CLI 角色非 null

MemoryClient memories();              // 平台记忆 REST
FileClient files();                   // 平台文件 REST
FileClient.UploadedFile uploadArtifact(String flowId, String sessionId, Path file);
```

`start()` 流程：认证 → （无预置身份时）registrar 注册 → identityValidator 校验 → PRIMARY_CLI 时加载/创建 instanceId（workspace/instance_id）→ 建协同连接 → 等待 `connection.ready`（connectTimeout 内）→ `WorkerHook.onReady`。连接就绪时自动补传 Outbox 与未知回报。

## 身份与认证

### 身份 SPI（第三方实现）

```java
@FunctionalInterface
public interface WorkerIdentityValidator {
    void validate(WorkerIdentity identity, WorkerRole role, Set<Ability> abilities) throws Exception;
}

@FunctionalInterface
public interface WorkerRegistrar {
    WorkerRegistrationResult register(WorkerRegistrationRequest request) throws Exception;
}
```

- workerId、角色和能力必须由第三方身份系统登记并绑定认证主体；智能体 Agent 后台不承担 workerId 登记，SDK 也不自动注册。
- `WorkerIdentity.of(workerId, displayName)`；`WorkerRegistrationRequest(displayName, role, abilities)`。
- 校验抛异常即启动失败。

### WorkerAuth

```java
WorkerAuth.apiKey("...");
WorkerAuth.jwt("...");
WorkerAuth.persistentToken(token, deviceId, credentialStore);  // store 必填
```

- apiKey/jwt：静态令牌，不支持刷新。
- persistentToken：经平台 SSO（salt + SHA-256 签名）换发 accessToken；响应携带新 persistent_token 时自动轮换并写入 store；HTTP 400/401/403/410 为确定性拒绝，清空凭据。
- 所有长期流式连接读取最新 accessToken；401 换牌按认证代次合并（同代只交换一次），每个操作最多重试一次。

### 加密凭证存储

持久令牌只能放独立加密 `CredentialStore`，不能写入普通 workspace：

```java
new EncryptedFileCredentialStore(credentialFile, keyFile);
new EncryptedFileCredentialStore(credentialFile, keyFile, workspace); // 强制两文件位于 workspace 之外
```

AES-256-GCM 加密、密钥不存在时 SecureRandom 生成、原子写入、POSIX 0600；`clear()` 只删凭据文件不删密钥。

## 任务执行与可靠性

### Worker SPI

```java
@FunctionalInterface
public interface WorkerTaskHandler {
    Object execute(CollaborationPlugin.TaskDispatch task, TaskProgress progress) throws Exception;
}

@FunctionalInterface
public interface TaskProgress { void report(JsonNode progress) throws Exception; }

public interface TaskReporter {
    void running(TaskDispatch task, JsonNode progress) throws Exception;
    void success(TaskDispatch task, Object result) throws Exception;
    void failure(TaskDispatch task, String error) throws Exception;
    default void cancelled(TaskDispatch task, String cancelRequestId) throws Exception { ... }
}
```

### TaskExecutor 可靠性语义

- **running 前置回报**：先成功回报 running 才执行任务，避免平台未接收状态时产生副作用；running 回报失败进入 `REPORT_UNKNOWN`，不执行任务，也不通过重跑补救。
- **幂等**：按 `task:<taskId>`（定时任务 `schedule:<runRecordId>`）去重；同版本重复下发不重复执行；注册表容量 1024，满时淘汰最旧终态记录。
- **终态回报**：success/failed/cancelled 回报失败进入 `REPORT_UNKNOWN`；连接就绪后 `flushUnknownReports()` 只补传回报，不重跑任务。
- **对账**：重连后通过 `tasks.fetch_recent` 分页补拉。只有平台明确为 `pending` 且未取消的任务会自动执行；`dispatched/acknowledged/running` 只对账不重放；平台终态用于校正本地状态；旧版本快照不会覆盖新版本。
- **取消**：携带版本与 cancelRequestId，旧版本取消拒绝、同请求幂等；未启动的任务直接撤销，已启动的任务经控制接口取消。
- `taskType == "primary"` 的任务直接执行 handler，不经 TaskExecutor、无平台回报。

### 默认会话任务处理器

`AgentSessionTaskHandler` 按 flowId 缓存 `TaiyiAgent`，同 flow + session 串行；`dialogue_subtask` 校验 requestMode 与父子 run 关联，应用能力快照后执行，返回 `{session_id, run_id, text}`。

### 单实例约束

同一 workerId 只部署一个容器副本，由容器平台负责心跳检测、故障摘除和快速拉起。SDK 不实现主备、选举或 fencing 协议。容器重启后的跨进程恢复以平台任务、run/checkpoint 和已接收结果为权威；状态不明保持 `REPORT_UNKNOWN` 等待人工处理。

## 审批

默认 `ApprovalChannel.PLATFORM`：写操作进入审批，只读直接放行。

- **PLATFORM**：需要显式 `approvalHandler`（PermissionHook）对接真实审批服务；未配置时需要审批的工具失败关闭。handler 必须返回 ALLOW 或 DENY（不允许 ASK 作为终局）。
- **LOCAL_CALLBACK**：必须同时配置 `approvalAudit`；本地 ALLOW/DENY 只有在平台审计完成后才返回工具执行链。

```java
@FunctionalInterface
public interface ApprovalAudit {
    CompletableFuture<Void> record(String sessionId, ToolCall toolCall, PermissionHook.Decision decision);
}
```

> DigitalWorker 没有 approve/deny 公开方法；会话级人工裁决（approve/deny）属于 TaiyiAgent 场景，见 [hooks.md](hooks.md)。

## 受控子任务（run_subtask）

DigitalWorker 为默认智能体会话注册 `run_subtask` 工具，支持单 prompt 和 `subtasks` 批量：

| 参数 | 默认 | 说明 |
|------|------|------|
| `subtasks` | 缺省时把 arguments 本身当单个子任务 | 数组元素为 prompt 字符串或对象 |
| `prompt` | 必填（子任务对象） | 子任务指令 |
| `wait_for_completion` | 必须为 true | 不支持 detached 子任务 |
| `serial` | false | true 时并行度强制 1 |
| `max_parallelism` | min(子任务数, 4) | 上限 4 |
| `request_mode` | 继承父级 | 只能保持或降级 agent → plan → ask，提权拒绝 |
| `flow_id` / `target_client_id` / `target_device_id` | 子项 → 顶层 → 父上下文/本机 | 投递目标 |
| `delivery_policy` | `local_default` | 本机任务复用 TaskExecutor 的完整可靠性链路 |
| `timeout_seconds` | 300 | 上限 3600 |

约束：子任务数上限 16；父 run 必须已持久化；`ask`/`plan` 模式在 Hook 和 takeover 前拒绝写工具；未列入 `available_tools` 的工具直接失败；批量结果按输入索引聚合，单项失败不抹掉其他结果。

每个子任务先通过 `POST /api/v1/agents/client/collaborations` 创建平台记录再投递；创建响应丢失时按父 run、幂等键和 child 标识查询平台对账，不直接重复创建。父运行取消或超时先调用平台取消（携带 request_id 与 expected_version），再补充本机取消。

发布时冻结能力快照：tools、requested skills、MCP server 名称及 reasoning/search/knowledge 配置；快照不包含 MCP env、URL 凭证或访问令牌。接收端按名称通过 SPI 获取当前身份可用的 MCP 配置并再次过滤：

```java
DigitalWorker worker = DigitalWorker.builder()
    // ...必填配置
    .subtaskMcpResolver((flowId, serverNames) ->
        mcpRegistry.configsFor(flowId, serverNames)) // 返回 ObjectNode：serverName -> 后端 MCP 配置
    .build();
```

## 观测：WorkerHook

```java
public interface WorkerHook {
    default void onReady(DigitalWorker worker) {}
    default void onTaskStart(CollaborationPlugin.TaskDispatch task) {}
    default void onTaskFinish(CollaborationPlugin.TaskDispatch task, TaskExecutionState state) {}
    default void onError(String stage, Throwable error) {}   // stage: start / task / report-outbox
    default void onStop() {}
}
```

Hook 异常被隔离（仅告警日志），不影响任务结果。可接日志、指标或追踪系统；日志不得包含令牌或完整业务结果。

## 记忆、产物与 workspace 边界

- `worker.memories()` / `worker.files()` 使用平台 REST，统一支持 401 换牌重试。
- 本地产物经 `worker.uploadArtifact(flowId, sessionId, path)` 上传。
- workspace 只保存 instance_id、最小运行标识和临时文件，**不保存权威业务结果、工具结果或长期任务历史**。
- `state-dir` 与 workspace、凭证目录分离，仅保存有界的瞬态补传 Outbox。

## 配置化启动（worker.yml）

除编程式 Builder 外，可用 YAML 配置 + 容器入口启动，完整键树见 [deployment.md](deployment.md)：

```bash
./mvnw -pl taiyiflow-sdk-worker -am package
java -cp 'taiyiflow-sdk-worker/target/*-all.jar:build/adapters/*' \
  io.taiyiflow.sdk.worker.DigitalWorkerMain --config deploy/worker.yml
```

配置路径也可通过 `TAIYIFLOW_WORKER_CONFIG` 环境变量或 `taiyiflow.worker.config` 系统属性指定。

底层协同插件的任务幂等执行与平台回报示例见 `CollaborationExample`。