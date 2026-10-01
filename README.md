# TaiyiFlow Java Agent SDK

> 面向 Java 后台的智能体能力 SDK——`TaiyiAgent` 实例可多会话并行执行，消息流实时输出给调用者，插件（工具）执行通过 Hook 与调用者交互。

## 定位

**一句话定位**：Java 后台程序作为调用者，主动驱动智能体并承接其全部能力。

| 维度 | JSSDK | Java SDK（本项目） |
|------|-------|-------------------|
| 场景 | 浏览器应用嵌入聊天窗口 | Java 后台程序主动驱动智能体 |
| 调用方 | 前端页面 | 服务端 / CLI / 微服务 |
| 扩展方式 | 插件注册 | **Hook 机制**（唯一扩展面） |
| 会话模型 | 单会话 | **多会话并行**（虚拟线程） |

### 核心原则

1. **客户端协同 WS 是可选插件**：默认不建立被动长连接；仅当需要"平台反向下发任务"时启用 `CollaborationPlugin`。
2. **多会话并行**：一个 `TaiyiAgent` 实例绑定一个智能体，内部 `SessionManager` 管理多个并发 `AgentSession`，会话间隔离、可各自中断。
3. **Hook 是唯一扩展面**：调用者按需挂 Hook，不挂则走内置默认行为。

### 不做的事

- UI / 前端渲染
- 语音 dialogue 链路
- LLM 编排（大脑仍在平台侧）

## 架构总览

```
taiyiflow-javasdk/
├── api/                          # 对外门面
│   ├── TaiyiAgent.java           # 智能体实例：builder 创建，绑定 flow + token
│   ├── AgentSession.java         # 一次会话执行：chat() 返回消息流，可中断
│   └── hooks/                    # Hook SPI（全部可选实现）
│       ├── StreamHook            # 消息流输出：onChunk/onEvent/onError/onClose
│       ├── ToolExecutionHook     # 工具执行拦截：before/after/takeover(接管)
│       ├── PermissionHook        # 敏感操作审批：onPermission(allow/deny/ask)
│       └── SessionHook           # 会话生命周期：onStart/onFinish/onInterrupt
├── core/
│   ├── session/                  # SessionManager：多会话并行（虚拟线程池+注册表）
│   ├── dispatch/                 # ToolDispatcher：工具调用统一入口，串接 Hook 链
│   ├── plugin/                   # Plugin SPI + PluginManager（内置/自定义工具注册）
│   ├── lifecycle/                # LifecycleManager：实例启停、优雅关闭
│   └── state/                    # 会话状态机（RUNNING/STREAMING/TOOL_CALLING/DONE）
├── transport/
│   ├── stream/                   # 流式运行（主通道）：WS 双向 + SSE 回退
│   ├── rest/                     # REST 客户端（会话/消息/技能/记忆查询）
│   └── envelope/                 # 消息封套模型 + 事件常量
├── capabilities/                 # 内置插件（默认注册，可移除）
│   ├── shell/                    # ShellPlugin：ProcessBuilder
│   ├── files/                    # FilePlugin：读写/细粒度编辑
│   ├── search/                   # SearchPlugin：文本/正则搜索
│   ├── skills/                   # SkillPlugin：技能注册与同步
│   └── tools/                    # 自定义 ToolHandler 注册入口
├── collaboration/                # 可选插件（默认不启用）
│   ├── CollaborationPlugin       # 协同 WS 被动模式：接收平台下发任务
│   └── SchedulePlugin            # 定时任务回调（依赖协同连接）
└── spring-boot-starter/          # 自动装配：逐插件/Hook 开关
```

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+
- 可访问的 TaiyiFlow 后端服务

### 最简示例（3 行代码跑通流式对话）

```java
// 1. 创建智能体实例
TaiyiAgent agent = TaiyiAgent.builder()
    .baseUrl("http://localhost:8080")
    .token("your-api-token")
    .flowId("my-agent-flow")
    .build();

// 2. 发起会话
AgentSession session = agent.newSession();

// 3. 发送消息，通过 StreamHook 接收流式回复
session.chat("你好，请介绍一下你自己", new StreamHook() {
    @Override
    public void onChunk(String text) {
        System.out.print(text);
    }
});
```

### 挂 Hook 定制行为

```java
TaiyiAgent agent = TaiyiAgent.builder()
    .baseUrl("http://localhost:8080")
    .token("your-api-token")
    .flowId("my-agent-flow")
    // 工具执行拦截：接管特定工具的执行
    .toolExecutionHook(new ToolExecutionHook() {
        @Override
        public Optional<ToolResult> takeover(ToolCall call) {
            if ("custom_tool".equals(call.name())) {
                return Optional.of(myCustomExecutor.execute(call));
            }
            return Optional.empty(); // 走内置插件
        }
    })
    // 权限审批：拦截写操作
    .permissionHook(call -> {
        if (isWriteOperation(call)) {
            return PermissionDecision.ask(); // 向调用者请求审批
        }
        return PermissionDecision.allow();
    })
    .build();
```

### Spring Boot 集成

```xml
<dependency>
    <groupId>com.taiyiflow</groupId>
    <artifactId>taiyiflow-java-sdk-spring-boot-starter</artifactId>
    <version>${taiyiflow.version}</version>
</dependency>
```

