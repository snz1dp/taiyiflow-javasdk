# Spring Boot 集成

`taiyiflow-spring-boot-starter` 提供 TaiyiAgent 与 DigitalWorker 的自动装配（Spring Boot 3.2.x，autoconfigure 为 optional 依赖）。

## 引入依赖

```xml
<dependency>
    <groupId>io.taiyiflow</groupId>
    <artifactId>taiyiflow-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

自动装配注册三个配置类：`TaiyiFlowAutoConfiguration`、`TaiyiFlowBrowserAutoConfiguration` 与 `DigitalWorkerAutoConfiguration`。浏览器配置以 `@ConditionalOnClass` 隔离，未引入可选浏览器模块时不影响基础装配。

## TaiyiAgent 装配

**条件**：`taiyiflow.base-url`、`taiyiflow.api-key` 齐备即装配；`taiyiflow.flow-id` 可选，缺省时自动使用平台 `/config` 的 `default_flow_id`。产出 `TaiyiAgent` Bean（`destroyMethod="shutdown"`，`@ConditionalOnMissingBean` 可被自定义 Bean 覆盖）。

### 配置键（prefix = taiyiflow）

```yaml
taiyiflow:
  base-url: http://localhost:8080        # 必填（装配条件）
  api-key: ${TAIYIFLOW_API_KEY}          # 必填（装配条件）
  # flow-id: my-agent-flow               # 可选；缺省自动使用平台 /config 的 default_flow_id
  use-default-flow: false                # 可选显式开关；flow-id 缺省时无需开启
  connect-timeout: 10s
  request-timeout: 30s
  stream-idle-timeout: 1200s             # 流空闲看门狗；0 禁用空闲检测
  # tool-execution-timeout: 40m          # 工具期看门狗覆盖；缺省按注册工具自动推导（+60s，下限 31min）
  max-sessions: 100
  # max-session-concurrency: 3           # 可选；缺省跟随服务端 /config client_max_session_concurrency（0 阻止新对话）
  event-queue-capacity: 1024
  sse-fallback-enabled: true
  recovery-policy: MANUAL                # MANUAL / AUTO（注意：worker 段默认 AUTO）
  recovery-min-backoff: 2s
  recovery-max-backoff: 60s
  recovery-max-attempts: 8
  auto-restart-on-execution-loss: true
  permission-timeout: 300s               # 0 永不超时；非零范围 10–6000s；超时返回显式 timeout 结果（≠DENY）
  permission-policy: READONLY            # AUTO / CONFIRM / READONLY 三档兼容
  permission:                            # 细粒度权限（PermissionGate）；仅配置层可改
    rules: []                            # 优先级 deny > ask > allow；非法 action 归一 ask，无选择器条目丢弃
    #   - action: deny
    #     command-prefix: rm
    #   - action: ask
    #     tool-name: schedule_delete
    write-confirmation: confirm          # confirm | direct（file_edit / write_file）
    browser-permission-mode: ask         # ask | follow_a_plan | skip_all_permission_checks；非法值回落 ask
    categories: {}                       # shell | file | browser | skill | schedule → auto | confirm | readonly
  collaboration-enabled: false           # true 时 build 即建立协同 WS
  sandbox-root: /tmp/taiyiflow-sandbox   # 自动创建目录，同时作为会话 workspace
  allowed-shell-commands: [ls, cat, echo, date, whoami, pwd]
  request:                               # 首帧请求默认值；单次请求显式值始终优先
    input-type: chat                     # 会话默认 chat/chat（决策点 14）
    output-type: chat
    # client-type: device                # 首帧 client_type，默认 device；服务端按此合并内置技能
    # model: qwen-max                    # 须在平台合并后的 available_model_keys 范围内
    # reasoning: true
    # internet-search: true
    # knowledge-scopes: []               # 知识库范围 ID 列表
    # knowledge-docs: []                 # 知识文档 ID 列表
    auto-loop-enabled: false             # 默认关闭（决策点 10）
    # auto-loop-max-rounds: 6            # >= 0
    image-generation: AUTO               # AUTO（不下发）/ "ON" / "OFF"
    # group-id: <session-group-uuid>     # 新建会话自动归档到分组
    relation-sessions: []                # 关联会话 ID，最多 5 个
    # agent-app-id: <app-id>
    # agent-app-owner-id: <owner-uuid>
    # client-context-dir: <context-label>
    # long-term-memory-enabled: true     # 缺省不下发，由服务端默认决定
    # cross-session-memory-enabled: false
  instructions:
    directories: []                      # 显式目录才供应 client_instructions；默认关闭，不扫描 home
  plugins:
    shell: true                          # ShellPlugin（带命令白名单）
    files: true                          # FilePlugin
    search: true                         # SearchPlugin
    media: false                         # 文档/图片/音频/视频理解，默认关闭
    # shell-command-timeout: 30s         # 内置 shell 命令超时；缺省用插件默认 30s
  skills:
    enabled: false                       # 本地技能发现与首帧供应，默认关闭
    platform-enabled: false              # 平台技能发现/正文获取，默认关闭
    workspace-dir: /var/lib/taiyiflow/skills  # 缺省 <sandbox-root>/.snz1dp/skills
    # user-dir: /var/lib/taiyiflow/user-skills
    # compat-dir: /var/lib/taiyiflow/compat-skills
    default-names: []                    # 默认全文技能；其余发现项发摘要
    bundled-resources: []                # classpath 中具体的 SKILL.md 资源路径
    tolerate-platform-failure: true       # 平台发现失败时保留其他来源并记录诊断
  browser:
    mode: DISABLED                       # DISABLED / HEADLESS / REMOTE_CDP
    # executable-path: /opt/pw-browsers/chromium-1148/chrome-linux/chrome
    launch-arguments: []
    action-timeout: 15s
    max-pages-per-session: 10
    max-read-text-characters: 30000
    max-console-messages: 200
    max-screenshot-bytes: 1048576
    execute-js-enabled: false
    allowed-hosts: []
    allowed-cidrs: []
    # remote-endpoint: ${TAIYIFLOW_CDP_ENDPOINT}
    # remote-headers:
    #   Authorization: ${TAIYIFLOW_CDP_AUTHORIZATION}
