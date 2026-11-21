# 执行控制、恢复与结果补传

## 执行控制 API

`AgentSession` 的控制方法分三类：

| 方法 | 通道 | 语义 |
|------|------|------|
| `pause()` | 服务端 REST | 请求暂停当前执行轮次（run），返回 `AgentLoopControlResult` |
| `cancel()` | 服务端 REST + 本地 | 服务端取消并关闭本地流资源 |
| `interrupt()` | 仅本地 | 本地中断，不调服务端控制 API |
| `attach()` | 服务端 | 重接仍在执行的原轮次，**不创建新执行** |
| `resume()` / `resume(checkpoint)` | 服务端 | 通过服务端检查点（checkpoint）恢复，**不创建新执行** |
| `retry()` | 新轮次 | 已持久化时续做，未持久化时重发原输入 |
| `repeat()` | 新轮次 | 复用原始输入、参数覆盖、文件与请求类型（input/tweaks/files/requestType） |

```java
session.pause();
session.resume();                 // 自动发现并恢复可续跑检查点；重接活跃 run 可改用 attach()
session.await();

session.retry();                  // 新轮次
session.await();
session.repeat();                 // 新轮次
session.await();
```

可恢复检查点查询：

```java
List<ResumableCheckpoint> checkpoints =
    agent.executionControl().listResumable(flowId, sessionId);
session.resume(checkpoints.get(0));
```

完整示例见 `PauseResumeExample`、`RetryRepeatExample`。

### 暂停/取消对在途工具的影响

- `pause()` 在服务端**受理成功后**才中止本地在途工具（与 Electron 一致：先设暂停意图、POST 受理后才 abort 本地流）；未完成调用由服务端在暂停检查点标记为取消，工具的中断信号被触发。
- `cancel()` 立即标记取消并中止在途工具，再走服务端取消与本地资源关闭。
- **迟到结果短路**：工具分发返回后再次校验轮次代次（generation）、取消标记与会话状态（PAUSING/PAUSED/CANCELLED/INTERRUPTED），命中即丢弃结果，不回传平台、不写发件箱。
- `resume()` / `attach()` / 自动恢复 / `restart` 启动新流时统一重置取消标记，恢复后的工具调用不受上一轮取消影响。

## 断线恢复策略

默认 `RecoveryPolicy.MANUAL`：断线后不自动动作，由调用者决定重接（attach）/恢复（resume）/重试（retry）。

设置 `.recoveryPolicy(RecoveryPolicy.AUTO)` 后，SDK 按退避策略依次对账并选择：

1. **重接（attach）**：原轮次仍在执行 → 重接；
2. **检查点恢复（checkpoint resume）**：存在可续跑检查点 → 恢复；
3. **自动启动（start）**：仅当确认原执行丢失，且不存在等待中/待处理/不确定（waiting/pending/uncertain）工具副作用时才允许。

状态未知或平台不可达时不会盲目重发。可调参数：

```java
.recoveryPolicy(RecoveryPolicy.AUTO)
.recoveryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(60))  // 0 < min <= max
.recoveryMaxAttempts(8)                                           // > 0
.autoRestartOnExecutionLoss(true)
```

优先复用 session_id 并换新 run_id；原会话失效才新建会话。

> DigitalWorker 的恢复默认值与 TaiyiAgent 不同：worker 默认 `AUTO`，TaiyiAgent 默认 `MANUAL`。

### 恢复并行化、租约锁与建流超时

对照 Electron `agent-loop-recovery` 的客户端行为（无新协议字段）：

- **per-checkpoint 租约锁**：`ResumeLeaseRegistry` 以 `sessionId/checkpointId` 为键做进程内互斥（单实例容器下等价 Electron 的 Web Locks/localStorage 租约），默认租期 5min，原子获取、仅持有者可释放、过期可接管。三条检查点恢复路径（AUTO 对账恢复、`resume(checkpoint)`、`resume()` 自动发现）统一受保护；租约被占用时抛 `IllegalStateException`，不并发恢复同一检查点。
- **建流超时**：RESUMING 执行开流后 60s 内未收到首个流事件（对照 Electron `RESUME_STREAM_TIMEOUT_MS`），按可恢复错误进入断线退避重试；首个事件到达即解除。
- **重连恢复轮**：DigitalWorker 协同 WS 收到 `connection.ready` 时，**先补传发件箱（flushReports）再并行恢复**所有 DISCONNECTED 会话（`AgentSessionTaskHandler.recoverDisconnectedSessions`）：单飞（single-flight）防重入，每会话独立虚拟线程恢复，单会话失败仅记 pending 等待下一轮，不中断整轮。重连与平台 resume 通知同属显式外部触发，**MANUAL 策略下同样生效**。
- 回调补偿队列口径：400 确定性拒绝不重试（转死信）、`tool_call_paused`（用户暂停冻结）直接丢弃；401/403/408/429/5xx 与网络错误按退避重试。与「状态未知不重放」约束联合生效，不放大副作用重放。

