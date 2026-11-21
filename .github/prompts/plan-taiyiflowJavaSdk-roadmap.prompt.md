# Plan: TaiyiFlow Java SDK 后续实施总计划（Phase G–Z）

> 合并时间：2026-11-11；2026-11-13 合并运行时增强计划。本文统一承载“协议差距与纠偏”“执行控制”“数字人形态”“运行时增强”，是 Phase G–Z 的唯一权威实施计划；Phase A–F 的完成情况见初始规划，Phase AA–AG 见独立的 Electron 后台能力对齐计划。
> 协议以后端 `../taiyiflow-dev` 代码为准，桌面行为参照 `../taiyiflow-electron`，后台认证参照慧码至一服务融合网关 `jwtauth` 插件；参考文档与代码冲突时以实际代码为准。
> 实施状态（2026-11-13）：Phase G–Z 的 SDK 侧实现与本地单元验收已闭环；`WorkerRegistrar`、`.workerRegistrar(...)`、`extensions.worker-registrar`、`extensions.task-reporter` 及对应 starter 装配均已落地。第三方身份、平台审批、跨实例恢复及结果对账仍须按 P-IDENTITY、P-APPROVAL、P-RECOVERY、P-RESULT 联调，不得以本地测试替代平台验收。
> 第九部分为 Phase S–Z 的详细实施内容，仅允许保存独立状态目录中的最小瞬态回传记录，平台仍是任务、工具结果、检查点和产物权威。第十部分仅保留 Phase AA–AG 独立计划入口。

## 一、目标与实施主线

本 SDK 的主要目标是实现 **Backend 侧数字人（Digital Worker）**：以 Java 进程作为长期在线的数字员工，具有由第三方身份实现登记并校验的 workerId/角色/能力身份，可被平台调度接活，可持续执行、暂停、恢复、重试、重复，可请示汇报，并可断线恢复和任务补偿。

同时保留 `TaiyiAgent` 主动调用形态：多会话并行、SSE/WS 流式消息、Hook 扩展与插件化工具执行。

统一实施主线：

1. **Phase G–K：协议可用**——纠偏对话、工具、鉴权与协同协议，补齐 REST、文件、多轮和健壮性。
2. **Phase L–O：执行可控**——实现协议级 pause/resume/cancel/attach/retry/repeat 和异步审批内核。
3. **Phase P–R：数字人可持续在岗**——装配第三方身份校验、双认证、常驻运行时、任务执行、平台记忆与结果、审批，以及单实例容器心跳检测与快速重启。
4. **Phase S–Z：运行时增强**——统一工具调度、瞬态结果补偿、连接活性、在途任务控制，并按需补齐文件编辑、后台进程、Stdio MCP 与父子任务编排。

贯穿约束：TaiyiAgent 业务扩展优先走 Hook，DigitalWorker 身份与任务接入走 Worker SPI；同会话控制串行、跨会话并行；**记忆与一切运行中结果均在服务端，本地仅保存最小执行状态与临时工作文件**。

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
| 数字人身份与常驻 | 无 | 预留 `WorkerRegistrar`/`WorkerIdentityValidator` SPI，由第三方实现 workerId 登记及认证主体、角色、能力绑定；双认证，7x24 长连、状态回报、优雅启停 | P1 |
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
- Phase G–R 的工具结果先通过 call/result 写入服务端；回传失败在**当前进程内存**暂存并重报，或随 resume 的 tool_results 提交，**不设计本地权威工具结果库**。后续跨进程瞬态补传仅以本文第九部分 Phase T 为准。
- 崩溃后以平台已接收结果、检查点和服务端任务状态对账；启用 Phase T 后，也只可补传已持久化的最终结果，不得为重建结果盲目重放副作用工具。后端已核实 call/result 的 run_id+call_id 相同结果幂等、冲突拒绝、client_paused/client_offline，以及 resume.tool_results 的 submission_id/hash、waiting/uncertain 和 checkpoint version 语义；具体补传与死信分类以 Phase T 为准。无法确认的工具调用按 uncertain/失败补偿处理，Collaboration/Schedule 的 REPORT_UNKNOWN 必须先查询平台状态再补偿，不套用工具回调规则。
- `retry()`：未入库重发原始输入，已入库发“请继续完成上面的任务”；`repeat()`：使用 lastInputValue/lastTweaks 以新 run_id start，PAUSED 时先 cancel。自动 RESTARTING 属于同一逻辑任务的故障恢复，不等同于用户主动 repeat，不新增业务任务。
- 定时重复由 schedule_task.dispatch 按 once/interval/cron 触发 TaskHandler，并关联 schedule_id/run_record_id 回报。

## 五、数字人形态（Phase P–R）

### 5.1 数字人抽象

一个数字人 = **第三方身份 + 在岗 + 接活 + 干活 + 汇报 + 请示 + 平台记忆 + 自愈**。

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

### 5.2 第三方身份与双认证

- **workerId 不由智能体 Agent 后台登记**。workerId 及其与认证主体、角色、允许能力的绑定，由 SDK 使用方提供的第三方身份扩展负责登记、治理和校验；第三方实现可对接其 IAM、worker 注册中心或其他权威身份系统。
- 待实现的 SDK SPI 增量包括 `WorkerRegistrar` 与注册请求/结果模型，由使用方实现真实注册逻辑；SDK 不内置第三方 HTTP 注册客户端，也不提供 Agent 后台注册实现。Builder 与配置化入口分别新增 `.workerRegistrar(...)` 和 `extensions.worker-registrar`，Spring Boot starter 支持注入对应 Bean。
- `WorkerRegistrar` 为可选 SPI：已配置 workerId 时可直接进入校验；未配置 workerId 但提供 registrar 时，由 registrar 返回登记后的 `WorkerIdentity`，再继续校验和建连；两者都未提供时拒绝启动。注册请求应由第三方实现保证幂等，注册响应未知时先查询/对账，不由 SDK 盲目重复注册。
- SDK 通过必填的 `WorkerIdentityValidator` 扩展接收第三方校验实现，在建立协同连接前调用；未登记、未绑定或越权声明能力时拒绝上岗。registrar 不能绕过 validator，SDK 也不以客户端自报 workerId 替代第三方校验。
- 智能体 Agent 后台只使用协同协议中的 client/device/worker 身份与能力声明进行连接、调度和授权协商，不承担 workerId 的管理侧登记流程；能力上报只用于协商，不扩大授权。
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

### 5.3 TaskExecutor、任务 SPI 与状态回报

SDK 不为第三方任务系统封闭执行与回报入口，预留以下使用方扩展接口；接口模型由 SDK 定义，具体业务适配由使用方实现：