```

`request` 只作用于新执行（start）首帧，attach/resume 不重发这些选项；retry/repeat 与执行丢失自动重启复用上一次执行的请求快照。`instructions.directories` 仅扫描显式目录内的 `CLAUDE.md`、`AGENTS.md`、`.github/copilot-instructions.md` 及对应 `.local.md` 变体（project 优先级 20、local 25），单文件 ≤256KB、总量 ≤1MB，超限跳过/截断；路径按配置目录相对披露，首帧不含本机绝对路径。`knowledge-scopes`/`knowledge-docs` 支持三层入口：`RequestDefaults.builder()`、worker.yml `request.knowledge-scopes/knowledge-docs` 与 starter `taiyiflow.request.knowledge-*`。`output_language` 在服务端无消费点，SDK 不设字段（如需透传可走 tweaks）。

`max-session-concurrency` 语义：显式配置优先于服务端 `/config` 的 `client_max_session_concurrency`（缺省 3）；`0` 阻止一切新对话；`/config` 拉取失败时回落 **-1 不限**（Electron 在 config 缺失时回落默认 3 收紧，Java SDK 选择不阻断后台任务，属文档化偏差）。配额只拦 `chat()` 新执行，resume/attach/retry/repeat 不受限。详见[执行控制](execution-control.md)。

浏览器需另行引入 `io.taiyiflow:taiyiflow-sdk-browser:1.0.0-SNAPSHOT`。HEADLESS 与 REMOTE_CDP 二选一；`allowed-hosts` / `allowed-cidrs` 是访问边界，不能用宽泛公网规则替代业务白名单。远程 endpoint 与 Authorization 推荐通过环境变量占位注入，配置元数据和日志不得输出实际值。技能只提供提示内容，不自动扩大 available_tools、审批、沙盒或网络权限；`skill_create/upload/delete` 属于写操作，仍走 PermissionHook。技能目录、frontmatter、来源覆盖、显式选择与供应语义见[技能配置与供应](skills.md)。

### 钩子（Hook）Bean 自动挂载

容器中的四类钩子 Bean 会按 `orderedStream()` 顺序自动挂到全局：

```java
@Bean
public StreamHook streamHook() {
    return new StreamHook() {
        @Override public void onChunk(String sessionId, String chunk) {
            log.info("[{}] {}", sessionId, chunk);
        }
    };
}

@Bean
public PermissionHook permissionHook() {
    return (sessionId, toolCall) ->
        CompletableFuture.completedFuture(PermissionHook.Decision.ALLOW);
}
```

### 注入使用

```java
@Service
public class MyService {
    private final TaiyiAgent agent;

    public MyService(TaiyiAgent agent) {
        this.agent = agent;
    }

