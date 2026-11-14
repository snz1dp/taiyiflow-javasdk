# 传输协议参考

本文与 SDK 当前实现（`transport/envelope`、`transport/stream`、`transport/rest`）逐一对照。协议权威来源为 taiyiflow-dev 后端代码；修改协议模型时必须同步更新对照单测。

## 流式运行（主通道）

### 协议选择

- `StreamProtocol.AUTO`（默认）：读取服务端 `GET /api/v1/config` 的 `stream_protocol` 字段（`sse` / `websocket`）；读取失败或未知值回退 WebSocket。
- `StreamProtocol.SSE` / `StreamProtocol.WEBSOCKET`：显式覆盖。
- `sseFallbackEnabled=true`（默认）：WS 失败时回退服务器推送事件（SSE）。

### WebSocket

- 端点：`WS /api/v1/run/{flow_id}/websocket`，鉴权用 query `__TOKEN__`。
- 首帧：连接后 **10s 内**发送 `ChatRequest` JSON；超时或非法首帧服务端关闭（4002）。
- 首帧后服务端不再读 WS：**工具结果禁止 WS 回传**，必须走 REST。
- 服务端推送封套：`{"event": "...", "data": {...}}`；消息正文在 `data.chunk`。

### 服务器推送事件（SSE）

- 端点：`POST /api/v1/run/{flow_id}/stream`，请求体为 `ChatRequest`，鉴权 `Authorization: Bearer`。
- 事件封套与 WS 相同。

### ChatRequest 字段（首帧）

| 字段 | 说明 |
|------|------|
| `input_value` | 用户输入 |
| `session_id` | 会话 ID |
| `tweaks` | 流程参数覆盖（Map<String, JsonNode>） |
| `run_id` / `execution_mode` / `execution_attempt_id` | 执行标识与模式（attach/resume/start） |
| `after_event_id` / `subscription_id` | 重接续传位点 |
| `checkpoint_id` / `checkpoint_version` / `tool_results` / `submission_id` | 检查点恢复与工具结果提交 |
| `message_id` | 消息 ID |
| `files` | 输入文件（服务端路径列表） |
| `request_type` | 请求类型（agent/plan/ask 等） |
| `parent_id` | 父执行轮次（run，子任务） |
| `reasoning` / `internet_search` / `knowledge_scopes` / `knowledge_docs` | 能力开关与知识库范围 |
| `requested_skill_names` | 本轮显式选择的技能名称 |
| `client_skill_packages` | 本地/平台技能供应包；含来源、摘要或全文与附件声明，详见 [skills.md](skills.md) |
| `available_tools` / `client_mcp_servers` | 工具清单与客户端 MCP 配置 |
| `client_id` / `device_id` / `client_type` | 客户端标识 |

### 事件类型（EventConstants，11 种）

| 常量 | 值 | 说明 |
|------|----|------|
| `MESSAGE` | `message` | 消息增量（data.chunk） |
| `HEARTBEAT` | `heartbeat` | 心跳 |
| `ERROR` | `error` | 错误（data.error） |
| `CLOSE` | `close` | 关闭 |
| `CUSTOM` | `custom` | 自定义事件 |
| `BYTES` | `bytes` | 二进制块 |
| `TOOL_CALL` | `tool_call` | 工具调用（旧形态） |
| `FILE_WRITTEN` | `file_written` | 文件产出 |
| `CLIENT_TOOL` | `client_tool` | 客户端工具调用 |
| `MCP_TOOL` | `mcp_tool` | MCP 工具调用 |
| `BUILT_IN` | `built_in` | 内置工具调用 |

### WebSocket 关闭码（CloseCodes）

| 常量 | 码 | 含义 |
|------|----|------|
| `NORMAL` | 1000 | 正常关闭 |
| `INVALID_PATH` | 4000 | 非法 WS 端点路径 |
| `AUTH_FAILED` | 4001 | 认证失败 |
| `INVALID_FIRST_FRAME` | 4002 | 首帧超时或非法请求 |
| `PERMISSION_DENIED` | 4003 | 权限/配额/模型密钥拒绝 |
| `FLOW_UNAVAILABLE` | 4004 | flow 不存在或不可用 |
| `SESSION_CONFLICT` | 4009 | 会话执行冲突 |