| SPI | 职责 | 默认行为与约束 |
|-----|------|----------------|
| `WorkerTaskHandler` | 承接 remote_task、dialogue_subtask、schedule 或自定义业务任务执行 | 通过 `.onTask(...)`/`extensions.task-handler` 注入；未提供时才使用默认 AgentSession 路由 |
| `TaskProgress` | 执行中回报进度与中间状态 | 由 SDK 传入 handler，使用方不自行伪造平台 ACK/终态 |
| `TaskReporter` | 扩展 running/success/failure 的结果回报通道 | 通过 `.taskReporter(...)` 注入；默认 `PlatformTaskReporter`，平台派发任务仍须保持平台状态权威与对账语义 |
| `WorkerHook` | 任务接收、开始、完成、失败、停止等观察扩展 | 只作生命周期观察，不替代任务执行或结果权威 |
| `PermissionHook`、`ApprovalAudit` | 第三方审批决策与审计适配 | 默认仍走平台审批；本地扩展必须保留平台审计记录 |

- 当前代码已有 `WorkerTaskHandler`、`TaskProgress`、`TaskReporter`、`WorkerHook` 及 Builder 主要注入入口；待实现项是补齐 worker.yml 与 starter 的一致装配能力，其中新增的 `task-reporter` 配置键只负责类名装配，不把第三方地址、凭证或业务参数塞入 SDK 通用配置。
- 自定义任务扩展不得绕过 taskId 幂等、并发边界、取消传播和平台对账；Phase V 的会话控制句柄属于 SDK 运行时能力，不要求使用方重新实现。
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
- workspace 只保存 instance_id、任务/run/session/checkpoint 标识、事件游标、最小去重标记、待对账控制状态及插件临时工作文件；不得持久化业务结果缓存、工具结果缓存、完整聊天内容或长期任务历史。Phase T 的瞬态 Outbox 若实施，必须位于独立状态目录且只保存待平台确认的最小回传记录，不属于 workspace 或本地权威结果。
- 本地状态仅是恢复提示。重启后以平台任务、检查点、记忆和结果对账，执行 `tasks.fetch_recent`、resumable 查询，再按策略 attach/resume/retry；不得用陈旧本地状态覆盖服务端。

### 5.6 请示与人工审批

- 同时支持 `PLATFORM` 与 `LOCAL_CALLBACK`，**默认 PLATFORM**。
- PLATFORM：协同事件上报审批请求，平台持久化并人工处理；SDK 等待回执，按 approvalId/taskId/runId 关联恢复。
- LOCAL_CALLBACK：业务方通过异步 Hook 接本地 UI 或审批系统；决策仍须回报平台形成审计记录。
- Phase H/N 先实现异步 PermissionHook、pending Map、approve/deny 和本地回调；Phase Q 接入平台默认通道。
- 默认超时 300s，可配 10–6000s 或 0=永不；超时/拒绝作为工具失败结果回传模型，审批等待不占工具并发槽。
- 回执幂等，断线后从平台查询/对账；不得仅凭本地“等待审批”状态重放已批准的副作用操作。

### 5.7 安全、部署与可观测

- 权限 = 第三方身份实现登记并校验的身份与能力 + 工具白名单 + workspace 沙盒 + PermissionPolicy（AUTO/CONFIRM/READONLY）。
- 凭证由 WorkerAuth/Secret Provider 管理，与执行状态隔离；配置、日志、异常和 Hook 事件默认脱敏。
- `DigitalWorkerMain` 支持 worker.yml/环境变量启动独立进程和容器；starter 提供 DigitalWorker 自动装配。
- WorkerHook 暴露接活/完成/失败/在途/重连/换牌/审批等待指标，不引入 metrics 依赖。
- 日志以 taskId/runId/sessionId/instanceId 贯穿 MDC，不输出令牌。

## 六、平台配套工作项

| 工作项 | 是否必须后台支持 | 说明 |
|--------|----------------|------|
| **P-IDENTITY：第三方身份注册与绑定校验 SPI** | **是，由 SDK 使用方实现，非 Agent 后台工作项** | SDK 定义 `WorkerRegistrar`、注册请求/结果模型及 `WorkerIdentityValidator`；使用方实现 workerId 注册和认证主体、角色、能力绑定校验。Builder/worker.yml/starter 均预留注入点，Agent 后台不提供 workerId 管理登记流程。 |
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
| **Phase P：身份、认证与常驻运行时** | worker 模块；`WorkerRegistrar`/`WorkerIdentityValidator` SPI 及 Builder/worker.yml/starter 注入；双 WorkerAuth；salt 换牌/轮换/single-flight；独立凭证存储；长连/心跳/重连；instance_id；状态机；start/stop/drain | I + P-IDENTITY | 使用方可实现注册与校验；已有 workerId 可直接校验，未配置时可由 registrar 返回；注册未知不盲重试；未登记/未绑定被拒绝；令牌不落 workspace；kicked 退出 |
| **Phase Q：任务执行、平台结果与恢复** | TaskExecutor；`WorkerTaskHandler`/`TaskProgress`/`TaskReporter`/`WorkerHook` 任务 SPI 与配置注入；MemoryClient；结果/产物/审批服务端化；最小 workspace；崩溃与实例断开对账；原 taskId 新 runId 自动重建；平台审批+本地回调 | P+H+J+M；自动重建需 P-RECOVERY | 使用方可扩展任务执行与回报；默认派单→执行→平台结果全链路；自定义扩展不绕过幂等/取消/对账；kill 后不盲重放；审批恢复 |
| **Phase R：数字人产品化** | DigitalWorkerMain+worker.yml；Secret Provider；WorkerHook；README/Dockerfile；starter 自动装配；单实例容器心跳与重启手册 | Q | 配置化容器启动；指标可读；审批联调；文档明确单实例部署与重启对账约束 |

依赖说明：G/H 是最高优先级；I 在 G/H 后推进，J/K 随后；L 可在 G 后开始，M/N 需要 H；I 与 L–O 可并行；P 依赖 I 和第三方身份实现，Q 依赖 P/H/J/M，R 收尾。跨实例自动重建联调以 P-RECOVERY 就绪为前提，不做客户端伪实现。

## 八、验证策略