    public void chat(String message) throws InterruptedException {
        AgentSession session = agent.newSession();
        session.onStream(new StreamHook() {
            @Override public void onChunk(String sessionId, String chunk) {
                System.out.print(chunk);
            }
        });
        session.chat(message);
        session.await();
    }
}
```

## DigitalWorker 装配

**条件**：`taiyiflow.worker.enabled=true` 且 classpath 存在 `DigitalWorker`。产出 `DigitalWorker` Bean（`destroyMethod="stop"`）与 `DigitalWorkerLifecycle`（SmartLifecycle，随容器启停）。

### 配置键（prefix = taiyiflow.worker）

```yaml
taiyiflow:
  worker:
    enabled: false                 # 装配开关
    auto-start: true               # false 只装配不连接，便于测试或自行控制生命周期
    worker-id: backend-worker-1    # 非空白 → 预置身份；为空则必须提供 WorkerRegistrar Bean
    display-name: 后端数字员工
    workspace: /var/lib/taiyiflow/worker
    state-dir: /var/lib/taiyiflow/runtime-state   # 空白按 null → 内存发件箱（Outbox）
    role: COLLABORATION_WORKER     # COLLABORATION_WORKER / PRIMARY_CLI
    abilities: []                  # 如 [shell, files, search]
    client-id: ""
    device-id: ""
    concurrency: 3
    connect-timeout: 15s
    drain-timeout: 30s
    stream-idle-timeout: 1200s     # 流空闲看门狗；0 禁用空闲检测
    # tool-execution-timeout: 40m  # 工具期看门狗覆盖；缺省按注册工具自动推导（+60s，下限 31min）
    # max-session-concurrency: 3   # 可选；缺省跟随服务端 /config client_max_session_concurrency（0 阻止新对话）
    builtin-tools: false           # Shell/File/Search 默认不注册
    allowed-shell-commands: []     # builtin-tools=true 时仍需精确白名单
    # shell-command-timeout: 30s   # 内置 shell 命令超时；缺省用插件默认 30s
    # subtask-max-tasks: 16        # run_subtask 本地单批上限
    # subtask-max-parallelism: 4   # 本地上限；服务端 max_parallelism_subtask 只收紧（0 禁用 run_subtask 供应）
    # subtask-timeout: 1h          # 单个子任务完成超时
    recovery-policy: AUTO          # worker 默认 AUTO
    permission-policy: CONFIRM
    permission:                    # 与 taiyiflow.permission 同结构（rules/write-confirmation/browser-permission-mode/categories）
      rules: []
      write-confirmation: confirm
      browser-permission-mode: ask
      categories: {}
    approval-channel: PLATFORM     # PLATFORM / LOCAL_CALLBACK；权限决策先经 PermissionGate，仅 ASK 进入审批通道
    request:                       # 与 taiyiflow.request 同语义（首帧默认值，chat/chat、auto-loop 默认关）
      # model: qwen-max
      # client-type: device        # 首帧 client_type，默认 device；服务端按此合并内置技能
      # knowledge-scopes: []
      # knowledge-docs: []
      auto-loop-enabled: false
    instructions:
      directories: []              # 显式目录才供应 client_instructions，默认关闭
```

注意：worker 段**没有** `request-timeout` 键（取 `taiyiflow.request-timeout`），也没有发件箱（outbox）限额键（用默认 1000/1MiB/16MiB/32）。

### 必须与可选 Bean

| Bean 类型 | 必须性 | 缺省行为 |
|-----------|--------|---------|
| `WorkerIdentityValidator` | **必须**（构建器硬校验） | 无则启动失败 |
| `WorkerAuth` | 可选 | 缺省 `WorkerAuth.apiKey(taiyiflow.api-key)`；可注册 JWT/persistentToken |
| `WorkerRegistrar` | worker-id 为空时必须 | 向第三方系统注册身份 |
| `WorkerTaskHandler` | 可选 | 内置 AgentSession 路由 |
| `TaskReporter` | 可选 | PlatformTaskReporter |
| `PermissionHook`（Bean 名 **`workerApprovalHandler`**） | 可选 | 平台审批适配器；必须用该名称/Qualifier，避免与普通 PermissionHook 混用 |
| `ApprovalAudit` | LOCAL_CALLBACK 时必须 | 本地决策的平台审计 |
| `WorkerHook` | 可选 | 全部按序追加 |

```java
@Bean
public WorkerIdentityValidator identityValidator() {
    return (identity, role, abilities) -> {
        // 对接第三方身份与访问管理（IAM）/ 注册中心校验 workerId 与认证主体的绑定
        if (!iamClient.isBound(identity.workerId())) {
            throw new IllegalStateException("workerId not bound: " + identity.workerId());
        }
    };
}

@Bean("workerApprovalHandler")
public PermissionHook workerApprovalHandler() {
    return (sessionId, toolCall) -> approvalService.decide(sessionId, toolCall);
}
```

### 生命周期

- `auto-start=true`（默认）：`DigitalWorkerLifecycle` 在 Spring 容器启动时 `worker.start()`，关闭时 `worker.stop()`。
- `auto-start=false`：只装配 Bean，不建立连接；由应用自行调用 start/stop。
- `DigitalWorker.stop()` 幂等，destroyMethod 与 SmartLifecycle 双兜底。

## 完整示例配置

```yaml
taiyiflow:
  base-url: http://localhost:8080
  api-key: ${TAIYIFLOW_API_KEY}
  flow-id: my-agent-flow
  sandbox-root: /tmp/taiyiflow-sandbox
  allowed-shell-commands: [ls, cat, echo]
  plugins:
    shell: true
    files: true
    search: true
  collaboration-enabled: false
  recovery-policy: MANUAL
  permission-timeout: 300s
  worker:
    enabled: true
    auto-start: true
    worker-id: backend-worker-1
    display-name: 后端数字员工
    workspace: /var/lib/taiyiflow/worker
    state-dir: /var/lib/taiyiflow/runtime-state
    concurrency: 3
    approval-channel: PLATFORM
```