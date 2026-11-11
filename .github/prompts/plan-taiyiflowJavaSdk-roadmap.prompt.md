# Plan: TaiyiFlow Java SDK 后续实施总计划（Phase G–R）

> 合并时间：2026-11-11。本文统一承载原“协议差距与纠偏”“执行控制”“数字人形态”三份计划，是 Phase G–R 的唯一权威实施计划；Phase A–F 的完成情况见初始规划。
> 协议以后端 `../taiyiflow-dev` 代码为准，桌面行为参照 `../taiyiflow-electron`，后台认证参照慧码至一服务融合网关 `jwtauth` 插件；参考文档与代码冲突时以实际代码为准。
> 实施状态（2026-11-11）：Phase G–R 的 SDK 侧实现与本地单元验收已闭环；真实平台身份、审批、跨实例恢复及结果对账仍须按 P-IDENTITY、P-APPROVAL、P-RECOVERY、P-RESULT 联调，不得以本地测试替代平台验收。

## 一、目标与实施主线

本 SDK 的主要目标是实现 **Backend 侧数字人（Digital Worker）**：以 Java 进程作为长期在线的数字员工，具有平台预登记身份，可被平台调度接活，可持续执行、暂停、恢复、重试、重复，可请示汇报，并可断线恢复和任务补偿。

同时保留 `TaiyiAgent` 主动调用形态：多会话并行、SSE/WS 流式消息、Hook 扩展与插件化工具执行。

统一实施主线：

1. **Phase G–K：协议可用**——纠偏对话、工具、鉴权与协同协议，补齐 REST、文件、多轮和健壮性。
2. **Phase L–O：执行可控**——实现协议级 pause/resume/cancel/attach/retry/repeat 和异步审批内核。
3. **Phase P–R：数字人可持续在岗**——装配平台身份、双认证、常驻运行时、任务执行、平台记忆与结果、审批，以及单实例容器心跳检测与快速重启。

贯穿约束：一切扩展优先走 Hook；同会话控制串行、跨会话并行；**记忆与一切运行中结果均在服务端，本地仅保存最小执行状态与临时工作文件**。

## 二、现状差距与优先级

| 维度 | SDK 现状 | 后端/前端实际或目标 | 优先级 |
|------|---------|--------------------|--------|
| 对话通道选择 | WS 为主、SSE 仅手动回退，无协商 | SSE/WS 均可用；默认由 `/config.stream_protocol` 决定，调用者可覆盖 | P0 |
| 流式消息解析 | 读 `data.text`/`data.content` | 正文在 `data.chunk` | P0 |
| 工具调用下发 | 假设 `custom` 事件 | 实际为 `client_tool`/`mcp_tool`/`built_in` | P0 |
| 工具结果回传 | WS 流内 `sendToolResult` | 首帧后服务端不再读 WS；走 REST `POST /agents/client/call/result` | P0 |
| WS 鉴权 | `Authorization: Bearer` 头 | 前端用 query `__TOKEN__`；后端身份读网关注入的 `X-Credential-*` | P0 |
| 事件常量 | 5 种 | 实际 11 种，另含 bytes/tool_call/file_written/client_tool/mcp_tool/built_in | P0 |
| 关闭码语义 | 4002=Flow 不存在等 | 4000 路径错误；4002 首帧超时/无效；4003 权限/配额/模型 Key；4004 Flow 不可用；4009 会话冲突 | P0 |
| 首帧模型 | input_value/session_id/tweaks | `SimplifiedAPIRequest` 还含 run_id/message_id/files/request_type/available_tools/client_mcp_servers 等 | P1 |
| 协同 WS 握手 | 发 register 首帧 | client-id/device-id 至少一个；primary_cli 必须 instance_id；成功收 `connection.ready` | P1 |
| 权限审批 | ASK 直接当 DENY | auto/confirm/readonly；confirm 异步询问；数字人默认平台审批 | P1 |
| 协同插件接线 | `enableCollaboration()` 仅打日志 | 应真正启动/停止 CollaborationPlugin | P1 |
| 工具结果回传可靠性 | 无重试 | 前端默认重试 3 次；数字人结果由服务端权威保存 | P1 |
| REST 业务封装 | 裸 get/post/delete | sessions/messages/session-groups/skills/memories/schedule/files/mcp/config | P2 |
| 文件上传/下载 | 无 | `POST /agents/upload/{flow}`、`GET /agents/download/...` | P2 |
| 多轮对话 | 一个 session 只能 chat 一次 | 同 session_id 支持多轮 | P2 |
| MCP 本地工具 | 无 | `c_{server}_{tool}` handler，支持 Stdio/HTTP/SSE | P2 |
| 执行控制 | 仅本地 interrupt | 后端 freeze/checkpoint/resume，支持 attach/retry/repeat | P1 |
| 数字人身份与常驻 | 无 | 平台预登记 workerId，双认证，7x24 长连、状态回报、优雅启停 | P1 |
| 平台记忆与结果 | 无 | MemoryClient、任务/工具结果、产物、检查点和审批回执均由平台保存 | P1 |
| 数字人故障恢复 | 无 | 单实例容器部署，由容器平台心跳检测并快速拉起；重启后以平台任务、检查点和结果对账 | P1 |

