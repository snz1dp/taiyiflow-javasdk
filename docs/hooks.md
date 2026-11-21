# Hook 体系与权限审批

TaiyiAgent 的全部业务扩展收敛为 4 个钩子（Hook）服务提供接口（SPI，`io.taiyiflow.sdk.hooks` 包）。钩子可挂在构建器（Builder，全局，作用于所有会话）或会话（仅该会话）上；不挂则走内置默认行为。

## 钩子（Hook）一览

| Hook | 方法 | 默认行为 |
|------|------|---------|
| `StreamHook` | `onChunk` / `onEvent` / `onError` / `onClose` | 全部 default 空实现，消息流内部缓冲 |
| `ToolExecutionHook` | `before` / `after` / `takeover` | before/after 原样透传；takeover 返回 null（走内置插件） |
| `PermissionHook` | `evaluate` → `CompletableFuture<PermissionResult>`（兼容 `onPermission`） | 按 `PermissionGate`（默认 `READONLY` 档）决策 |
| `SessionHook` | `onStart` / `onFinish` / `onInterrupt` / `onClose` | 全部 default 空实现 |

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
    record PermissionResult(Decision decision, String reason) {}   // reason 随 DENY 回传模型

    CompletableFuture<Decision> onPermission(String sessionId, ToolCall toolCall);

    // 默认包装 onPermission（reason=null）；覆写 evaluate 可携带拒绝原因
    default CompletableFuture<PermissionResult> evaluate(String sessionId, ToolCall toolCall) {
        return onPermission(sessionId, toolCall)
            .thenApply(decision -> new PermissionResult(decision, null));
    }
}
```

分发器优先调用 `evaluate`；DENY 时把 `reason` 作为工具错误结果回传模型（缺省文案 "Permission denied by hook"），既有 lambda 钩子无需改动。

### 内置策略 PermissionPolicy

`PermissionPolicy` 是三档兼容枚举（`AUTO`/`CONFIRM`/`READONLY`），通过 `builder.permissionPolicy(...)` 配置，默认 `READONLY`。未显式配置 `permissionSettings` 时，SDK 以该档位构造默认 `PermissionGate`（见下节），行为与旧版一致：

| 策略 | 只读工具 | 写操作工具 |
|------|---------|-----------|
| `AUTO` | ALLOW | ALLOW |
| `CONFIRM` | ALLOW | ASK（进入审批等待） |
| `READONLY`（默认） | ALLOW | DENY |

### 写操作（mutating）判定规则

`PermissionPolicy.isMutating(toolName)`：

- Shell/进程：`shell`、`bash`、`execute_command`、`shell_exec`、`process_stop`、`process_cleanup`、`task_stop`、`task_delete`、`task_cleanup`。
- 文件与其他本地状态：`write_file`、`file_edit`、`todo_write`、`document_unstructured`。
- 定时任务：`schedule_create`、`schedule_update`、`schedule_delete`、`schedule_toggle`。
- 技能：`skill_create`、`skill_upload`、`skill_delete`。
- `taiyiflow-in-chrome__*` 前缀：除 `tabs_context`、`read_text`、`read_console` 外全部视为写。
- `taiyiflow-computer__*` 前缀：除 `displays_context`、`windows_context`、`screenshot` 外全部视为写。
- 其余工具名一律视为只读。

> 注意：`process_list`、`process_output`、`read_file`、`list_files`、`search`、`grep` 等属于只读；`run_subtask` 不在固定写名单中，按只读处理。

### 细粒度权限 PermissionGate

`PermissionGate`（`io.taiyiflow.sdk.core.security`）对照 Electron `security.js evaluateApproval` 移植，是默认的会话权限决策器；通过 `builder.permissionSettings(PermissionSettings)`（TaiyiAgent 与 DigitalWorker 同名方法）、`worker.yml` 的 `permission:` 块或 Starter 的 `taiyiflow.permission.*` / `taiyiflow.worker.permission.*` 配置。`PermissionSettings` 不可变，只能来自配置层——**技能正文、模型参数或工具结果不得反向修改规则**。

| 配置项 | 取值 | 默认 | 语义 |
|--------|------|------|------|
| `policy` | AUTO / CONFIRM / READONLY | READONLY | 全局档位（与旧三档兼容） |
| `rules[]` | action=ALLOW/DENY/ASK + toolName / commandPrefix | 空 | 显式规则；deny > ask > allow；两个选择器同时给出须同时命中；非法 action 归一为 ASK，空选择器规则丢弃 |
| `writeConfirmation` | CONFIRM / DIRECT | CONFIRM | `file_edit`/`write_file` 写确认：CONFIRM→ASK，DIRECT→ALLOW；deny 规则仍优先 |
| `browserPermissionMode` | ASK / SKIP_ALL / FOLLOW_A_PLAN | ASK | 浏览器/计算机类工具独立模式，独立于全局档位 |
| `categoryPolicies` | shell/file/browser/skill/schedule → AUTO/CONFIRM/DENY | 空（回落全局） | 按工具类别覆盖全局档位 |

决策顺序（与 Electron 对照）：

1. **浏览器/计算机分支优先**：规则 deny/ask → 只读后缀工具 ALLOW → READONLY 档拒绝写操作 → 显式 allow → 模式开关（SKIP_ALL→ALLOW；FOLLOW_A_PLAN→全局 CONFIRM 时 ASK 否则 ALLOW；ASK→ASK）。
2. 类别档位覆盖全局档位；READONLY 档拒绝写操作。
3. 写确认工具（`file_edit`/`write_file`）：deny 规则→DENY，DIRECT→ALLOW，否则 ASK。
4. 显式 deny → 显式 ask。
5. **opaque 命令**（静态分析无法完全解析：命令替换/变量展开/未闭合引号/heredoc/超深度嵌套）：AUTO 档仍过高危守卫（deny/allow 清单），其余档位 ASK 并附解析失败原因——显式 deny/ask 规则先于 AUTO 豁免。
6. 显式 allow → AUTO 档高危守卫 → 非写操作 ALLOW → ASK。

高危守卫清单对照 Electron `security.js`：shutdown/reboot/killall/sudo/halt/poweroff/mkfs*、`dd of=/dev/`、rm 根/家目录/含未展开变量、fork bomb、反向 shell、敏感文件读写、下载落盘、破坏性操作、eval 与 `env -S` 嵌套、深层 `shell -c` 剥离等；命令前缀规则按 `ShellCommandAnalysis` 的候选命令逐一匹配。

**与 Electron 的已记录偏差**（Java 取更严格一侧）：

- 无「未分类工具→confirm」兜底桶，统一按 `PermissionPolicy.isMutating` 判定；
- 高危守卫恒生效——Java 始终运行于沙盒/白名单内，不存在 Electron 的沙盒关闭豁免；
- 不移植 `skill_fetch` 审查策略（Java 技能供应另有安全边界，见 [skills.md](skills.md)）；
- READONLY 档对浏览器写操作直接 DENY（Electron 为 confirm）；BROWSER 类别覆盖同时作用于 computer 工具。

**OS 沙盒差异**：Electron 桌面端可调用 OS 级沙盒（macOS sandbox-exec 等）；Java SDK 不伪装支持 OS 沙盒，仅提供沙盒目录 + 命令白名单 + 高危守卫，容器隔离由部署方负责（见 [deployment.md](deployment.md)）。

```java
.permissionSettings(PermissionSettings.builder()
    .policy(PermissionPolicy.CONFIRM)
    .addRule(PermissionRule.of("deny", "shell", "rm "))
    .addRule(PermissionRule.of("allow", "read_file", null))
    .writeConfirmation(WriteConfirmation.CONFIRM)
    .browserPermissionMode(BrowserPermissionMode.ASK)
    .categoryPolicy(ToolCategory.SHELL, PermissionPolicy.CONFIRM)
    .build())
```

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
- **审批超时 ≠ DENY**：超时返回显式 timeout 错误结果（文案含工具名与等待时长，提示模型改用无需确认的方案或待用户在线时重试），不是权限拒绝；排队中的失效审批项随超时清队。
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
    default void onClose(String sessionId) {}
}
```

- `onFinish`：一轮执行正常完成；此后会话仍可用于 retry/repeat 等后续操作，不等于会话对象销毁。
- `onClose`：显式移除、同 ID 替换或 agent shutdown 导致真实会话关闭；每个会话最多触发一次，插件注册的同生命周期回调也会收到通知。
- `StreamHook.onClose` 描述当前流关闭，与 `SessionHook.onClose` 的会话生命周期语义不同。

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
PermissionHook.evaluate()          ← PermissionGate（默认）与自定义 Hook
    │ ALLOW（DENY → 带 reason 的失败结果回传；ASK → 等待 approve/deny，超时 → timeout 结果）
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