1. **协议对照单测**：11 种事件、关闭码、chunk/error、首帧字段、execution_mode 三值与后端字符串逐一比对。
2. **工具契约**：call/result 请求体与 `agent_client.py:1152-1249` 一致；重试 3 次后上报错误；takeover/deny 语义回归。
3. **通道协商**：AUTO 读取并缓存服务端协议、失败回退 WS、显式配置优先；SSE/WS 事件口径一致。
4. **执行状态机**：合法/非法迁移全覆盖；DONE 后 pause 报错、CANCELLED 不可 resume、PAUSED 可 cancel；同 request_id 重复 pause 无副作用。
5. **恢复联调**：chat→pause→resumable→resume；执行中断网 AUTO 恢复；停止服务端实例并切换到健康实例，先验证 attach/resume，再在明确不可恢复后验证同 session 新 run 或新 session 自动 start；状态未知或存在不确定副作用时不得重发；恢复锁与 recoveryGeneration 防止双执行；waiting 工具结果随 resume 提交。
6. **审批**：ASK→approve 继续；deny/超时作为工具失败回传；等待不占并发槽；平台回执断线对账且幂等。
7. **协同**：primary_cli/collaboration_worker 握手、connection.ready、schedule_task.dispatch 回报；fetch_recent 带 limit=20。
8. **数字人身份 SPI**：API Key/JWT 与 persistentToken 两种认证；401 换牌去重；使用方可通过 `WorkerRegistrar` 完成注册并通过 `WorkerIdentityValidator` 校验；已有 workerId 时 registrar 可缺省，未配置 workerId 且未提供 registrar 时拒绝启动；注册结果未知不盲目重试；未登记/未绑定/越权时拒绝；凭证不进入 workspace 和日志；不得为 Agent 后台新增 workerId 登记接口作为验收前提。
9. **任务 SPI**：使用方可注入 `WorkerTaskHandler` 执行业务任务，通过 `TaskProgress` 回报进度、通过 `TaskReporter` 扩展终态回报，并用 `WorkerHook` 观察生命周期；未提供 handler 时默认 AgentSession 路由保持可用；自定义实现不得绕过 taskId 幂等、并发、取消和平台对账。
10. **服务端结果与恢复**：任务、工具结果、产物、记忆和审批均可在服务端查询；本地无权威结果缓存；kill 重启后以服务端对账，副作用工具不盲目重放。
11. **单实例容器恢复**：同一 workerId 只运行一个容器副本；心跳失效后由容器平台快速拉起，启动时先执行任务与检查点对账，状态不明时不重放副作用操作。
12. **回归与真实联调**：保持既有单元测试全绿；minimal 与工具接管示例对本地 taiyiflow-dev 跑通，多会话并行和 starter 集成无回归。

## 九、运行时增强实施计划（Phase S–Z）

### 9.1 目标与范围

#### 9.1.1 目标

将 Electron 主进程中已经验证的运行时机制，按 Java 后台数字人的约束转化为以下能力：

1. 工具执行有统一并发边界、排队取消和稳定执行上下文，worker 的所有 per-flow agent 真正共享同一组资源限额。
2. 工具最终结果在进程崩溃或网络中断后仍可安全补传，但不重放工具副作用。
3. SSE/WS 与协同连接可识别空闲、半开和迟到回调，避免假在线与误恢复。
4. worker 可管理排队及在途任务，接通平台 `task.cancel`，暂停、恢复、取消和停机语义一致。
5. 文件编辑具备原子性、乐观锁和结构化冲突，Shell 隐式产物可被有界审计。
6. 长命令以后台进程句柄运行，输出、取消和进程清理由 SDK 统一管理。
7. 按需增加 Stdio MCP 和父子任务编排，严格对齐后端配置与任务契约，不扩大权限或引入凭证扩散。

#### 9.1.2 不做的事

- 不迁移聊天 UI、DOM/响应式状态、页面隐藏节流或 Electron IndexedDB。
- 不新增本地权威记忆、任务历史、运行结果或工具结果查询库。
- 不用文件快照实现事务回滚，不用内存进程表实现跨进程任务恢复。
- 不在状态未知、平台不可达或副作用不确定时自动 start、重放工具、重启命令或重放非幂等 MCP 调用。
- 不通过子任务、MCP 配置或能力快照提升权限，不传递 API Key/JWT/accessToken/persistentToken。
- 不虚构协同 `task.pause/task.resume` 下行事件；AgentSession 暂停恢复仍走 run/checkpoint 协议，普通协同任务只支持平台已有的取消与状态回报。
- 不混淆 Collaboration task、Flow `/run/tasks/{task_id}` 与 AgentSession run，也不把本地后台进程句柄冒充平台任务 ID。
- 不重复实现已有 attach/resume、审批、Schedule、凭证加密和单实例容器机制。

### 9.2 双端复核结论

#### 9.2.1 Java 与 Electron 能力差距

| 能力 | Java 当前状态 | Electron 可迁移机制 |
|------|--------------|---------------------|
| 工具调度 | `ToolDispatcher.dispatch()` 直接同步执行，无跨会话统一有界调度 | `src-electron/agent-runtime/index.js:40-90`：全局许可、FIFO、排队取消 |
| 工具结果补偿 | `ToolResultReporter` 有限重试；`AgentSessionImpl` 使用内存 `pendingToolResults` | `tool-callback-persist.js:101-154`、`agent-loop-recovery.js:161-197`：持久化待回传队列与恢复冲刷 |
| 流活性 | 需要统一按 run/代次管理的空闲判定 | `assits-sse-lifecycle.js:67-175`：消息重置、streamId 守卫 |
| 协同活性 | 固定重连间隔，只发 ping，无下行静默判定 | `client-collab.js:2342-2510`：下行超时、调度漂移、有界退避和抖动 |
| worker 控制 | `WorkerRuntime.pause()` 主要停止接新任务；`TaskExecutor` 没有在途会话控制句柄 | 只迁移取消与生命周期思路，具体协议以后端为准 |
| 文件写入 | `FilePlugin.writeFile()` 直接截断写入，无编辑乐观锁 | `file-tools.js:478-653`：原子写入、SHA-256、dry-run、结构化冲突 |
| 隐式文件变更 | 无统一工作区基线和有界审计 | `workspace-baseline.js:54-149`：忽略目录、深度/条目上限、不跟随符号链接 |
| 长命令 | Shell 以前台同步执行和输出限额为主 | `shell-tools.js:1019-1252`：后台进程注册表、列表/输出/停止 |
| MCP | `McpToolClient` 支持 Streamable HTTP | `mcp-stdio-manager.js:22-157`：Stdio 子进程复用、分帧、超时和退出清理 |
| 父子任务 | 有独立会话与协同基础，无完整受控编排 | `subtask-manager.js:242-405`：独立 child session/run、能力快照和客户端 fan-out |

#### 9.2.2 后端已证实、无需重新发明的协议能力