## 会话并发配额与 auto-loop 轮次

- **配额来源**：`TaiyiAgent.Builder.maxSessionConcurrency(int)` 显式配置优先；缺省跟随服务端 `/config` 的 `client_max_session_concurrency`（缺省 3，显式 0 保留）。`/config` 拉取失败时回落 **-1 不限**（Electron 在 config 缺失时回落默认 3 收紧；Java SDK 选择不阻断后台任务，属文档化偏差）。`agent.getMaxSessionConcurrency()` 返回生效值。
- **拦截口径**：仅 `chat()` 新执行入口计数，状态为 CONNECTING/STREAMING/TOOL_CALLING/PAUSING/PAUSED/RESUMING/RESTARTING 的会话算执行中（DISCONNECTED 不算）；达到上限抛 `IllegalStateException("Maximum concurrent executing sessions reached: N")`。resume/attach/retry/repeat 与自动恢复不受配额限制。`0` 阻止一切新对话。
- **auto-loop 轮次回落**：请求显式开启 auto-loop 且未指定轮次时，回落服务端 `agent_auto_loop_max_rounds`；显式轮次或显式关闭始终优先。SDK 默认显式下发 `agent_auto_loop_enabled=false`（决策点 10）。
- **子任务并行度合并**：本地 `subtaskMaxParallelism`（默认 4）与服务端 `max_parallelism_subtask` 取小；服务端缺失用本地值，服务端为 0 时禁用 `run_subtask` 工具供应。`max_input_tokens` 为服务端权威，SDK 仅提供访问器，不下发。
- **同工作区占用警告**：同一 agent 多会话共享 workspace 并行执行时记非阻断 WARN（可能互相覆盖文件），不阻断发起；自动续跑/检查点恢复不提示。

## 工具结果回传与补传发件箱（Outbox）

工具结果通过 REST `POST /api/v1/agents/client/call/result` 回传（首帧后服务端不再读 WS，禁止 WS 回传）。回传失败进入补偿发件箱（Outbox）：

### ReportOutbox

两种实现：

| 实现 | 持久性 | 用途 |
|------|--------|------|
| `InMemoryReportOutbox` | 进程内存（默认） | 进程退出后未确认记录丢失 |
| `FileReportOutbox(stateDir)` | 磁盘 | 重启后补传；每条记录一个 JSON 文件，原子替换，POSIX 0600 |

记录类型：`CLIENT_TOOL_RESULT`（单工具结果）与 `CHECKPOINT_TOOL_RESULTS`（检查点批量提交）。

默认容量限额 `ReportOutbox.Limits.DEFAULT`：

| 项 | 默认值 |
|----|--------|
| `maxEntries` | 1000 条 |
| `maxEntryBytes` | 1 MiB |
| `maxTotalBytes` | 16 MiB |
| `maxRetries` | 32（耗尽转死信（DEAD_LETTER）） |

语义要点：

- 同 id 重复写入：结果一致幂等返回；不一致旧条目转死信（`result-conflict`）并抛 `ReportConflictException`。
- 损坏/超限/身份不匹配文件移入隔离（quarantine）目录。
- 补传**只重放结果，不重新执行工具或任务**。
- 失败分类：客户端回调 400+`tool_call_paused` → 视为已终结直接丢弃；其余 4xx 确定性拒绝 → 死信不重试；401/403/408/429/5xx 与网络错误 → 退避重试（预算耗尽转死信）。检查点批量提交同口径（400 确定性拒绝转死信，瞬态错误重试）。
- 冲突或损坏通过告警通知（worker 侧为 `WorkerHook.onError("report-outbox", ...)`）。

### ToolResultReporter

```java
ToolResultReporter reporter = new ToolResultReporter(restClient);          // 3 次重试、500ms 初始退避
reporter.report(toolCall, toolResult);                                     // 失败抛 ToolResultReportException
reporter.reportPersisted(toolCall, toolResult);                            // 经发件箱（Outbox）持久化补偿
reporter.flush();                                                          // 触发补传
```

`TaiyiAgent.flushResumeReports()` 重放本 flow 持久化的检查点首帧提交（不执行工具）；`ToolRuntime.flushClientResults(restClient)` 补传未确认的客户端工具结果。

### 平台工具调用记录对账（ToolCallRecordClient）

补传/死信处理前先查平台权威记录，**只分类、绝不重放平台状态未知的结果**：

```java
ToolCallRecordClient records = agent.toolCallRecords();
ToolCallRecordClient.Page page = records.list(flowId, sessionId, messageId,
    ToolCallRecordClient.Query.defaults().withRunId(runId).withViaClient(true));
Map<String, ToolCallRecordClient.Record> byCallId =
    records.byCallId(flowId, sessionId, messageId, runId, true);
```