## 三、协议纠偏与基础能力（Phase G–K）

### 3.1 对话通道协商

- 对话支持 SSE `POST /run/{flow_id}/stream` 与 WS `/run/{flow_id}/websocket`；默认通道由 `/config` 的 `stream_protocol` 决定，调用者可覆盖。
  证据：`taiyiflow-dev/src/backend/base/taiyiflow/api/v1/endpoints.py:1386-1478`；`taiyiflow-electron/src/stores/assits/assits-chat-core.js:635-724`。
- `TaiyiAgent.Builder` 新增 `streamProtocol(AUTO|SSE|WEBSOCKET)`，默认 AUTO。
- AUTO 首次创建会话前读取并缓存 `/config.stream_protocol`；读取失败回退 WS 并记录 warn；显式 SSE/WEBSOCKET 跳过协商。
- `AgentSession.chat()` 按解析通道选择 `SseStreamClient` 或 `StreamClient`，统一事件回调口径；会话级覆盖可作为后续增强。

### 3.2 消息、事件与关闭码

- 正文在 `data.chunk`；另有 `message/stage/id/name/result/session_name/retry` 等载荷；error 固定 `{error,type,persisted}`；心跳为 15 秒无数据时发送。
  证据：`taiyiflow-dev/src/backend/base/taiyiflow/utils/response.py:159-166,205-213`、`processing/process.py:680-704,1294-1306,2950-2966`。
- 前端 heartbeat 忽略；message/chunk/search 更新正文；custom 覆盖上下文压缩/echarts/file_written；tool_call 展示状态。
  证据：`taiyiflow-electron/src/stores/assits/assits-ws-lifecycle.js:473-582`、`assits-sse-handler.js:291-407`。
- SDK 修改：`StreamEvent.getMessageText` 改读 chunk；`EventConstants` 补齐 11 种事件；增加 `isClientTool/isMcpTool/isBuiltIn/isToolCall/isFileWritten/isBytes`；error 读取 `data.error`。
- 关闭码对齐：1000 完成；4000 路径错误；4001 认证；4002 首帧超时/无效；4003 权限/配额/模型 Key；4004 Flow 不可用；4009 会话冲突。
  证据：`taiyiflow-dev/src/backend/base/taiyiflow/api/v1/endpoints.py:751-786,852-966`。

### 3.3 工具执行闭环

- 工具调用经流内事件下发，载荷为 `flow_id/context{session_id,run_id,message_id}/server/function{id,name,arguments}` + `event_type`，事件为 `client_tool`/`mcp_tool`/`built_in`。
  证据：`taiyiflow-dev/src/backend/base/taiyiflow/services/mcp_tool/service.py:653-669,743-767,967-991`。
- 结果回传走 REST `POST /api/v1/agents/client/call/result`；前端执行后回传并重试 3 次。
  证据：`taiyiflow-dev/src/backend/base/taiyiflow/api/v1/agent_client.py:1152-1249`；`taiyiflow-electron/src/stores/assits/assits-tool-handle.js:287-435`、`src/api/client_call.js:3-12`。
- SDK 修改：废弃 `StreamHandle.sendToolResult`；`AgentSessionImpl.handleCustomEvent` 改为处理三类工具事件；`parseToolCall` 读取 function/context；结果通过 RestClient 回传，失败指数退避重试 3 次，最终失败由 `StreamHook.onError` 上报。
- PermissionHook 改为 `CompletableFuture<Decision>`，内置 auto/confirm/readonly；ASK 挂起工具执行，默认超时时 DENY。平台审批与本地回调的统一模型见第 5.6 节。
  前端参考：`taiyiflow-electron/src-electron/agent-runtime/index.js:381-427,503-577`。

### 3.4 基础鉴权与首帧

- 后端声明 `x-api-key`，实际身份读 `X-Credential-Username/X-Credential-Userid/X-User-Scope`（网关注入），不直接读 Authorization。
  证据：`taiyiflow-dev/src/backend/base/taiyiflow/services/auth/utils.py:34-53,169-183`。
- 前端 REST 使用 `Authorization: Bearer {token}`；两类 WS 使用 query `__TOKEN__`；MCP 使用 `X-API-Key`。
  证据：`taiyiflow-electron/src/utils/request.js:140-150`、`assits-ws-lifecycle.js:55-69`、`src/utils/mcpClient.js:459-462`。
- SDK REST 保留 Bearer；StreamClient/CollaborationPlugin 的 WS URL 追加 `__TOKEN__`；Builder 预留可配置 credential headers，适配无网关直连。
- 首帧补齐 `run_id/message_id/files/request_type/available_tools/client_mcp_servers`，执行控制字段见第 4.2 节。
  证据：`taiyiflow-dev/src/backend/base/taiyiflow/api/v1/schemas.py:1558-1785`。

