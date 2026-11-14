# Hook 体系与权限审批

TaiyiAgent 的全部业务扩展收敛为 4 个钩子（Hook）服务提供接口（SPI，`io.taiyiflow.sdk.hooks` 包）。钩子可挂在构建器（Builder，全局，作用于所有会话）或会话（仅该会话）上；不挂则走内置默认行为。

## 钩子（Hook）一览

| Hook | 方法 | 默认行为 |
|------|------|---------|
| `StreamHook` | `onChunk` / `onEvent` / `onError` / `onClose` | 全部 default 空实现，消息流内部缓冲 |
| `ToolExecutionHook` | `before` / `after` / `takeover` | before/after 原样透传；takeover 返回 null（走内置插件） |
| `PermissionHook` | `onPermission` → `CompletableFuture<Decision>` | 按 `PermissionPolicy` 决策 |
| `SessionHook` | `onStart` / `onFinish` / `onInterrupt` | 全部 default 空实现 |

## StreamHook

```java
public interface StreamHook {
    default void onChunk(String sessionId, String chunk) {}        // 增量文本（data.chunk）
    default void onEvent(String sessionId, StreamEvent event) {}   // 任意流事件（11 种）
    default void onError(String sessionId, Throwable error) {}
    default void onClose(String sessionId, String reason) {}       // 正常/异常关闭均触发
}
```

`StreamEvent` 提供 `isMessage()/isClientTool()/isMcpTool()/isBuiltIn()` 等判定方法与 `getMessageText()/getErrorMessage()` 便捷读取。事件类型全集见 [protocol.md](protocol.md)。

## ToolExecutionHook

```java
public interface ToolExecutionHook {
    // 默认原样返回；返回 null 表示取消本次执行
    default ToolCall before(String sessionId, ToolCall toolCall) { return toolCall; }

    // 默认原样返回；可改写结果
    default ToolResult after(String sessionId, ToolCall toolCall, ToolResult result) { return result; }

    // 默认 null；返回非 null 即接管执行，跳过内置插件
    default ToolResult takeover(String sessionId, ToolCall toolCall) { return null; }
}
```

接管示例（完整代码见 `ToolTakeoverExample`）：

```java
.onToolExecution(new ToolExecutionHook() {
    @Override
    public ToolResult takeover(String sessionId, ToolCall toolCall) {
        if ("get_weather".equals(toolCall.getName())) {
            ObjectNode weather = mapper.createObjectNode();
            weather.put("city", "Beijing");
            weather.put("temperature", 22);
            return ToolResult.success(toolCall.getId(), weather);
        }
        return null; // 其他工具走内置插件
    }
})
```

`ToolCall` 字段：`id`、`name`、`arguments`（JsonNode）、`type`，以及上下文 `flowId/sessionId/runId/messageId`。
`ToolResult` 工厂：`ToolResult.success(toolCallId, JsonNode)` / `ToolResult.error(toolCallId, String)`。

## PermissionHook 与 PermissionPolicy

```java
public interface PermissionHook {
    enum Decision { ALLOW, DENY, ASK }
    CompletableFuture<Decision> onPermission(String sessionId, ToolCall toolCall);
}
```

### 内置策略 PermissionPolicy

`PermissionPolicy` 是枚举且自身实现 `PermissionHook`，通过 `builder.permissionPolicy(...)` 配置，默认 `READONLY`：

| 策略 | 只读工具 | 写操作工具 |
|------|---------|-----------|
| `AUTO` | ALLOW | ALLOW |
| `CONFIRM` | ALLOW | ASK（进入审批等待） |
| `READONLY`（默认） | ALLOW | DENY |

### 写操作（mutating）判定规则

`PermissionPolicy.isMutating(toolName)`：

- 固定写工具集合：`shell`、`bash`、`execute_command`、`shell_exec`、`process_stop`、`process_cleanup`、`task_stop`、`task_delete`、`task_cleanup`、`todo_write`、`file_edit`、`document_unstructured`、`write_file`。
- `taiyiflow-in-chrome__*` 前缀：除 `tabs_context`、`read_text`、`read_console` 外全部视为写。
- `taiyiflow-computer__*` 前缀：除 `displays_context`、`windows_context`、`screenshot` 外全部视为写。
- 其余工具名一律视为只读。

> 注意：`process_list`、`process_output`、`read_file`、`list_files`、`search`、`grep` 等属于只读；`run_subtask` 不在固定写名单中，按只读处理。

### 自定义审批钩子（Hook）

```java
.onPermission((sessionId, toolCall) -> {
    PermissionHook.Decision decision = "shell".equals(toolCall.getName())
        ? PermissionHook.Decision.ASK      // 进入人工裁决
        : PermissionHook.Decision.ALLOW;
    return CompletableFuture.completedFuture(decision);
})
.permissionTimeout(Duration.ofSeconds(300))
```

## ASK 审批流程

`PermissionHook` 返回 `ASK` 时，工具调用进入会话级审批等待，由调用者裁决：

```java
boolean accepted = session.approve(toolCallId);
boolean rejected = session.deny(toolCallId, "not allowed");
```

- 批准后才执行工具；拒绝或超时形成失败结果并通过 REST 回传平台。
- 审批超时默认 300s；`permissionTimeout(Duration.ZERO)` 永不超时；其他有效值 10–6000s。
- 重复或未知 toolCallId 的回执返回 `false`。
- 未确认的工具结果仅保存在进程内存：attach 前补偿重报，resume 时随检查点提交，不写入本地权威结果库。

完整示例见 `LocalApprovalExample`（从标准输入（stdin）读取 approve/deny）。

## SessionHook

```java
public interface SessionHook {
    default void onStart(String sessionId) {}
    default void onFinish(String sessionId) {}
    default void onInterrupt(String sessionId, String reason) {}
}
```

注册方法名为 `onLifecycle`（Builder 与会话均是）：

```java
builder.onLifecycle(new SessionHook() { ... });   // 全局
session.onLifecycle(new SessionHook() { ... });   // 单会话
```

## 工具调用执行顺序

```
智能体发起工具调用（client_tool / mcp_tool / built_in 流事件）
    │
    ▼
PermissionHook.onPermission()      ← PermissionPolicy 与自定义 Hook
    │ ALLOW（DENY → 失败结果回传；ASK → 等待 approve/deny）
    ▼
ToolExecutionHook.before()         ← 返回 null 取消执行
    │
    ▼
ToolExecutionHook.takeover() ──→ 非空？──→ 使用调用者结果
    │ null                               │
    ▼                                    │
内置插件执行                              │
（ToolRuntime 调度：全局/会话并发限额、    │
  队列容量、单工具超时、取消信号）         │
    │                                    │
    ▼                                    ▼
ToolExecutionHook.after() ◄──────────────┘
    │
    ▼
结果经 REST POST /api/v1/agents/client/call/result 回传
（失败进入 Outbox 补偿，见 execution-control.md）
```

## 挂载位置与组合

- 构建器（Builder）上的钩子为全局列表，可多次调用追加；会话级钩子只影响该会话。
- Spring Boot 启动器（Starter）会把容器中的四类钩子 Bean 按 `orderedStream()` 顺序自动挂到全局。
- DigitalWorker 的审批不走会话 approve/deny，而是 `approvalHandler` + `ApprovalChannel` + `ApprovalAudit` 机制，见 [digital-worker.md](digital-worker.md)。