1. **客户端工具结果已具备幂等基础**：`agent_client.py` 的 `POST /client/call/result` 以 `run_id + "." + call_id` 定位记录；success/error 的相同结果重放返回成功，不同结果拒绝。`client_offline` 允许迟到真实结果覆写并记录 `compensated_at`；`client_paused` 返回机器可识别的 `tool_call_paused`。
2. **恢复提交有独立幂等语义**：`services/agent_loop/results.py` 对 canonical `tool_results` 计算 submission hash；相同 `submission_id + hash` 幂等，同 ID 不同内容拒绝。waiting/uncertain 全部清空后才 ready/paused，取消或过期场景走 archive-only，且更新带 checkpoint version 乐观锁。
3. **协同取消已存在**：`services/client/protocol.py` 与 `service.py` 明确支持 `task.cancel`，payload 包含 task_id、version、cancel_request_id 等；活动任务通过下行事件取消，pending 任务可由平台直接终态取消。
4. **协同任务终态不能盲目重放**：`task.result/task.error` 和 REST PATCH 使用任务状态与 expected_version 约束；已终态、版本冲突和不同结果必须先查询平台对账，不能套用工具回调的删除规则。
5. **Schedule 回报是另一条协议**：`/client/schedules/runs/{run_record_id}/report` 直接更新执行记录，没有工具回调的 identical-replay 判定；自动补偿前必须读取 run record 并核对终态。
6. **Stdio MCP 配置已有权威格式**：后端支持 stdio、streamable_http、sse；Stdio 参数为 command/args/env，并支持 `stdio://command arg...` 与 base64 JSON data URI。Java 应兼容解析，不另造配置协议。
7. **子任务平台记录已存在**：`dialogue_subtask` 使用 task_kind、target_client_id/device_id、delivery_policy、command_payload、source/child IDs；max_parallelism、wait_for_completion 和按索引聚合属于 Java 编排层，不伪装成服务端字段。

#### 9.2.3 本轮架构纠偏

- `DigitalWorker.newAgent()` 会为每个 flow 创建独立 `TaiyiAgent`。共享调度器和 Outbox 必须由 worker 创建并显式注入所有 agent，禁止每个 agent 各自 new 一套导致限额和补偿队列失效。
- `AgentSessionTaskHandler` 将真实会话封装在私有执行对象内，`TaskExecutor` 只保存状态枚举。Phase V 必须引入共享任务控制句柄，不能只保存 Future 就宣称支持在途控制。
- `REPORT_UNKNOWN` 是任务终态回传未知，不是工具执行状态未知。补偿的是结果报告，不是任务或工具本身；其存储可以复用 Phase T 原语，但平台对账和错误分类必须独立实现。
- Electron 的 24 小时 TTL、UI chunk 排水、页面隐藏和纯内存后台任务表不照搬；Java 采用死信可见、代次守卫和重启未知语义。

### 9.3 贯穿设计约束

1. **扩展面分层**：TaiyiAgent 的业务观察与工具扩展继续复用 StreamHook、SessionHook、ToolExecutionHook；DigitalWorker 的 workerId 注册/校验与任务接入使用第 5.2/5.3 节的 Worker SPI。调度、Outbox、watchdog 和任务注册表属于运行时内部基础设施，不在门面堆叠业务参数。
2. **worker 级共享运行时**：增加内部 `ToolRuntime`，聚合 ToolExecutionScheduler 与结果补偿设施。standalone agent 默认可持有独立实例；DigitalWorker 必须持有唯一实例并注入全部 per-flow agent，最后由 worker 统一关闭。
3. **同会话控制串行**：调度、取消、恢复和终态化不得绕过既有会话锁与 recoveryGeneration；跨会话并行受统一资源上限约束。
4. **平台权威**：本地记录只用于瞬态补偿和执行提示；启动、重连和恢复先查询平台任务/run/checkpoint/结果状态。
5. **副作用与结果分离**：补传最终结果不等于重新执行工具或任务；任何自动恢复都不得通过重放副作用来“重建结果”。
6. **最小持久化**：普通 workspace 不保存凭证、完整聊天内容、长期任务历史或权威结果；瞬态记录必须有大小、数量、保留和死信策略。
7. **状态目录独立**：新增 `state-dir`，与 workspace、credential-file/key-file 三者分离。生产配置使用 `/var/lib/taiyiflow/state`；未配置文件 Outbox 时保留内存模式并明确暴露“不具跨进程补偿”，不得静默写入 workspace 或凭证目录。
8. **可取消不等于可中断**：排队任务保证可移除；已运行的 Java 工具、外部进程和远程 MCP 只承诺传播取消请求并如实回报最终状态。
9. **安全默认**：文件与 Shell 继续执行真实路径、符号链接、工作目录和白名单校验；Stdio MCP 默认关闭并采用命令、参数、环境变量白名单。
10. **单实例不变**：不引入多实例选主、分布式锁或本地高可用协议；同一 workerId 仍只运行一个容器副本。

### 9.4 阶段总览

| 阶段 | 优先级 | 内容 | 依赖 | SDK 本地验收重点 |
|------|--------|------|------|------------------|
| **Phase S** | P0 | worker 共享 ToolRuntime、执行上下文与有界调度 | Phase G–R | 多 flow 共享限额、队列上限、FIFO、排队取消、审批不占槽 |
| **Phase T** | P0 | 工具回调与 resume 结果的瞬态 Outbox | S；后端幂等已核实 | 崩溃窗口、重复补传、协议错误分类、死信、绝不重放工具 |
| **Phase U** | P0 | 流空闲 watchdog 与协同半开检测 | G–R，可与 S/T 并行 | 代次守卫、心跳刷新、工具期超时、下行静默、调度漂移 |
| **Phase V** | P0 | worker 任务注册表、task.cancel 与终态补偿 | S/T/U | 排队及在途取消、会话 pause/resume、版本防旧、REPORT_UNKNOWN 对账 |
| **Phase W** | P1 | 原子文件编辑与工作区变更审计 | S | 原子替换、SHA 冲突、沙盒回归、有界扫描、审计降级可见 |
| **Phase X** | P1 | 后台进程句柄 | S/V；产物审计复用 W | 输出 draining、进程树停止、会话清理、重启状态未知 |
| **Phase Y** | P1 | Stdio MCP 子进程托管 | S/X | 后端格式兼容、单例启动、分帧、请求超时、白名单与脱敏 |
| **Phase Z** | P2 | 父子任务能力快照与受控编排 | V；涉及 Stdio MCP 时依赖 Y | 防提权、快照隔离、fan-out、父取消、按索引聚合、平台路由 |

实施顺序以 S → T → V 为主线，U 可与 S/T 并行；W 在 S 后启动，X 在 V/W 后收尾；Y 复用 X 的进程生命周期原语但不复用其任务语义；Z 只在明确需要 worker 充当协调者时启动。每个阶段完成后保持既有测试全绿，再进入依赖它的阶段。

### 9.5 详细实施计划

#### Phase S：统一工具执行上下文与有界调度

**目标**：将工具执行从“各会话直接运行”改为“worker 共享资源治理 + 会话独立上下文”，但不改变现有 Hook 链顺序。

**实现**：