### 3.5 协同插件

- 握手通过 Header/Query 传 client-id/device-id（至少一个），primary_cli 必须 instance_id；收 `connection.ready` 后才发 `tasks.fetch_recent`；删除 register 首帧；ping 由客户端发起，presence TTL 120s。
  证据：`taiyiflow-dev/src/backend/base/taiyiflow/services/client/service.py:1240-1329,2212-2256,531-545`。
- 补齐 `session.update`、`primary_task.dispatch`、`schedule_task.dispatch`；定时载荷含 schedule_id/run_record_id/prompt/request_mode，回报带 `payload.task_type="schedule"`；`tunnel.*` 可暂缓。
  证据：`services/client/service.py:59-89,1097-1163,1748-1810`、`services/schedule/router.py:25-55`。
- `enableCollaboration()` 真正创建并 start 插件，shutdown 时停止；`tasks.fetch_recent` 补拉带 `limit=20`。
- task.dispatch 保持幂等先 ack；remote_task、dialogue_subtask、简单工具统一由 TaskHandler 路由，不照搬前端 UI。

### 3.6 REST、文件与体验能力

- SessionClient：sessions/messages 查询、创建、删除、批量删除、重命名。
  证据：`agent_chat.py:201-305,362-463`。
- SkillClient：技能列表、上传、卸载、安装记录。
  证据：`agent_skill.py:466-848`。
- MemoryClient：`GET/POST /agents/memories`、forget/restore。
  证据：`agent_memory.py:169-423`。
- ScheduleClient：schedule CRUD/启停/runs；ConfigClient：`GET /config`；McpClient：`/agents/mcp/servers` CRUD。
  证据：`taiyiflow-electron/src/stores/schedule.js:38-197`；`endpoints.py:1386-1478`；`agent_mcp.py:256-344,458-613`。
- FileClient：multipart file+session_id 上传与下载，首帧 files 联动。
  证据：`agent_file.py:73-168,172-281`。
- AgentSession 支持同 session_id 多轮 chat；MCP 提供 `c_{server}_{tool}` 注册入口，先支持 Streamable HTTP，Stdio/SSE 后续。
- SSE 读取响应头 `X-TaiyiFlow-Session-id`；语音 dialogue 维持“不做”边界，仅记录 `WS /agents/dialogue/websocket`，未来可扩展 DialoguePlugin。
- 健壮性：每会话事件队列与背压策略（优先丢弃 heartbeat）、`await(Duration)`、可选 Flow 适配器、SSE 降级、starter 自动收集 Hook Bean。

## 四、执行控制（Phase L–O）

### 4.1 后端控制协议

| 端点 | 方法 | 说明 | 证据 |
|------|------|------|------|
| `/run/sessions/{session_id}/pause` | POST(202) | freeze 生成检查点，级联终止子智能体 | `api/v1/endpoints.py:426-471` |
| `/run/sessions/{session_id}/cancel` | POST(202) | 终止智能体循环 | `api/v1/endpoints.py:474-499` |
| `/run/checkpoints/resumable` | GET | 发现可恢复检查点；client_id 必填，可带 device_id/flow_id/session_id | `api/v1/endpoints.py:396-423` |
| `/run/tasks/{task_id}/cancel` | POST(202) | 取消异步执行任务 | `api/v1/endpoints.py:351-383` |

- 请求 `AgentLoopControlRequest{run_id?,request_id?,client_id?,device_id?}`；request_id 是幂等控制标识。
- 响应 `AgentLoopControlResponse{session_id,run_id,status,request_id,checkpoint_id?,subtask_cancel_requested/pending/failed}`。
  证据：`api/v1/schemas.py:1599-1614`。
- 仅原发起终端可暂停（origin_client_id/origin_device_id）；run_id 与当前执行/检查点不一致返回 409；可暂停状态为 `running/agent_loop_running/pause_requested/network_freezing`。
  证据：`services/agent_loop/control.py:28,75-105`。

### 4.2 首帧执行控制字段

| 字段 | 取值/语义 |
|------|----------|
| `execution_mode` | start 新执行 / attach 接入观察 / resume 恢复冻结执行；缺省为老客户端 |
| `run_id` | 执行 ID；resume/attach 时指定 |
| `checkpoint_id` + `checkpoint_version` | 恢复检查点与版本，防止旧通知覆盖新控制状态 |
| `after_event_id` | 已消费的最后一个事件 ID，用于续传不重复 |
| `execution_attempt_id` | 接入时校验执行尝试身份，服务端发起/恢复时覆盖 |
| `subscription_id` | 幂等接入订阅 ID |
| `tool_results` | 恢复时提交的等待中工具调用最终结果 |
| `submission_id` | 工具结果提交幂等 ID |
| `request_type` | ask/agent/plan，对话类型而非执行控制 |
| `parent_id` | 父 run_id，标记子智能体会话 |

