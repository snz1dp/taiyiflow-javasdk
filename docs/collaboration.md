# 协同插件（CollaborationPlugin / SchedulePlugin）

`taiyiflow-sdk-collaboration` 是零核心（core）依赖的独立模块，提供被动接收平台任务的协同 WebSocket 能力。`DigitalWorker` 构建在其之上；也可以单独使用做轻量无头命令行（CLI）。

## CollaborationPlugin

连接 `{base}/api/v1/agents/client/websocket`，封套格式 `{type, event, payload}`；握手传 client-id/device-id，收 `connection.ready` 后开始收任务（无 register 首帧）。

### 构造

```java
new CollaborationPlugin(baseUrl, apiKey);                          // 角色 collaboration_worker，clientId 随机
new CollaborationPlugin(baseUrl, apiKey, role);
new CollaborationPlugin(baseUrl, apiKey, role, clientId, deviceId, instanceId);
new CollaborationPlugin(baseUrl, apiKey, role, clientId, deviceId, instanceId, credentialHeaders);
new CollaborationPlugin(baseUrl, accessTokenSupplier, role, clientId, deviceId, instanceId, credentialHeaders);
```

- 角色常量：`ROLE_COLLABORATION_WORKER = "collaboration_worker"`、`ROLE_PRIMARY_CLI = "primary_cli"`；其他角色抛异常。
- `clientId` 与 `deviceId` 至少一个非空；`primary_cli` 必须提供 `instanceId`。
- worker 角色握手附能力声明：`notification.v1, task.remote_task.v1, task.dialogue_subtask.v1`。
- 默认节奏：心跳 30s、入站静默超时 90s、重连基础延迟 5s / 上限 60s（指数退避 + 抖动）；`activityTimeouts(...)` 可在 start 前调整。

### 回调注册

```java
plugin.onTask(task -> { ... });                 // Consumer<TaskDispatch>，虚拟线程池执行；成功后自动回执（ack）
plugin.onTaskDispatch(task -> { ...; return true; }); // Predicate，同步调用；返回 false 拒收（不发回执）
// onTask 与 onTaskDispatch 互斥（互相置空）
plugin.onTaskCancel(cancel -> { ... });
plugin.onTaskSnapshot(snapshot -> { ... });     // 平台任务快照（对账）
plugin.onRelease(push -> { ... });              // 版本发布推送
plugin.onConnectionReady(payload -> { ... });
plugin.onKicked(reason -> { ... });             // 被踢下线（自动 stop）
```

### 生命周期与回执

```java
plugin.start();
plugin.stop();
plugin.isConnected();
plugin.isRunning();
plugin.sendSessionUpdate(sessionId);

// 任务回执（均有可选 Long expectedVersion 乐观锁重载）
plugin.sendTaskAck(taskId);
plugin.sendTaskResult(taskId, result);
plugin.sendTaskError(taskId, error);
plugin.sendTaskStatus(taskId, status);

// 定时任务回执
plugin.sendScheduleAck(scheduleId, runRecordId);
plugin.sendScheduleStatus(scheduleId, runRecordId, status);
plugin.sendScheduleResult(scheduleId, runRecordId, result);
plugin.sendScheduleError(scheduleId, runRecordId, error);
```

### TaskDispatch 字段

`taskId, taskKind, taskType, version, flowId, inputValue, sessionId, childSessionId, childRunId, sourceSessionId, sourceRunId, sourceMessageId, scheduleId, runRecordId, requestMode, instanceId, payload`。

`dialogue_subtask` 且带 `childSessionId` 时会用它覆盖 `sessionId`。

### 处理的服务端事件

| 事件 | 行为 |
|------|------|
| `connection.ready` | worker 角色拉取 `tasks.fetch_recent`（limit=20 游标翻页）补拉近期任务 |
| `task.dispatch` | 按 version 去重，回调成功后自动回执（ack） |
| `task.cancel` | 触发 onTaskCancel |
| `primary_task.dispatch` | primary 任务下发 |
| `schedule_task.dispatch` | 按 `schedule:<runRecordId>` 去重 |
| `tasks.recent` | 近期任务快照 |
| `connection.kicked` | stop + onKicked 回调 |
| `release.push` | onRelease 回调 |
| `connection.ping/pong` | 心跳 |

## 与 TaiyiAgent 的集成

```java
TaiyiAgent agent = TaiyiAgent.builder()
    .baseUrl(baseUrl)
    .apiKey(apiKey)
    .flowId(flowId)
    .enableCollaboration()              // Builder 方法：build 时立即创建并 start 协同连接
    .collaborationClientId("cli-1")
    .collaborationDeviceId("dev-1")
    .build();

// TaiyiAgent 只负责创建与启动，不自动挂任务回调——业务侧自行注册：
agent.getCollaborationPlugin().onTask(task -> {
    // 处理平台下发任务...
});

agent.shutdown();   // 自动停止协同连接
```

未启用协同时 `getCollaborationPlugin()` 返回 null。

## SchedulePlugin

本地定时执行辅助插件，依赖一个已存在的 CollaborationPlugin 连接用于回报：

```java
SchedulePlugin scheduler = new SchedulePlugin(agent.getCollaborationPlugin());

scheduler.schedule("task-1", () -> doWork(), 0, 60_000);   // 固定频率；异常 → sendTaskError
scheduler.scheduleOnce("task-2", () -> doOnce(), 5_000);   // 成功 → sendTaskResult(taskId, "completed")
scheduler.cancel("task-1");
scheduler.shutdown();
```

内部为 2 线程 ScheduledExecutorService；`scheduleOnce` 完成后自动移除。平台侧定时任务的创建/管理用 `agent.schedules()`（ScheduleClient），触发后经协同连接下发到本插件。

## 独立使用（无 TaiyiAgent）

collaboration 模块不依赖核心（core）模块，可单独引入：

```xml
<dependency>
    <groupId>io.taiyiflow</groupId>
    <artifactId>taiyiflow-sdk-collaboration</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

配合 worker 模块的 `TaskExecutor` + `PlatformTaskReporter` 可获得幂等执行与平台回报能力，完整示例见 `CollaborationExample`（[examples.md](examples.md)）。