1. 在 core 增加内部 `ToolRuntime` 和 `ToolExecutionScheduler`；TaiyiAgent Builder 提供受控注入点。DigitalWorker 创建唯一实例，`newAgent(flowId)` 必须注入同一实例，worker 关闭时统一释放。
2. 增加不可变执行上下文，至少携带 `flowId/sessionId/runId/taskId/workspace/requestMode/cancellation`；不得在工具执行期间读取其他会话的全局可变值，也不用 ThreadLocal 代替显式上下文。
3. 调度器提供全局并发、每会话并发和有界 FIFO 队列；worker execution.concurrency 映射到共享上限，配置非法时拒绝启动，不静默转为无界。
4. 保持 `PermissionHook → before → takeover|plugin → after` 顺序。权限审批和 ASK 等待在申请工具执行许可前完成，避免审批占槽。
5. 排队取消必须移除 waiter 并释放等待资源；获得许可后取消只传播中断/取消信号，最终在 `finally` 释放许可。
6. 队列满、执行超时和取消返回明确工具错误，不伪装成平台成功结果；调度指标通过既有 Hook/日志输出，不新增 metrics 依赖。
7. AgentSessionTaskHandler 与 TaskExecutor 使用同一任务控制上下文，为 Phase V 暴露 pause/resume/cancel 句柄预留接线，但本阶段不虚构控制协议。

**主要改动位置**：

- `taiyiflow-sdk-core/.../core/dispatch/ToolDispatcher.java`
- `taiyiflow-sdk-core/.../core/session/AgentSessionImpl.java`
- `taiyiflow-sdk-core/.../TaiyiAgent.java`
- `taiyiflow-sdk-worker/.../DigitalWorker.java`、`AgentSessionTaskHandler.java`、`TaskExecutor.java`

**测试与验收**：

- 同一 worker 的两个 flow 同时提交工具，运行总数仍不超过 worker 全局上限；每会话上限独立生效。
- 超额任务按 FIFO 获得许可；队列满时行为可预测。
- 排队取消后不再执行，后续任务可继续；运行中取消不会泄漏许可。
- ASK 审批等待期间其他工具仍可执行，审批不占用执行许可。
- takeover、deny、before/after 异常和现有超时语义全部回归。
- 两个会话使用不同 workspace 时，工具上下文不会串用；关闭 worker 后无调度线程残留。

#### Phase T：工具结果瞬态 Outbox 与补偿

**目标**：解决“工具已执行、最终结果尚未被平台确认时进程退出”的回传缺口，同时覆盖 client/call/result 与 checkpoint resume.tool_results，不建立本地结果权威。

**实现**：

1. 在 core 定义内部 `ReportOutbox` 存储原语和协议适配器；本阶段实现 `CLIENT_TOOL_RESULT` 与 `CHECKPOINT_TOOL_RESULTS` 两类。存储可复用，重试和错误分类不得合并成一套通用规则。
2. standalone 默认维持内存补偿；worker 通过共享 ToolRuntime 注入文件实现。`state-dir` 未配置时不得伪装成持久模式。
3. 工具执行完成后先原子持久化最终结果，再进入对应回传通道（客户端回调 REST 或 resume 首帧）。客户端回调记录以 `run_id + call_id` 去重；resume 记录以 `checkpoint_id + submission_id` 去重并保存 canonical payload/hash，重试不得改变 submission_id 或结果内容。
4. 记录仅含平台回传所需 payload、flow/session/message/checkpoint 标识、类型、创建时间、重试次数、最后错误和状态；不得包含凭证、MCP env 或重新执行命令。
5. 状态至少区分 `PENDING/REPORTING/DEAD_LETTER`。文件实现采用同目录临时文件 + 原子替换，目录/文件使用最小权限；损坏记录隔离，数量/单条/总大小有上限。
6. 若结果落盘失败，仍尝试一次直接回传；若回传也不成功，必须触发 WorkerHook 严重告警并阻止该会话被误认为可安全自动恢复，不得静默继续或重放工具。
7. worker 启动和网络恢复时先认证并对账，再按记录类型补传。客户端回调依据可查询的 run/checkpoint 状态与回调响应分类；resume 记录先核对 checkpoint version、waiting/uncertain 集合和取消状态，再进入 attach/resume 主流程。
8. **客户端回调分类**：
   - 2xx（含 identical replay、client_offline 接受补偿）→ 删除记录。
   - HTTP 400 且 detail 明确为 `tool_call_paused` → 按平台指令删除，不再补偿。
   - 网络错误、超时、429、5xx → 保留并指数退避；认证失败走既有刷新机制，持续失败暂停冲刷并告警。
   - 不同结果、session/message 身份不匹配及其他确定性 4xx → DEAD_LETTER 或对账，不盲目重试或删除。
9. **resume 提交分类**：相同 submission_id/hash 的 changed=false 视为已接受；同 ID 不同 hash、checkpoint version 冲突、结果不在 waiting/uncertain 集合或 archive 内容冲突均停止自动重试并对账。取消/过期只允许按 archive-only 语义归档 cancelled 工具记录。
10. 为 REST 错误保留 HTTP status 与结构化 detail，不能靠匹配中文错误文案分类；日志只记录标识和脱敏错误，不输出完整结果正文。
11. 不照搬 24 小时 TTL 静默删除。过期或超过重试预算转 DEAD_LETTER，保留最小审计信息并通过 WorkerHook 暴露。
12. Outbox 只保存最终结果，不保存“重新执行工具”的命令计划；恢复代码不得调用 ToolDispatcher 重建缺失结果。

**交付配置**：

- `WorkerConfig` 增加 `state-dir` 及 Outbox 限额配置；`deploy/worker.yml` 示例设置 `/var/lib/taiyiflow/state`。
- Dockerfile 创建并授权该目录，增加独立 volume；credential volume 保持分离。
- README 明确 state-dir 只保存瞬态补偿记录，持久令牌仍只能放独立加密凭证存储。

**崩溃窗口验收**：

- 持久化后、首次回传前退出：重启后仅补传结果，工具执行次数不增加。
- 平台已接收、删除本地记录前退出：重启可能重复回传，平台按对应幂等键处理；SDK 不宣称 exactly-once。
- `client_offline` 补传成功后清理；`tool_call_paused` 不再重试；通用 400 和不同结果进入死信。
- resume 重试保持 submission_id/hash 不变，waiting/uncertain 未满足或 version 过期时不强行恢复。
- 平台状态未知、run 已更换或存在 uncertain 副作用：不补传、不重放，进入对账/死信。
- 损坏、超限和过期记录不会阻塞其他记录，也不会被静默丢弃。
- 普通 workspace、日志和异常中不出现凭证或完整工具结果。

**平台门槛**：后端已核实支持本阶段所需幂等与取消语义，无新增 P-RESULT-OUTBOX 阻塞；真实环境仍需验证线上版本与源码一致。