证据：`api/v1/schemas.py:1689-1728`。

### 4.3 断连与检查点

- `freeze_on_disconnect`：start/resume 模式异常断连时服务端自动 freeze；attach 断连不影响执行。`preserve_producer` 保留生产者，观察者离开不终止执行。
  证据：`api/v1/endpoints.py:867-868,1102`。
- 检查点模型：`AgentLoopResumableCheckpointResponse{id,flow_id,session_id,run_id,origin_client_id,origin_device_id,status,version,resume_stage,pause_reason,requires_user_resume,waiting_call_ids,pending_call_ids,uncertain_call_ids,updated_at,expires_at}`。
  证据：`api/v1/schemas.py:1617-1635`。
- 检查点会过期；waiting/pending/uncertain_call_ids 标记工具在途状态，waiting 调用恢复时需提交 tool_results。
- **服务端实例断开不等于原执行一定丢失**：可能只是客户端到某一实例的连接中断，也可能伴随实例重启、负载均衡切换或执行状态失效。客户端不得仅根据一次 WS close 就直接重发任务，应先重连并查询平台执行状态。
- 若 session/run/checkpoint 由平台共享存储承载，新连接可落到任意健康实例；若状态仅在原实例内存中，则必须由平台提供粘性路由、状态迁移或故障接管，否则客户端无法可靠 attach/resume，只能在明确确认原执行不可恢复后重新发起。

桌面行为参照：

| 能力 | 桌面端实现 | 证据 |
|------|-----------|------|
| 暂停/恢复 | POST pause；查询 checkpoint 后以 resume+runId+checkpointId/version 开新流 | `pages/home/Index.vue:2450-2540`、`stores/assits/agent-loop-recovery.js:74-140` |
| 停止 | abort+WS 1000；远程/暂停任务 POST cancel | `assits-chat-core.js:1127-1223` |
| 重试 | 未入库重发原文；已入库发“请继续完成上面的任务”；不复用 messageId | `pages/home/Index.vue:4528-4569,4695-4713` |
| 断线恢复 | 记录恢复意图，online 后查检查点并退避 resume；attach 远程 observer | `agent-loop-recovery.js:161-197,205-257` |
| 审批 | Promise+pending Map，默认 300s，等待不占并发槽 | `agent-runtime/index.js:273-307,388-435` |
| 多会话 | 同会话串行、跨会话并行，切换不 abort 旧会话 | `session-state.js:36-173` |
| 定时重复 | once/interval/cron → schedule_task.dispatch → ack/执行/HTTP 回报 | `client-collab.js:2092-2119,2181-2196` |

### 4.4 会话 API 与状态机

```java
AgentSession session = agent.newSession();
session.chat("帮我分析这个目录"); // execution_mode=start
session.pause();                  // POST pause，自动生成幂等 request_id
session.resume();                 // resume + runId + checkpointId/version + toolResults
session.cancel();                 // POST cancel，终态不可恢复
session.interrupt();              // 本地 abort + WS 1000，仅断开观察
session.attach();                 // attach + afterEventId
session.retry();                  // 按是否已入库选择重试输入
session.repeat();                 // 相同输入，新 run_id、新 start

SessionState state = session.getState();
Optional<RunContext> run = session.getRunContext();
```

```
IDLE → CONNECTING → STREAMING ⇄ TOOL_CALLING → DONE
                     │  │  │
        pause() ─────┘  │  └──── cancel() → CANCELLED（终态）
                     ▼  │
        PAUSING → PAUSED ──resume()──→ RESUMING → STREAMING
                     │                     │失败/过期 → ERROR
        断连(start/resume) → DISCONNECTED
                     │──attach()/resume()──→ STREAMING
                     │──确认原执行不可恢复──→ RESTARTING ──new start──→ STREAMING
                     └────超过恢复预算或状态不确定──→ ERROR
```

新增 `PAUSING/PAUSED/RESUMING/CANCELLED/DISCONNECTED/RESTARTING`，保留 INTERRUPTED 的本地中止语义；chat/pause/resume/cancel 与自动恢复在同会话内互斥，跨会话并行不变。RESTARTING 只允许由自动恢复或显式重试进入，不得与 resume/attach 并发启动两个执行。

### 4.5 RunContext 与 ExecutionController

```java
class RunContext {
    String runId;
    String checkpointId;
    Integer checkpointVersion;
    String afterEventId;
    String executionAttemptId;
    String lastInputValue;
    Map<String, JsonNode> lastTweaks;
    List<String> lastInputFiles;
    String requestType;
    long recoveryGeneration;
    boolean persisted;
}

class ExecutionController {
    AgentLoopControlResult pause(String sessionId, String runId);
    AgentLoopControlResult cancel(String sessionId, String runId);
    List<ResumableCheckpoint> listResumable(String flowId, String sessionId);
}
```

