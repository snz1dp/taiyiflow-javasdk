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

自动装配注册了两个配置类：`TaiyiFlowAutoConfiguration` 与 `DigitalWorkerAutoConfiguration`。

## TaiyiAgent 装配

**条件**：`taiyiflow.base-url`、`taiyiflow.api-key`、`taiyiflow.flow-id` 三键齐备才装配；产出 `TaiyiAgent` Bean（`destroyMethod="shutdown"`，`@ConditionalOnMissingBean` 可被自定义 Bean 覆盖）。

### 配置键（prefix = taiyiflow）

```yaml
taiyiflow:
  base-url: http://localhost:8080        # 必填（装配条件）
  api-key: ${TAIYIFLOW_API_KEY}          # 必填（装配条件）
  flow-id: my-agent-flow                 # 必填（装配条件）
  connect-timeout: 10s
  request-timeout: 30s
  max-sessions: 100
  event-queue-capacity: 1024
  sse-fallback-enabled: true
  recovery-policy: MANUAL                # MANUAL / AUTO（注意：worker 段默认 AUTO）
  recovery-min-backoff: 2s
  recovery-max-backoff: 60s
  recovery-max-attempts: 8
  auto-restart-on-execution-loss: true
  permission-timeout: 300s               # 0 永不超时；非零范围 10–6000s
  collaboration-enabled: false           # true 时 build 即建立协同 WS
  sandbox-root: /tmp/taiyiflow-sandbox   # 自动创建目录
  allowed-shell-commands: [ls, cat, echo, date, whoami, pwd]
  plugins:
    shell: true                          # ShellPlugin（带命令白名单）
    files: true                          # FilePlugin
    search: true                         # SearchPlugin
```

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
    recovery-policy: AUTO          # worker 默认 AUTO
    permission-policy: CONFIRM
    approval-channel: PLATFORM     # PLATFORM / LOCAL_CALLBACK
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