#### Phase U：流空闲 watchdog 与协同半开检测

**目标**：区分连接建立、整体执行、流空闲和协同下行活性，避免假在线及旧回调误伤新执行。

**实现**：

1. 在 SSE/WS 会话层增加 run 级空闲 watchdog，实际收到数据、有效协议事件和心跳刷新传输活性；心跳只证明连接活性，不能推进业务终态。
2. 超时回调校验 `runId + recoveryGeneration + streamGeneration/handleIdentity`，旧流、旧 attach/resume 的迟到回调直接失效。
3. 工具执行使用独立的本地工具超时与活性状态；本地工具运行期间切换到有上限的工具期保护，不因长时间无流事件误判，也不无限关闭 watchdog。
4. watchdog 超时统一进入既有取消、断线和恢复状态机，不直接伪造 DONE/PAUSED，不引入 Electron UI chunk 排水逻辑。
5. CollaborationPlugin 记录任意下行帧时间，ping 与 pong/下行静默窗口分离；超过配置窗口无任何下行消息时主动关闭当前 WebSocket，走统一重连。
6. 重连改为有最大间隔的指数退避加随机抖动；worker 长期在线，不照搬桌面端达到次数后永久停止。
7. 定时任务记录预期与实际触发时间；容器冻结、系统睡眠或长 GC 后先重置活性基准并跳过本轮半开判定，防止误重连。
8. stop、connection.kicked 和认证失效仍可终止重连；新 socket 建立后旧 socket 的 onClose/ready 不得清除或覆盖新连接状态。

**测试与验收**：

- 数据、消息和有效心跳刷新计时；旧 generation/handle 超时不影响新 run。
- 工具执行、暂停、正常 close 和主动 cancel 不触发错误的自动恢复。
- 模拟只发 ping 无任何下行，超过窗口后主动关闭并重连；收到 task.dispatch 等下行帧也算存活。
- 退避间隔有上限且含抖动；stop/kicked 后不再调度。
- 模拟定时器大幅延迟，不立即误判半开；新连接不被旧 onClose 覆盖。

#### Phase V：worker 任务注册表、在途控制与终态补偿

**目标**：落实“停止接新活 + 在途任务控制”，接通已有 task.cancel，并补齐 REPORT_UNKNOWN 的对账型补偿。

**实现**：

1. 增加 worker 内部任务注册表，记录 taskId、taskKind、version、cancelRequestId、sessionId、runId、runRecordId、排队/运行状态、Future、会话控制句柄、attempt 和最后平台回报状态。
2. AgentSessionTaskHandler 在任务接受时注册控制句柄，将会话 pause/resume/cancel 暴露给 TaskExecutor；句柄随执行代次失效，旧句柄不得控制新 run。
3. task.dispatch 仍先完成幂等校验，只有任务被本地接受后才 ACK；同一 taskId 不得并发启动多个执行或恢复流程。
4. CollaborationPlugin 新增 `task.cancel` 解包与 WorkerRuntime 接线，校验 task_id/version/cancel_request_id：重复 request 幂等，旧 version 不得取消新执行，无法判定时先查询平台任务。
5. `cancel(taskId)` 先移除排队任务并回报 cancelled；在途 AgentSession 调用 run 协议 cancel，普通本地任务传播协作式取消。不得把 Flow `/run/tasks/{task_id}/cancel` 当作协同任务取消接口。
6. `pause()` 先停止接新任务，再对可暂停的在途 AgentSession 调用协议 pause；普通协同任务没有 task.pause 下行协议，不得仅修改 worker 全局状态就宣称全部 PAUSED。
7. `resume()` 恢复接活前，先按平台 checkpoint/task 状态恢复可恢复会话；状态未知时继续对账，不重新 start。已 cancel 的任务不得通过 resume 复活。
8. stop/drain 继续执行“停止接新活 → 尽量 pause/drain → 离线 → 释放调度器、进程和连接”；shutdownNow 丢弃任务必须全部进入明确终态或对账状态。
9. `REPORT_UNKNOWN` 只表示终态报告未被确认。复用 Phase T 的瞬态存储原语增加 Collaboration/Schedule 报告适配器，但**不得直接盲重放**：
   - Collaboration 先 GET task；平台已是相同终态/结果则清理，已是不同终态/结果则死信，仍可更新时携带最新 expected_version 再提交。
   - Schedule 先 GET run record；仅在状态和结果允许时补偿，不用终态 running/success/failed 报告覆盖平台新状态。
   - 网络不可达保留退避；409/version conflict、身份不匹配或不同终态停止自动重试并对账。
10. 注册项只有在平台终态确认、补偿记录清理且本地执行句柄释放后才移除；保留数量和终态审计时间有界，不形成长期任务历史。
11. 重启后以平台任务记录和检查点为权威，本地注册表只作提示；未完成且状态明确的任务关联原 taskId，新 run 必须防止旧执行重复回报。

**测试与验收**：

- 重复 task.cancel 幂等，旧 version 不影响新执行；排队取消不会执行且不会 ACK 为成功。
- 在途 pause 必须等待平台确认才进入对应状态；普通任务不被伪报为 paused。
- 同一 taskId 重复 dispatch、迟到恢复和重连补拉不会并发执行。
- pause/resume/cancel/drain 交错时不泄漏线程、工具许可或任务注册项。
- 模拟终态报告失败后重启：相同平台终态只清理，不重复执行；不同终态/version conflict 进入对账。
- kill 重启后不依据本地注册表盲目重跑；平台状态未知时只回报对账状态。

#### Phase W：原子文件编辑与工作区变更审计

**目标**：降低文件半写入、并发覆盖和 Shell 隐式产物不可见问题。

**实现**：

1. `write_file` 改为同目录唯一临时文件写入、按需 flush 后原子替换，失败清理临时文件；不支持同目录 ATOMIC_MOVE 时返回明确不支持错误，不回退为直接截断写。
2. 替换已有文件时保留其 POSIX 权限（文件系统支持时）；临时文件默认最小权限，最终目标仍须通过全部沙盒检查。
3. 新增 `file_edit`：支持 old/new string、replace_all、dry_run、expected_sha256_before，返回 before/after SHA、替换次数和变更状态。
4. 结构化返回 `PATCH_CONFLICT`，reason 区分 `MATCH_NOT_FOUND/AMBIGUOUS_MATCH/STALE_CONTENT`；换行符适配不得扩大匹配范围。
5. 保留现有 sandboxRoot、真实父目录、符号链接和 NOFOLLOW 检查；临时文件与最终目标都必须位于允许目录。
6. 增加按 session/run 分桶的工作区基线，记录 path/size/mtime，必要时对候选文件计算 hash；忽略依赖、构建、缓存、state-dir 和凭证目录。
7. 扫描设置最大深度、条目数和时间预算，不跟随符号链接；超限或扫描期间冲突必须返回 `complete=false`/降级原因，不能假装无变更。
8. Shell 声明的 fileOperations 与扫描结果去重，产物元数据按现有 FileClient/平台结果通道上报；本地基线不长期保存，不提供回滚 API。