- 从 pause 响应、resumable 查询和流事件维护 RunContext；进程内保留可重发首帧模板（input/tweaks/files/request_type），供确认执行丢失后的自动重建使用。
- 每次 attach/resume/restart 递增 recoveryGeneration，异步响应仅可推进同代次状态，防止迟到的旧流、旧检查点查询或旧控制响应覆盖新执行。
- Builder 新增 `clientId(...)/deviceId(...)`；缺省生成并持久化随机 clientId，供 pause/resumable 与协同握手复用。
- 控制模型包括 `AgentLoopControlResult` 与 `ResumableCheckpoint`，字段与后端 schemas 对照。

### 4.6 恢复、工具结果与重复执行

```java
.recoveryPolicy(RecoveryPolicy.MANUAL)      // 默认：通知调用者自行恢复
.recoveryPolicy(RecoveryPolicy.AUTO)        // 自动 attach → resume → 必要时重新发起
.recoveryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(60))
.recoveryMaxAttempts(8)
.autoRestartOnExecutionLoss(true)           // AUTO 下默认允许；false 时仅 attach/resume
```

AUTO 按以下顺序恢复，整个流程持有会话级恢复锁，并以 recoveryGeneration 防止旧响应覆盖新状态：

1. **识别断连**：异常 WS close、SSE 中断、连接超时或健康实例切换（非 cancel/非 1000）→ 关闭旧流并进入 DISCONNECTED，记录恢复意图；正常完成、主动 cancel/interrupt 不触发自动恢复。
2. **退避重连**：使用带抖动的指数退避；重连时重新读取 `/config` 以适配实例切换后的 stream_protocol，401 则先按 WorkerAuth 刷新凭证。认证拒绝、权限/配额、Flow 不可用等明确不可恢复错误直接 ERROR，不盲目重建。
3. **优先接入原执行**：查询 session/run 状态；原 run 仍活跃时使用 attach + after_event_id 续传，存在有效检查点时使用 resume + run_id + checkpoint_id/version + waiting 工具结果；原 run 已成功或已有最终结果时只补拉结果，不重新执行。仅在平台明确确认原 run 失败/丢失/不存在且任务未完成，或检查点过期且执行确认不可恢复时，才进入自动重建判断。
4. **自动重新发起**：autoRestartOnExecutionLoss=true、执行状态明确、任务未完成且非用户主动取消时进入 RESTARTING。原会话仍存在则**复用 session_id，以新 run_id、message_id、subscription_id 和 execution_mode=start 重发首帧模板**；原会话已不存在或失效时才创建新 session_id，并向调用方上报新旧 sessionId/runId 对应关系。
5. **不确定状态禁止重发**：平台暂不可达、原 run 状态未知、存在 pending/uncertain 工具调用或副作用无法判重时，不得直接 start；继续退避对账，超过恢复预算后进入 ERROR 并保留人工 attach/resume/retry 入口。
6. **恢复完成**：收到新流有效事件后清除恢复意图并重置退避；数字人任务以原 taskId 关联新 runId，依赖平台任务幂等防止同一任务被多个执行重复回报。
- 工具结果先通过 call/result 写入服务端；回传失败可在**当前进程内存**暂存并重报，或随 resume 的 tool_results 提交。**不再设计 `toolResultStore(Path)` 或本地权威工具结果库**。
- 崩溃后只能以平台已接收结果、检查点和服务端任务状态对账；无法确认的工具调用按 uncertain/失败补偿处理，不得为重建结果盲目重放副作用工具。若现有平台接口不足以查询已接收结果，列入 P-RESULT 后端补齐。
- `retry()`：未入库重发原始输入，已入库发“请继续完成上面的任务”；`repeat()`：使用 lastInputValue/lastTweaks 以新 run_id start，PAUSED 时先 cancel。自动 RESTARTING 属于同一逻辑任务的故障恢复，不等同于用户主动 repeat，不新增业务任务。
- 定时重复由 schedule_task.dispatch 按 once/interval/cron 触发 TaskHandler，并关联 schedule_id/run_record_id 回报。

## 五、数字人形态（Phase P–R）

### 5.1 数字人抽象

一个数字人 = **平台身份 + 在岗 + 接活 + 干活 + 汇报 + 请示 + 平台记忆 + 自愈**。

```java
DigitalWorker worker = DigitalWorker.builder()
    .baseUrl(...)
    .auth(WorkerAuth.apiKey(...)) // 也可 jwt(...) 或 persistentToken(..., deviceId)
    .identity(WorkerIdentity.of("worker-001", "数据分析师"))
    .role(WorkerRole.COLLABORATION_WORKER)
    .abilities(Ability.shell(), Ability.files(), Ability.search())
    .workspace(Path.of("/var/lib/digital-worker"))
    .concurrency(3)
    .recoveryPolicy(RecoveryPolicy.AUTO)
    .permissionPolicy(PermissionPolicy.CONFIRM)
    .approvalChannel(ApprovalChannel.PLATFORM) // 默认平台，可选 LOCAL_CALLBACK
    .onTask(new ReportTaskHandler())
    .build();

worker.start(); // 认证→登记校验→建连→能力核对→补拉任务→接活
worker.await(); // 阻塞在岗
worker.stop();  // 停止接新活→drain→离线通知→关连接
```