```yaml
# application.yml
taiyiflow:
  base-url: http://localhost:8080
  token: ${TAIYIFLOW_TOKEN}
  flow-id: my-agent-flow
  plugins:
    shell:
      enabled: true
      working-directory: /tmp/sandbox
    file:
      enabled: true
    search:
      enabled: true
  collaboration:
    enabled: false  # 默认关闭
```

## Hook 体系

所有扩展点收敛为 Hook 机制，调用者按需挂载：

| Hook | 接口方法 | 默认行为（不挂时） |
|------|---------|-------------------|
| `StreamHook` | `onChunk` / `onEvent` / `onError` / `onClose` | 消息流内部缓冲，`chat()` 返回完整结果 |
| `ToolExecutionHook` | `before` / `after` / `takeover` | 内置插件直接执行 |
| `PermissionHook` | `onPermission` → allow / deny / ask | 只读放行，写操作拦截 |
| `SessionHook` | `onStart` / `onFinish` / `onInterrupt` | 无额外行为 |

### Hook 执行顺序（工具调用场景）

```
智能体发起工具调用
    │
    ▼
PermissionHook.onPermission()
    │ allow
    ▼
ToolExecutionHook.before()
    │
    ▼
ToolExecutionHook.takeover() ──→ 非空？──→ 返回调用者结果
    │ empty                              │
    ▼                                    │
内置插件执行                              │
    │                                    │
    ▼                                    ▼
ToolExecutionHook.after() ◄──────────────┘
    │
    ▼
结果回传智能体
```

## 传输协议

### 流式运行（主通道）

- **协议**：WebSocket 双向（`WS /api/v1/run/{flow_id}/websocket`）
- **首条消息**：连接后 10s 内发送 `{input_value, session_id, tweaks}`
- **服务端推送**：`{"event": "message|heartbeat|error|close|custom", "data": {...}}`
- **关闭码**：1000（正常）/ 4001 / 4002 / 4003 / 4004 / 4009
- **SSE 回退**：`POST /run/{id}/stream`

### 多会话并行

一个 `TaiyiAgent` 实例可并发多个 `AgentSession`，每个会话对应一条独立 WS 连接（独立 `session_id`），基于虚拟线程实现，会话间完全隔离。

### REST 辅助面

- `/agents/*`：会话/消息/技能/记忆查询
- `/client-collaboration-manager`：协同管理（可选）
- `/client-schedule-manager`：定时任务管理（可选）
- `/config`：配置查询

## 内置插件

| 插件 | 功能 | 默认状态 |
|------|------|---------|
| `ShellPlugin` | 执行 Shell 命令（ProcessBuilder） | 启用（沙盒目录） |
| `FilePlugin` | 文件读写、细粒度编辑 | 启用 |
| `SearchPlugin` | 文本/正则搜索 | 启用 |
| `SkillPlugin` | 技能注册与同步 | 启用 |
| `CustomToolHandler` | 自定义工具注册入口 | 按需 |

## 可选插件

| 插件 | 功能 | 启用方式 |
|------|------|---------|
| `CollaborationPlugin` | 接收平台反向下发任务（无头 CLI 模式） | `agent.enableCollaboration()` |
| `SchedulePlugin` | 定时任务回调 | 依赖 CollaborationPlugin |

## 技术选型

| 维度 | 选择 | 理由 |
|------|------|------|
| Java 版本 | 17+ | 虚拟线程（Loom）支持 |
| 构建 | Maven 多模块 | 标准企业级 |
| JSON | Jackson | 生态成熟 |
| WebSocket | `java.net.http.WebSocket` | JDK 内置，零额外依赖 |
| 并行 | 虚拟线程 | 高并发会话，低资源开销 |
| 消息流 | 简单回调 + 可选 `Flow` 适配器 | 不引入 Reactive Streams 依赖 |

## 开发计划

| 阶段 | 内容 | 依赖 |
|------|------|------|
| **Phase A** | Hook SPI 与协议模型冻结；核实工具调用回调通道 | — |
| **Phase B** | 传输层：流式 WS + REST 客户端 | A |
| **Phase C** | 内核：TaiyiAgent / SessionManager / ToolDispatcher / PluginManager | B |
| **Phase D** | 内置插件与 Hook 语义落地 | C |
| **Phase E** | 可选协同插件（CollaborationPlugin + SchedulePlugin） | C（与 D 并行） |
| **Phase F** | Spring Boot Starter、示例与文档 | D/E |

## 验证策略

1. **协议单测**：封套解析、事件常量与后端字符串全量对照、超时与关闭码映射
2. **多会话并行测试**：并发 N 个会话，验证隔离、独立中断、资源回收
3. **Hook 链测试**：零 Hook 默认路径 / StreamHook 全量事件 / ToolExecutionHook takeover / PermissionHook deny
4. **工具回调集成测试**：对接本地后端，跑通全链路
5. **协同插件测试**：启用后接收/执行/回报；未启用时无被动连接
6. **开箱即用验收**：minimal 示例仅填 baseUrl/token/flowId 即运行

## 项目结构

```
taiyiflow-javasdk/
├── pom.xml
├── taiyiflow-sdk-api/
├── taiyiflow-sdk-core/
├── taiyiflow-sdk-transport/
├── taiyiflow-sdk-capabilities/
├── taiyiflow-sdk-collaboration/
├── taiyiflow-sdk-spring-boot-starter/
├── examples/
│   ├── minimal/
│   ├── tool-takeover/
│   └── collaboration/
└── .github/
    └── prompts/
```

## License

TBD