**测试与验收**：

- 写入异常不会留下半文件或临时文件；并发编辑 SHA 不匹配时不覆盖新内容。
- 0 个、1 个、多个匹配及 dry-run 返回正确结果；换行符和 UTF-8 内容回归。
- 符号链接、越界路径和不存在父目录攻击测试保持通过。
- 大仓库超过扫描预算会显式降级；同次产物不重复上报；审计结果不被描述为完整事务记录。

#### Phase X：后台进程句柄

**目标**：避免长构建和脚本长期占用前台工具调用，同时统一输出与进程生命周期。

**实现**：

1. ShellPlugin 增加受控 `background` 模式和 SDK 生成的 processHandle，提供 `process_list/process_output/process_stop/process_cleanup`；不与 Collaboration taskId、Flow taskId 或 OS PID 混用。
2. 后台进程记录经命令白名单校验的执行配置、cwd、session/run/task 关联、状态、创建/结束时间和有限输出；该记录默认仅内存，不写入结果 Outbox。
3. stdout/stderr 持续 draining，保留有界尾部或明确截断标记；不得因达到输出上限而停止读取导致子进程阻塞。
4. process_stop 使用 ProcessHandle/进程组终止后代进程，先请求终止再按超时强制结束；只能操作本 SDK 注册且仍匹配的进程，禁止按模型提供的任意 PID kill。
5. 会话取消、worker stop 和工具调度器关闭时按策略清理后台进程；W 的审计机制在进程结束时补扫产物。
6. 进程重启后历史 processHandle 返回 unknown/lost，不自动重启命令，不假定原 OS 进程仍属于当前 worker，也不根据陈旧 PID kill 进程。

**测试与验收**：

- 长任务可查询状态和增量输出；超过输出上限仍可正常退出。
- stop 能清理子进程树，不能停止非本 SDK 创建的进程。
- 会话取消和 worker 停机无孤儿进程、无未释放工具许可。
- 模拟 worker 重启后旧 processHandle 只对账/报未知，不重复执行原命令。

#### Phase Y：Stdio MCP 子进程托管

**目标**：在现有 Streamable HTTP MCP 外补 Stdio 传输，复用统一工具调度、权限链和 Phase X 的进程生命周期原语。

**实现**：

1. 配置解析兼容后端 command/args/env、`stdio://command arg...` 和 base64 JSON data URI；使用与后端一致的参数拆分规则，不另造 YAML/URL 协议。
2. 新增 Stdio MCP 实例管理器，按经过规范化的 server 配置键复用进程；并发首次调用通过 single-flight 防止重复 spawn。
3. 实现 MCP initialize → notifications/initialized → tools/list/tools/call，协议版本与现有 `McpToolClient` 对齐。
4. stdout 按换行分隔 JSON-RPC 处理跨 chunk 和不完整帧，pending 请求按 id 关联并设置超时；子进程 stdout 只允许协议帧，stderr 只作有界、脱敏日志。
5. 进程 error/exit 时拒绝全部 pending 请求并清理实例；worker stop 统一销毁，重启实例不得隐式重放上次 tools/call。
6. 默认不继承全部环境变量，只显式传递允许变量；命令、参数、cwd、server 数量、输出和运行时间均有白名单或上限。敏感 env 仅驻留配置/内存，不写 state-dir、能力快照或日志。
7. MCP 工具仍注册为 `c_{server}_{tool}` 并进入 ToolDispatcher，不绕过 PermissionHook、并发上限和取消传播。

**测试与验收**：

- 三种后端配置形式解析一致；并发 listTools 只启动一个进程；跨 chunk 响应正确关联请求 id。
- 请求超时、进程启动失败和异常退出都能拒绝 pending，不遗留等待项。
- env 默认不泄露宿主凭证，命令/参数日志脱敏，未允许命令拒绝执行。
- 工具调用取消不自动重放；worker 停止后无 MCP 子进程残留。

#### Phase Z：父子任务能力快照与受控编排（按需）

**目标**：让 worker 可作为协调者发布有限子任务，而不是通过子任务获得额外权限。

**实现**：

1. 以平台 `dialogue_subtask` 契约为准创建任务，建立 parent task/session/run/message 与 child session/run/task 的关联；target_client_id/target_device_id 至少一个，路由使用既有 delivery_policy。
2. requestMode 采用“显式值不得高于父级；未显式则继承父级；非法显式值拒绝”的规则，ask/plan 只读父任务不得提升为 agent。
3. 创建子任务时冻结 features、requested skills、MCP server 名称和工具能力快照；执行期间不读取其他会话的全局配置。
4. 快照只包含能力名称、版本/配置标识和平台允许的引用，不包含凭证或 MCP 敏感 env；接收端仍需按自身身份重新鉴权、白名单过滤和权限收敛。
5. 本地/远程路由都必须先创建平台任务记录，本地执行只是 delivery 方式，不以本地返回值替代平台状态。
6. max_parallelism、wait_for_completion、timeout 和按索引聚合在 Java 编排层实现；批量 fan-out 设置数量与并行上限，部分失败不静默吞掉。
7. 父级取消通过平台协同取消请求传播到所有子任务并携带 request_id/expected_version；仅调用本地 Future.cancel 不算完成父取消。
8. detached 子任务只有在平台查询、取消、结果补拉和重启对账均验证后才启用，否则先仅支持 waitForCompletion；发布结果未知时先查平台，不重复创建子任务。

**测试与验收**：

- 只读父任务不能通过显式参数或伪造 features 提权；接收端过滤未授权 MCP/工具。
- 子任务使用创建时快照，父会话切换或配置变化不影响已发布任务。
- fan-out 不超上限，父取消覆盖排队和运行子任务，结果顺序与输入一致。
- 创建响应丢失后先按 source/child IDs 对账，不重复发布；worker 重启后 detached 任务只按平台记录对账，不重复执行。

### 9.6 平台配套与联调门槛