新增 `taiyiflow-sdk-worker` 模块；数字人默认启用协同 WS，`TaiyiAgent` 主动调用形态保留，两者共享 core。

### 5.2 平台身份与双认证

- workerId 必须由平台预先登记，并与认证主体、角色和允许的能力绑定；SDK 不提供首次握手自动注册，不得以客户端自报 workerId 替代平台身份。
- 启动时通过平台登记信息或握手响应校验“认证主体—workerId—角色—能力”；未登记、未绑定或越权声明能力时拒绝上岗。能力上报只用于协商，不扩大授权。
- PRIMARY_CLI 的 instance_id 由 worker 生成并持久化在 workspace；它不是 workerId，仅用于标识当前客户端实例。

**后台 API Key/JWT：**

- WorkerAuth 统一向 REST/SSE/WS/协同提供凭证；REST/SSE 用 Bearer，WS 按协议用 `__TOKEN__`。
- 慧码至一服务融合网关 jwtauth 支持 query/Cookie/Header 提取；Java 后台默认只用 Authorization Header 和 WS query，不引入浏览器 Cookie 登录态。
- 普通 JWT 以 key claim（默认 iss）定位平台凭证，由网关校验算法、签名、exp/nbf 和最大有效期；`sk-` SECRET 令牌解密出 key/secret 并与登记凭证比对。consumer/key 由平台管理，SDK 不自行签发。
  证据：慧码至一服务融合网关 `kong/plugins/jwtauth/handler.lua:32-67,161-225,228-313`、`schema.lua:11-41`。

**用户持久化登录令牌：**

- 输入为平台已签发的 `persistentToken + deviceId`；SDK 不实现密码、扫码或验证码 UI，也不把 persistentToken 直接当业务 accessToken。
- 换牌协议：`GET /xeai/sso/salt` 获取 ticket/value → 计算 `Base64(SHA-256(deviceId + value))` → `POST /xeai/sso` 提交 persistent_token/salt_ticket/sign/alg=SHA-256 → 获得 accessToken，并按响应轮换持久令牌。
  证据：`taiyiflow-electron/src/api/auth.js:244-303`。
- 业务请求只使用 accessToken；401 时 single-flight 换牌并重试一次。明确无效时清除内存凭证并停止上岗，网络错误时保留凭证并退避。
  证据：`src/stores/auth.js:465-568`、`src/utils/request.js:32-100,140-165`。
- 持久令牌优先由环境变量、容器 Secret 或外部 Secret Provider 提供；如落盘，必须放独立加密凭证存储并限制权限，不得写入普通 workspace。Electron 参照实现使用 AES-256-GCM、0600 原子写入。
  证据：`src-electron/utils/config-manager.js:126-143,243-248,278-340`。

### 5.3 TaskExecutor 与状态回报

- 接活：task.dispatch → taskId 幂等校验 → 先 ack → 入队；平台任务记录是去重与恢复权威，本地去重状态仅作辅助。
- 路由：remote_task 开 AgentSession；简单工具走 ToolDispatcher；schedule_task 走 SchedulePlugin；dialogue_subtask 使用独立会话。
- 执行：默认并发 3，可配超时；执行中 worker 置 BUSY。
- 回报：running/success/failed、进度、文本、工具结果、错误和产物元数据均写平台；只有平台确认接收后才将本地执行状态标记为已回报。
- 暂停/恢复复用第 4 节协议；worker 暂停 = 停止接新活 + 在途任务 pause。
- 服务端实例断开时，WorkerRuntime 先重建协同 WS，收 connection.ready 后以 tasks.fetch_recent 补拉并对账；对在途对话按第 4.6 节执行 attach→resume→自动重建。确认原执行丢失后，以原 taskId、新 runId 重新发起会话，TaskExecutor 不为同一 taskId 并发启动多个恢复流程。

### 5.4 在线、单实例与容器恢复

- 状态机：`ONLINE ⇄ BUSY ⇄ PAUSED → OFFLINE`；心跳 30s，处于 presence TTL 120s 内。
- 只支持单实例：`connection.kicked` 后 OFFLINE 并退出，避免互踢循环；kicked 不能单独证明任务不重复，重启后仍须与平台任务状态对账。
- 同一 workerId 只运行一个容器副本，不引入多实例协调或选举协议。容器平台负责心跳检测、故障摘除和快速拉起，SDK 负责优雅停机、断线重连与启动后的安全恢复。
- 容器重启后以平台任务记录、run/checkpoint 和已接收结果为权威，先补拉任务并对账；状态未知或副作用不确定时不得重放。需要避免重启期间重复派单或重复副作用的服务端幂等能力归入 P-RECOVERY/P-RESULT，不另设高可用协议。
- 优雅停机：shutdown hook → drain（默认 30s）→ 在途任务尽量 pause 而非硬杀。

