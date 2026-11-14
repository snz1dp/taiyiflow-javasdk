# 执行控制、恢复与结果补传

## 执行控制 API

`AgentSession` 的控制方法分三类：

| 方法 | 通道 | 语义 |
|------|------|------|
| `pause()` | 服务端 REST | 请求暂停当前 run，返回 `AgentLoopControlResult` |
| `cancel()` | 服务端 REST + 本地 | 服务端取消并关闭本地流资源 |
| `interrupt()` | 仅本地 | 本地中断，不调服务端控制 API |
| `attach()` | 服务端 | 重接仍在执行的原 run，**不创建新执行** |
| `resume()` / `resume(checkpoint)` | 服务端 | 通过服务端检查点恢复，**不创建新执行** |
| `retry()` | 新 run | 已持久化时续做，未持久化时重发原输入 |
| `repeat()` | 新 run | 复用原始 input/tweaks/files/requestType |

```java
session.pause();
session.resume();                 // 自动发现并恢复可续跑检查点；重接活跃 run 可改用 attach()
session.await();

session.retry();                  // 新 run
session.await();
session.repeat();                 // 新 run
session.await();
```

可恢复检查点查询：

```java
List<ResumableCheckpoint> checkpoints =
    agent.executionControl().listResumable(flowId, sessionId);
session.resume(checkpoints.get(0));
```

完整示例见 `PauseResumeExample`、`RetryRepeatExample`。

## 断线恢复策略

默认 `RecoveryPolicy.MANUAL`：断线后不自动动作，由调用者决定 attach/resume/retry。

设置 `.recoveryPolicy(RecoveryPolicy.AUTO)` 后，SDK 按退避策略依次对账并选择：

1. **attach**：原 run 仍在执行 → 重接；
2. **checkpoint resume**：存在可续跑检查点 → 恢复；
3. **自动 start**：仅当确认原执行丢失，且不存在 waiting/pending/uncertain 工具副作用时才允许。

状态未知或平台不可达时不会盲目重发。可调参数：

```java
.recoveryPolicy(RecoveryPolicy.AUTO)
.recoveryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(60))  // 0 < min <= max
.recoveryMaxAttempts(8)                                           // > 0
.autoRestartOnExecutionLoss(true)
```

优先复用 session_id 并换新 run_id；原会话失效才新建会话。

> DigitalWorker 的恢复默认值与 TaiyiAgent 不同：worker 默认 `AUTO`，TaiyiAgent 默认 `MANUAL`。

## 工具结果回传与 Outbox

工具结果通过 REST `POST /api/v1/agents/client/call/result` 回传（首帧后服务端不再读 WS，禁止 WS 回传）。回传失败进入补偿 Outbox：

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
| `maxRetries` | 32（耗尽转 DEAD_LETTER） |

语义要点：

- 同 id 重复写入：结果一致幂等返回；不一致旧条目转死信（`result-conflict`）并抛 `ReportConflictException`。
- 损坏/超限/身份不匹配文件移入 quarantine 隔离目录。
- 补传**只重放结果，不重新执行工具或任务**。
- 冲突或损坏通过告警通知（worker 侧为 `WorkerHook.onError("report-outbox", ...)`）。

### ToolResultReporter

```java
ToolResultReporter reporter = new ToolResultReporter(restClient);          // 3 次重试、500ms 初始退避
reporter.report(toolCall, toolResult);                                     // 失败抛 ToolResultReportException
reporter.reportPersisted(toolCall, toolResult);                            // 经 Outbox 持久化补偿
reporter.flush();                                                          // 触发补传
```

`TaiyiAgent.flushResumeReports()` 重放本 flow 持久化的检查点首帧提交（不执行工具）；`ToolRuntime.flushClientResults(restClient)` 补传未确认的客户端工具结果。

## 审批与恢复的关系

- `PermissionHook` 返回 ASK 后的等待裁决仅存进程内存：attach 前补偿重报，resume 时随检查点提交，不写入本地权威结果库。
- 存在未确认工具副作用（waiting/pending/uncertain）时，AUTO 恢复不会自动 start。
- 跨进程/跨实例恢复以平台任务、run/checkpoint 和已接收结果为权威；缺少关联或状态不明时保持 `REPORT_UNKNOWN` 等待人工处理。

## 会话状态机

`SessionState`：`IDLE → CONNECTING → STREAMING ⇄ TOOL_CALLING → DONE`，控制分支 `PAUSING → PAUSED → RESUMING`，异常分支 `DISCONNECTED / RESTARTING / CANCELLED / INTERRUPTED / ERROR`。

流空闲看门狗：无工具执行时 90s 空闲超时，有工具执行时放宽到 15 分钟（当前实现固定值）。