| 工作项 | 当前结论 | 阻塞范围与要求 |
|--------|----------|----------------|
| **P-RESULT-OUTBOX** | 源码已核实，无新增后端阻塞 | Phase T 真实联调需验证线上版本具备 run_id+call_id 幂等、tool_call_paused、client_offline、submission_id/hash 与 checkpoint version 语义。 |
| **P-TASK-CANCEL** | task.cancel 下行已存在；REST PATCH 状态枚举缺少 `cancelled` | Phase V 按后端 payload/版本接线；在途取消可使用既有 WS task.status，REST 取消补偿在后端补齐 `cancelled` 前保持 REPORT_UNKNOWN，不改报 failed，也不要求新增 task.pause/task.resume。 |
| **P-TASK-REPORT** | Collaboration 支持 expected_version；Worker 单条 Schedule run GET 尚缺 | SDK 先查询再补偿；后端需补 `/api/v1/agents/client/schedules/runs/{run_record_id}` 或等价 Worker 查询，manager 路由不能替代。未补齐前 Schedule 补偿保留外部验收阻塞，不得盲重放或以终态覆盖新状态。 |
| **P-RECOVERY** | 延续 Phase G–R 门槛 | Phase V/Z 重启与跨实例恢复要求任务/run/checkpoint 状态可查询且一致；旧执行与新执行具备代次隔离。 |
| **P-RESULT** | 延续平台结果权威 | Phase W/X/Z 产物与任务结果由平台保存查询；本地进程表、工作区基线和 Outbox 不替代平台。 |
| **P-MCP/P-SUBTASK** | 配置与任务契约已核实 | Phase Y/Z 按线上实际支持版本联调，不把客户端编排字段误当后端字段。 |
| **P-APPROVAL** | 延续平台审批权威 | Phase S/Y/Z 权限回归，子任务和 MCP 不扩大审批范围；本地决策继续回报平台审计。 |

第三方身份、平台审批、跨实例恢复、重复结果、取消和任务对账必须在用户、第三方身份实现及平台提供的真实环境联调。本地单元测试、mock server 和容器构建只能完成 SDK 侧验收，不得替代平台验收或将阶段标记为真实闭环。

### 9.7 统一验证策略

1. **阶段单测**：每阶段在同模块 `src/test/java` 增加正常、边界、并发、取消、迟到回调和崩溃窗口测试；文件系统使用 `@TempDir`。
2. **协议对照**：涉及 call/result、resume.tool_results、task.cancel/result、dialogue_subtask 或 MCP 字段时，与后端实际字符串和状态语义逐项对照，并更新协议模型测试。
3. **共享资源回归**：至少一个测试覆盖同一 DigitalWorker 的多 flow agent，证明调度器、Outbox 和关闭生命周期只有一套。
4. **资源释放**：工具许可、任务注册项、pending 请求、定时器、WebSocket、进程和输出缓冲在成功、失败、取消、停机后均可释放。
5. **安全回归**：路径逃逸、符号链接、命令白名单、环境变量、日志脱敏、子任务提权和凭证不落普通 workspace/state-dir 必须有负向测试。
6. **恢复负向测试**：平台不可达、run 状态未知、pending/uncertain 副作用、检查点过期、任务版本冲突和旧任务迟到时，不发生自动重放。
7. **构建回归**：
   - 单阶段：`./mvnw test -pl <相关模块> -am`
   - 阶段收尾：`./mvnw test`
   - 交付变更时：`./mvnw package`
8. **真实联调**：由用户/平台环境执行断网、kill worker、服务端实例切换、重复派单、暂停取消和重启对账；记录平台任务/run/checkpoint/结果证据。

### 9.8 阶段完成定义

每个 Phase 只有同时满足以下条件才可标记“SDK 侧完成”：

- 代码、单测、配置和必要示例已提交到工作区，且全量既有测试无回归。
- 对应安全边界和禁止重放测试通过，资源清理可验证。
- README/worker.yml/Dockerfile/示例仅在真实能力可用时更新，不提前声明未实现能力。
- 若依赖平台配套，明确标记“SDK 侧完成、平台联调待办”，不得写成端到端完成。
- 本计划更新该阶段状态和遗留项；其他文档不复制出第二份实施清单。

### 9.9 实施状态

| 阶段 | SDK 侧状态 | 验证与遗留项 |
|------|------------|--------------|
| Phase S | 已完成 | 共享 ToolRuntime、有界调度、取消/超时、Hook 顺序及多 flow 生命周期已通过全模块回归；真实并发压测待平台环境验收。 |
| Phase T | 已完成 | 工具回调与 resume 瞬态 Outbox、独立 state-dir、容量/重试边界、崩溃窗口和补传不重放已通过本地测试；跨进程补传与死信处理待真实平台联调。 |
| Phase U | 已完成 | run 级 watchdog、代次守卫、工具期保护、协同静默检测、调度漂移与有界退避已通过全模块回归；真实断网和服务端实例切换待平台联调。 |
| Phase V | 已完成 | prepare-before-ACK、任务注册表、task.cancel、真实会话控制、版本防旧、REPORT_UNKNOWN 查询补偿与 WorkerRuntime 已接线并通过测试；跨实例接管和真实取消竞态待平台联调。 |
| Phase W | 已完成 | 原子替换、file_edit SHA 冲突、沙盒、有界审计和锁清理已通过插件模块及全量回归；产物上传与元数据真实性待平台联调。 |
| Phase X | 已完成 | SDK 私有进程句柄、增量输出、进程树停止、会话/Worker 清理及重启后 unknown/lost 已通过本地进程测试；真实平台产物补扫待联调。 |
| Phase Y | 已完成 | Stdio MCP 配置兼容、single-flight 启动、JSON-RPC 分帧、环境隔离与取消清理已通过真实子进程测试；后端配置下发与多服务联调待平台验收。 |
| Phase Z | 已完成 | request_mode 防提权、无凭证能力快照、平台任务先行、本机投递、fan-out 上限、按索引聚合、创建对账和版本化取消已通过测试；detached 子任务未启用，真实父子任务、跨实例恢复及取消竞态待平台联调。 |

全模块 `./mvnw test` 共 345 项测试通过（0 failure、0 error、0 skipped）。以上“已完成”仅表示 SDK 代码与本地自动化验收完成，不代表真实平台端到端闭环；真实平台联调、跨实例恢复、detached 子任务和生产压测仍是外部或后续验收项。

## 十、Electron 后台能力对齐计划（独立文档）

默认 Flow、Flow Metadata、媒体理解、本地/远程浏览器、高价值工具及完整 SKILL 供应机制已拆分为独立计划：

- [TaiyiFlow Java SDK Electron 后台能力对齐实施计划（Phase AA–AL）](plan-taiyiflowJavaSdk-capability-alignment.prompt.md)

该独立文档是 Phase AA–AL 的唯一权威实施与状态记录：**AA–AG 已于 2026-11-14 SDK 侧闭环**（真实环境验收 1–9 项仍待执行）；**AH–AL 为 2026-11-21 依据能力差距审查报告（`review-taiyiflowJavaSdk-capability-gap.prompt.md`，含 11-21 复核）新增的差距闭环阶段，已于 2026-11-21 SDK 侧完成（真实验收待执行）**。本路线图不再复制其阶段清单和验收明细。