### 5.5 平台记忆、服务端结果与工作区

- 全部记忆走平台 MemoryClient；长期记忆、跨会话上下文和任务历史按用户/worker 身份保存，不实现本地长期记忆副本。
- 对话事件、run/checkpoint、任务状态与结果、工具结果、错误、产物及元数据、审批请求与回执均以服务端为准；本地生成的文件产物及时上传平台。
- workspace 只保存 instance_id、任务/run/session/checkpoint 标识、事件游标、最小去重标记、待对账控制状态及插件临时工作文件；不得持久化业务结果缓存、工具结果缓存、完整聊天内容或长期任务历史。
- 本地状态仅是恢复提示。重启后以平台任务、检查点、记忆和结果对账，执行 `tasks.fetch_recent`、resumable 查询，再按策略 attach/resume/retry；不得用陈旧本地状态覆盖服务端。

### 5.6 请示与人工审批

- 同时支持 `PLATFORM` 与 `LOCAL_CALLBACK`，**默认 PLATFORM**。
- PLATFORM：协同事件上报审批请求，平台持久化并人工处理；SDK 等待回执，按 approvalId/taskId/runId 关联恢复。
- LOCAL_CALLBACK：业务方通过异步 Hook 接本地 UI 或审批系统；决策仍须回报平台形成审计记录。
- Phase H/N 先实现异步 PermissionHook、pending Map、approve/deny 和本地回调；Phase Q 接入平台默认通道。
- 默认超时 300s，可配 10–6000s 或 0=永不；超时/拒绝作为工具失败结果回传模型，审批等待不占工具并发槽。
- 回执幂等，断线后从平台查询/对账；不得仅凭本地“等待审批”状态重放已批准的副作用操作。

### 5.7 安全、部署与可观测

- 权限 = 平台登记身份与能力 + 工具白名单 + workspace 沙盒 + PermissionPolicy（AUTO/CONFIRM/READONLY）。
- 凭证由 WorkerAuth/Secret Provider 管理，与执行状态隔离；配置、日志、异常和 Hook 事件默认脱敏。
- `DigitalWorkerMain` 支持 worker.yml/环境变量启动独立进程和容器；starter 提供 DigitalWorker 自动装配。
- WorkerHook 暴露接活/完成/失败/在途/重连/换牌/审批等待指标，不引入 metrics 依赖。
- 日志以 taskId/runId/sessionId/instanceId 贯穿 MDC，不输出令牌。

## 六、平台配套工作项

| 工作项 | 是否必须后台支持 | 说明 |
|--------|----------------|------|
| **P-IDENTITY：身份预登记与绑定校验** | **是** | 平台登记 workerId、认证主体、角色、能力，提供管理侧登记流程与运行时校验；SDK 不自动注册。 |
| **P-AUTH：持久令牌换牌** | **是，复用现有认证服务** | 复用 `/xeai/sso/salt`、`/xeai/sso` 与令牌轮换/失效语义。 |
| **P-RECOVERY：跨实例会话与执行恢复** | **是，阻塞可靠自动重建** | session/run/checkpoint/任务状态须跨实例共享，或提供粘性路由与故障接管；控制端点应区分活跃、终态、不存在、过期和状态暂不确定，并以 taskId/runId 幂等防止旧执行与新执行重复产生副作用。 |
| **P-APPROVAL：平台审批闭环** | **是，默认通道依赖** | 提供审批上报、人工处理、持久化回执及重连查询/对账；本地回调可独立使用。 |
| **P-RESULT：记忆与运行结果服务端化** | **是** | 承载 MemoryClient、任务/运行状态、工具结果、产物和审批记录；缺失的查询/对账接口需后端补齐，SDK 不用本地结果库替代。 |

## 七、统一实施计划

