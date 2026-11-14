# MCP 接入

SDK 支持两种 MCP（Model Context Protocol）服务器形态，工具统一以 `c_{server}_{tool}` 命名注册到 `PluginManager`，经过同一条权限、Hook、并发与取消链。

## Streamable HTTP：McpToolClient

```java
McpToolClient client = new McpToolClient(
    "https://mcp.example.com/mcp",
    Duration.ofSeconds(30));                       // 连接与每请求超时（必传，无默认值）
// 可选附加请求头
McpToolClient client = new McpToolClient(url, timeout, Map.of("Authorization", "Bearer ..."));

List<McpToolClient.McpTool> tools = client.listTools();
JsonNode result = client.callTool("tool_name", arguments);

// 一键注册到 agent（返回注册名列表）
List<String> names = client.registerTools("weather", agent.getPluginManager());
// 注册名形如 c_weather_<tool>
```

行为要点：

- 协议版本 `2025-03-26`；懒初始化：首次调用发 `initialize` + `notifications/initialized`。
- 自动跟踪响应头 `Mcp-Session-Id`；响应兼容 JSON 与 SSE（按 JSON-RPC id 匹配）。
- `serverName` 空白抛 `IllegalArgumentException`。
- `McpTool` record：`(name, description, inputSchema)`。

## Stdio：StdioMcpManager

托管本地 MCP 子进程，适合无 HTTP 端点的本地服务器。

```java
StdioMcpPolicy policy = StdioMcpPolicy.builder(workspace)
    .allowCommand("python3")                 // 命令白名单（默认空 = 全部拒绝）
    .allowEnvironment("MCP_SERVER_TOKEN")    // 显式 env 白名单（默认空）
    .build();

StdioMcpManager stdio = new StdioMcpManager(policy, Duration.ofSeconds(30));

StdioMcpServerConfig config = StdioMcpServerConfig.of(
    "python3", List.of("-u", "server.py"),
    Map.of("MCP_SERVER_TOKEN", token));

stdio.registerTools("local", config, agent.getPluginManager());
```

### 配置来源

`StdioMcpServerConfig` 支持三种创建方式：

```java
// 1. 直接构造
StdioMcpServerConfig.of(command, args, env);
StdioMcpServerConfig.of(command, args, env, cwd);   // cwd 必须位于 workspace 内

// 2. 字符串解析
StdioMcpServerConfig.parse("stdio://python3 -u server.py");
// stdio:// 与后端一致：仅按 Unicode 空白拆分，不执行 shell，不解释引号或转义
StdioMcpServerConfig.parse("data:application/json;base64,...");  // base64/percent 编码 JSON

// 3. 后端 JSON 配置
StdioMcpServerConfig.fromJson(node);   // {command, args, env, cwd}；
                                       // 无 command 时回退读 server_url / url / stdio 字段再 parse
```

### 安全策略与默认限制

| 项 | 默认值 | 说明 |
|----|--------|------|
| 命令白名单 | 空（必须显式 allow） | `allowCommand(...)` |
| 显式 env 白名单 | 空（必须显式 allow） | `allowEnvironment(...)`；config.env 中的变量必须在白名单内才注入 |
| 继承 env | `HOME, LOGNAME, PATH, SHELL, TERM, USER` | `inheritedEnvironment(Set)` 可进一步收敛 |
| `maxServers` | 16 | 超限抛 IllegalStateException |
| `maxArguments` | 64 | 参数个数 |
| `maxArgumentChars` | 16,384 | 单参数字符数 |
| `maxFrameChars` | 1 MiB | 单协议帧上限 |
| `outputTailChars` | 65,536 | 输出尾部保留 |
| `maxLifetime` | 30 分钟 | 进程最长存活 |
| `stopTimeout` | 2s | 停止等待 |
| cwd | policy workspace | 解析真实路径后必须位于 workspace 内且为目录（防符号链接逃逸） |

### 运行时语义

- 相同规范化配置（SHA-256 cacheKey）并发首次调用只启动一个进程；进程终止自动移除缓存。
- stdout 仅接受换行分隔的 JSON-RPC，支持跨 chunk 响应和请求 ID 关联。
- 超时、取消、进程异常退出及关闭都会清理 pending 请求；工具取消不会隐式重放 `tools/call`。
- 注册为 `ContextualToolHandler`，透传取消信号；MCP 异常转为 `ToolResult.error`。
- `StdioMcpManager` 实现 `AutoCloseable` 并自动登记为 PluginManager 的 closeable：agent/worker 停机会关闭全部 MCP 子进程。
- 敏感 env 只保存在配置与进程内存中，不写入 state-dir、能力快照或日志。

## 服务端 MCP 注册管理

平台侧 MCP 服务器注册通过 `agent.mcpServers()`（`McpClient`）管理：

```java
agent.mcpServers().list(flowId, "cli");
agent.mcpServers().create(configJson);
agent.mcpServers().update(serverId, configJson);
agent.mcpServers().setEnabled(serverId, true);
agent.mcpServers().delete(serverId);
```

DigitalWorker 的受控子任务通过 `SubtaskMcpResolver` 按名称解析当前身份可用的 MCP 配置，见 [digital-worker.md](digital-worker.md)。