端点：`GET /api/v1/agents/flows/{flow}/sessions/{session}/messages/{message}/tool-calls`。`Record` 提供 `resultReceived()`（平台已收到终态结果）、`frozenPaused()`（暂停冻结）、`frozenOffline()`（离线冻结）。

`PlatformResultReconciler` 据此把发件箱条目分类为三种裁决（Verdict）：

| 裁决 | 条件 | 处理 |
|------|------|------|
| `RECEIVED` | 全部调用平台已有终态 success/error 记录 | 删除本地条目，不重放 |
| `DISCARD_PAUSED` | 全部调用已收到或暂停冻结（tool_call_paused） | 丢弃条目，不重放 |
| `UNKNOWN` | 记录缺失、非终态或查询失败 | 保留条目等待人工对账 |

死信（DEAD_LETTER）中的客户端结果由 `ToolResultReporter.reconcileDeadLetters()` 按同一口径清理；检查点批量提交的死信在 `flushResumeReports()` 时逐条对账。

## 审批与恢复的关系

- `PermissionHook` 返回 ASK 后的等待裁决仅存进程内存：重接（attach）前补偿重报，恢复（resume）时随检查点提交，不写入本地权威结果库。
- 存在未确认工具副作用（waiting/pending/uncertain）时，AUTO 恢复不会自动启动新执行。
- 跨进程/跨实例恢复以平台任务、执行轮次/检查点（run/checkpoint）和已接收结果为权威；缺少关联或状态不明时保持 `REPORT_UNKNOWN` 等待人工处理。

## 平台驱动恢复（agent_loop.resume.v1）

除本地 AUTO 恢复外，平台可经协同 WS 下发 `agent_loop.resume_requested` 通知，驱动跨实例/重启后的恢复：

- `CollaborationPlugin.onResumeRequested(Consumer<ResumeRequest>)` 接收请求（notificationId/checkpointId/checkpointVersion 必填，另带 sessionId/runId/flowId/resumeStage/reason/expiresAt）；
- 处理方负责实际恢复，并调用 `sendResumeNotificationAck(notificationId, checkpointId, checkpointVersion, result)` 回执；result 与 Electron 语义一致：`accepted`（首个流事件确认后才回执）、`already_running`、`ignored_stale` / `ignored_expired` / `ignored_unsupported` / `ignored_stopped`；
- `DigitalWorker` 已内置该链路（`AgentSessionTaskHandler.resumeAgentLoop`），详见 [digital-worker.md](digital-worker.md)；通知的 REST 兜底见 [collaboration.md](collaboration.md)。

## 子任务取消契约

受控子任务（SubtaskOrchestrator）与平台任务执行（TaskExecutor）的取消遵循 Electron 同款契约：

- **发起方**：取消走 `POST /api/v1/agents/client/collaborations/{taskId}/cancel`，body 携带 `request_id`（可选 `expected_version`）。远程等待中的子任务**禁止发起方本地 PATCH 终态**——服务端受理取消时会幂等跳过，本地 PATCH 会造成状态错乱；只有本地执行的任务才允许执行方 PATCH 终态确认。
- **执行方确认**：收到取消后以 `PlatformTaskReporter.cancelled`（PATCH 终态，publisher=executor）向平台确认。
- **竞态三查**（对照 Electron `_cancelRequests` 登记）：① 本地启动入口先查取消登记；② running 回报（REST）成功后、开流前再查——覆盖回报窗口内到达的取消；③ 执行成功后、success PATCH 前最后再查。TaskExecutor 维护 pending-cancel 登记（LRU 上限），"取消早于任务注册"也能命中；版本更旧的取消请求被丢弃。
- **创建窗口**：子任务 create REST 期间到达的取消，在创建返回后立即对平台任务发 POST cancel，本地不再启动执行。
- **轮询可中断**：等待终态的轮询线程在取消时被立即 interrupt 唤醒，不等轮询间隔耗尽；结果状态记为 `cancelled`。
- **detached 不入取消链**：`wait_for_completion=false` 的远端子任务创建后立即返回（`detached: true`），不登记进父执行取消链；父取消或 SDK 关闭不会取消其平台任务。本地任务恒等待终态，不受该参数影响。
- **流准备窗口**：会话级任务控制在注册回调时同步重放待定取消；chat 前无 run 可远程取消时仅置标记即可阻止启动，运行循环以 200ms 有界 await 轮询标记，取消可即时生效。

## 会话状态机

`SessionState`：`IDLE → CONNECTING → STREAMING ⇄ TOOL_CALLING → DONE`，控制分支 `PAUSING → PAUSED → RESUMING`，异常分支 `DISCONNECTED / RESTARTING / CANCELLED / INTERRUPTED / ERROR`。

流空闲看门狗：无工具执行时默认 1200s 空闲超时（`streamIdleTimeout`，0 禁用）；有工具执行时放宽到 `toolExecutionTimeout`（缺省自动推导 = 注册工具最大时长 + 60s，且不低于 31min），见 [agent-and-session.md](agent-and-session.md)。