| 阶段 | 内容 | 依赖 | 验收 |
|------|------|------|------|
| **Phase G：协议纠偏** | CloseCodes；EventConstants；chunk/error；WS query token+credential headers；首帧基础字段；AUTO/SSE/WEBSOCKET 通道协商 | 无 | 协议对照单测全绿；AUTO 按服务端选通道，显式设置优先 |
| **Phase H：工具执行闭环** | 三类工具事件；function/context 解析；REST call/result+3 次重试；PermissionHook 异步化与 auto/confirm/readonly | G | 跑通调用→工具执行→结果回传→最终回复；takeover/deny 回归 |
| **Phase I：协同插件对齐** | client-id/device-id+connection.ready；schedule_task 与 task_type=schedule；enableCollaboration 接线；fetch_recent limit=20 | G/H | 两种角色握手与任务回报；未启用无被动连接；定时回报格式一致 |
| **Phase J：REST 能力面与文件** | Session/Skill/Memory/Schedule/Config/Mcp Client；FileClient；多轮；MCP 注册入口 | G/H，I 完成后可并行 | Client 契约测试；同 session 两轮 chat；multipart 上传联调 |
| **Phase K：健壮性与体验** | 背压、await 超时、Flow 适配器、SSE 降级、starter Hook 装配 | J | 多会话并行回归；starter 集成测试 |
| **Phase L：控制面与身份** | RunContext；ExecutionController；clientId/deviceId；PAUSING/PAUSED/CANCELLED；会话串行锁 | G | 控制模型字段对照；pause→PAUSED；幂等 request_id |
| **Phase M：恢复、重接与自动重建** | resume checkpoint/version+tool_results；attach after_event_id；DISCONNECTED/RESTARTING；AUTO/MANUAL、退避与 recoveryGeneration；实例切换后 config 刷新；确认执行丢失后同 session 新 run 或新 session 自动 start | L+H；跨实例自动重建需 P-RECOVERY | 暂停→恢复联调；断网自动恢复；kill 服务端实例后按 attach→resume→重建恢复；状态不明不重发；waiting_call_ids 结果提交 |
| **Phase N：重试/重复与审批内核** | retry/repeat；pending Map+approve/deny+300s；内存瞬态补偿重报；不接本地结果磁盘库 | L+H | retry/repeat 单测；审批超时失败回传；补偿重报联调 |
| **Phase O：执行控制示例与文档** | PauseResume/RetryRepeat/审批示例；README；starter recoveryPolicy/permissionTimeout | M/N | 示例演示暂停→恢复→重试完整操作 |
| **Phase P：身份、认证与常驻运行时** | worker 模块；登记校验；双 WorkerAuth；salt 换牌/轮换/single-flight；独立凭证存储；长连/心跳/重连；instance_id；状态机；start/stop/drain | I + P-IDENTITY | 双认证可启动已登记 worker；未绑定被拒绝；令牌不落 workspace；重启 instance_id 不变；kicked 退出 |
| **Phase Q：任务执行、平台结果与恢复** | TaskExecutor；MemoryClient；结果/产物/审批服务端化；最小 workspace；崩溃与实例断开对账；原 taskId 新 runId 自动重建；平台审批+本地回调 | P+H+J+M；自动重建需 P-RECOVERY | 派单→执行→平台结果全链路；无本地权威结果；kill 后对账不丢不重；服务端实例切换后任务不并发重跑；审批恢复 |
| **Phase R：数字人产品化** | DigitalWorkerMain+worker.yml；Secret Provider；WorkerHook；README/Dockerfile；starter 自动装配；单实例容器心跳与重启手册 | Q | 配置化容器启动；指标可读；审批联调；文档明确单实例部署与重启对账约束 |

依赖说明：G/H 是最高优先级；I 在 G/H 后推进，J/K 随后；L 可在 G 后开始，M/N 需要 H；I 与 L–O 可并行；P 依赖 I 和平台身份能力，Q 依赖 P/H/J/M，R 收尾。跨实例自动重建联调以 P-RECOVERY 就绪为前提，不做客户端伪实现。

## 八、验证策略

1. **协议对照单测**：11 种事件、关闭码、chunk/error、首帧字段、execution_mode 三值与后端字符串逐一比对。
2. **工具契约**：call/result 请求体与 `agent_client.py:1152-1249` 一致；重试 3 次后上报错误；takeover/deny 语义回归。
3. **通道协商**：AUTO 读取并缓存服务端协议、失败回退 WS、显式配置优先；SSE/WS 事件口径一致。
4. **执行状态机**：合法/非法迁移全覆盖；DONE 后 pause 报错、CANCELLED 不可 resume、PAUSED 可 cancel；同 request_id 重复 pause 无副作用。
5. **恢复联调**：chat→pause→resumable→resume；执行中断网 AUTO 恢复；停止服务端实例并切换到健康实例，先验证 attach/resume，再在明确不可恢复后验证同 session 新 run 或新 session 自动 start；状态未知或存在不确定副作用时不得重发；恢复锁与 recoveryGeneration 防止双执行；waiting 工具结果随 resume 提交。
6. **审批**：ASK→approve 继续；deny/超时作为工具失败回传；等待不占并发槽；平台回执断线对账且幂等。
7. **协同**：primary_cli/collaboration_worker 握手、connection.ready、schedule_task.dispatch 回报；fetch_recent 带 limit=20。
8. **数字人身份**：API Key/JWT 与 persistentToken 两种认证；401 换牌去重；未登记/未绑定拒绝；凭证不进入 workspace 和日志。
9. **服务端结果与恢复**：任务、工具结果、产物、记忆和审批均可在服务端查询；本地无权威结果缓存；kill 重启后以服务端对账，副作用工具不盲目重放。
10. **单实例容器恢复**：同一 workerId 只运行一个容器副本；心跳失效后由容器平台快速拉起，启动时先执行任务与检查点对账，状态不明时不重放副作用操作。
11. **回归与真实联调**：保持既有单元测试全绿；minimal 与工具接管示例对本地 taiyiflow-dev 跑通，多会话并行和 starter 集成无回归。