`CloseCodes.describe(code)` 返回文案；`isError(code)` 判定 ≥ 4000。

## 工具调用与结果回传

- 工具调用经流内 `client_tool` / `mcp_tool` / `built_in` 事件下发，由 `ClientToolCallParser` 解析为 `ToolCall`。
- 结果回传：`POST /api/v1/agents/client/call/result`，载荷（payload）字段：`flow_id, session_id, message_id, run_id, call_id, success, result, error_message`。
- 回传失败进入 ReportOutbox 补传发件箱补偿（见 [execution-control.md](execution-control.md)）。

## 执行控制 REST

| 端点 | 说明 |
|------|------|
| `POST /api/v1/run/sessions/{sessionId}/pause` | 暂停 run |
| `POST /api/v1/run/sessions/{sessionId}/cancel` | 取消 run |
| `GET /api/v1/run/checkpoints/resumable` | 查询可恢复检查点（flow_id/session_id 查询参数） |

## 协同 WS

- 端点：`/api/v1/agents/client/websocket`；封套 `{type, event, payload}`。
- 握手 query：`__TOKEN__`、`client-id`、`device-id`、`connection-role`、`device-type=java`、`client-type=text`、`instance-id`（如有）；worker 角色附 `capabilities=notification.v1,task.remote_task.v1,task.dialogue_subtask.v1`。
- `primary_cli` 角色必须携带 instance_id；无 register 首帧，收 `connection.ready` 即就绪。
- 事件与回调见 [collaboration.md](collaboration.md)。

## REST 辅助面

鉴权统一 `Authorization: Bearer`；后端身份实际取自网关注入的 `X-Credential-*` 头（可经 `credentialHeaders` 附加）。

| 客户端 | 基址 | 能力 |
|--------|------|------|
| `SessionClient` | `/api/v1/agents/flows/{flowId}/sessions` | 会话 CRUD、消息查询/删除 |
| `MemoryClient` | `/api/v1/agents/memories` | 记忆 list/create/get/forget/restore |
| `FileClient` | 会话文件接口 | upload（multipart 表单上传）/ download |
| `SkillClient` | `/api/v1/agents/skills` | 技能上传/内容/安装管理 |
| `McpClient` | `/api/v1/agents/mcp/servers` | 服务端 MCP 注册管理 |
| `ScheduleClient` | `/api/v1/agents/client/schedules` | 定时任务 CRUD、启停、执行记录与回报 |
| `CollaborationTaskClient` | `/api/v1/agents/client/collaborations` | 协同任务创建/查询/取消/running/success/failure/cancelled（expected_version 乐观锁） |
| `ConfigClient` | `/api/v1/config` | 流协议、默认 Flow、媒体理解 Flow 与上传限制 |
| `FlowMetadataClient` | `/api/v1/run/{flow}/metadata` | Flow 名称、能力特性、知识库与扩展字段 |
| `FlowRunClient` | `/api/v1/run/{flow}`、`/api/v1/run/tasks/{taskId}` | 非流式同步/异步运行、任务轮询、等待与取消 |

非流式运行请求在 ChatRequest 上附加 `async`：同步 `run(...)` 直接返回 `RunResult`；`submitAsync(...)` 仅接受 HTTP 202 与有效 taskId，随后通过 `GET /api/v1/run/tasks/{taskId}` 轮询，或 `POST /api/v1/run/tasks/{taskId}/cancel` 请求取消。提交结果未知时抛 `SubmissionUnknownException`，不能按失败直接重发。

`/api/v1/config` 当前使用的字段包括：`stream_protocol`、`default_flow_id`、`doc_unstructured_flow_id`、`image_flow_id`、`audio_flow_id`、`video_flow_id`、`doc_exts`、`max_file_size_upload`（MiB）和 `once_max_file_count`。媒体 Flow 与默认 Flow 字段在对应能力启用时为必填；缺失时明确报错，不使用猜测值。

## 鉴权与换牌

- REST：`Authorization: Bearer <accessToken>`；WS：query `__TOKEN__`。
- `accessToken(Supplier<String>)` 每次请求读取最新令牌。
- WorkerAuth 401 换牌按认证代次合并，每个操作最多重试一次；persistentToken 确定性拒绝（400/401/403/410）清空凭据。