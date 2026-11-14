# 构建与单实例容器部署

DigitalWorker 以**单实例容器**形态运行：同一 workerId 只允许一个副本，更新使用 Recreate，进程退出由容器平台快速拉起。完整运维手册见 [单实例容器运维手册](single-instance-container.md)。

## 构建

### Maven

```bash
./mvnw clean compile                          # 编译
./mvnw test                                   # 全部测试
./mvnw package                                # 打包
./mvnw -pl taiyiflow-sdk-worker -am package   # 只打 worker（产出 *-all.jar 全量包（fat jar））
```

worker 模块使用 maven-shade-plugin 产出 classifier 为 `all` 的可执行全量包（fat jar），mainClass 为 `io.taiyiflow.sdk.worker.DigitalWorkerMain`。

### Make 目标

| 目标 | 行为 |
|------|------|
| `make test` | `./mvnw -B -ntp test` |
| `make build` | `./mvnw -B -ntp clean package` |
| `make worker-package` | 只打 worker 模块 |
| `make image` | 构建容器镜像（自动探测 docker/podman/nerdctl，可用 `DOCKER=` 指定） |
| `make docker` / `make package` / `make publish` | 委托 `snz1dpctl`（BUILD.yaml 描述 linux/amd64 与 linux/arm64 发布） |
| `make clean` | `./mvnw clean` |

可覆盖变量：`IMAGE`（默认 `taiyiflow-worker:local`）、`BUILD_IMAGE` / `RUNTIME_IMAGE`（默认 temurin 21-jdk / 21-jre）、`DOCKER_PLATFORM`。

### Dockerfile 要点

- 多阶段构建：build 阶段打包 worker 全量包（fat jar）；runtime 阶段仅 JRE。
- 非 root 系统用户 `taiyiflow` 运行。
- 预建目录：`/app/lib`（业务适配器）、`/config`、`/var/lib/taiyiflow/worker`、`/var/lib/taiyiflow/runtime-state`、`/var/lib/taiyiflow-credentials`。
- `ENV TAIYIFLOW_WORKER_CONFIG=/config/worker.yml`；`STOPSIGNAL SIGTERM`。
- ENTRYPOINT：`java -cp /app/worker.jar:/app/lib/* io.taiyiflow.sdk.worker.DigitalWorkerMain`。
- 默认 `INSTALL_CHROMIUM=false`，保持基础镜像精简；无头浏览器镜像显式执行 `docker build --build-arg INSTALL_CHROMIUM=true ...`，构建阶段按 Playwright 1.49.0 安装匹配 Chromium 与系统依赖。
- `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` 固定浏览器目录；运行期不静默下载，默认参数不包含 `--no-sandbox`。需要无头浏览器时使用预装镜像，不以启动时联网下载兜底。

## 本地运行

```bash
./mvnw -pl taiyiflow-sdk-worker -am package
java -cp 'taiyiflow-sdk-worker/target/*-all.jar:build/adapters/*' \
  io.taiyiflow.sdk.worker.DigitalWorkerMain --config deploy/worker.yml
```

### 配置定位优先级

1. `--config <worker.yml>`
2. 单个位置参数 `<worker.yml>`
3. 系统属性 `taiyiflow.worker.config`
4. 环境变量 `TAIYIFLOW_WORKER_CONFIG`
5. 默认 `worker.yml`（当前目录）

### 容器运行示例

```bash
docker run -d --name taiyiflow-worker \
  --restart always \
  -e TAIYIFLOW_API_KEY=${TAIYIFLOW_API_KEY} \
  -v taiyiflow-worker-state:/var/lib/taiyiflow/worker \
  -v taiyiflow-worker-runtime-state:/var/lib/taiyiflow/runtime-state \
  -v taiyiflow-worker-credentials:/var/lib/taiyiflow-credentials \
  -v $(pwd)/deploy/worker.yml:/config/worker.yml:ro \
  -v $(pwd)/build/adapters:/app/lib:ro \
  taiyiflow-worker:local
```

## worker.yml 完整键树

键名短横线命名（kebab-case）；Duration 用 ISO-8601（如 `PT15S`）；枚举按名称精确匹配；**未知键会导致绑定失败**。示例文件：[worker.yml](/worker.yml)（仓库内位于 `deploy/worker.yml`）。

```yaml
base-url: http://taiyiflow-platform:8080     # 必填
auth:                                         # 必填
  type: API_KEY                               # API_KEY | JWT | PERSISTENT_TOKEN
  token-secret: TAIYIFLOW_API_KEY             # 环境变量名（不是令牌本身！）
  # PERSISTENT_TOKEN 时：
  # device-id: backend-device-1               # 缺省回退顶层 device-id
  # credential-file: /var/lib/taiyiflow-credentials/credentials.json  # 必填
  # key-file: /var/lib/taiyiflow-credentials/encryption.key           # 必填
identity:                                     # 必填
  worker-id: backend-worker-1                 # 非空白 → 预置身份；否则需 extensions.worker-registrar
  display-name: Backend Digital Worker        # 注册路径必填
  role: COLLABORATION_WORKER                  # COLLABORATION_WORKER | PRIMARY_CLI
  abilities: []                               # 只声明已实现的能力，如 [shell, files, search]
workspace: /var/lib/taiyiflow/worker          # 必填
state-dir: /var/lib/taiyiflow/runtime-state   # 可选；缺省 → 内存发件箱（Outbox，重启丢失未确认记录）
client-id: ""                                 # 可选；缺省随机 UUID
device-id: ""                                 # 可选；缺省 auth.deviceId()
recovery-policy: AUTO                         # MANUAL | AUTO（默认 AUTO）
permission-policy: CONFIRM                    # AUTO | CONFIRM | READONLY（默认 CONFIRM）
approval:
  channel: PLATFORM                           # PLATFORM | LOCAL_CALLBACK（默认 PLATFORM）
execution:
  concurrency: 3                              # 默认 3，必须 > 0
  connect-timeout: PT15S                      # 默认 15s
  drain-timeout: PT30S                        # 默认 30s
  request-timeout: PT30S                      # 默认 30s
outbox:
  max-entries: 1000                           # 默认 1000
  max-entry-bytes: 1048576                    # 默认 1MiB
  max-total-bytes: 16777216                   # 默认 16MiB
  max-retries: 32                             # 默认 32
tools:
  builtin-tools: false                        # Shell/File/Search 默认不注册
  allowed-shell-commands: []                  # builtin-tools=true 时仍需精确白名单
  media: false                                # 文档/图片/音频/视频理解
  skills:
    enabled: false
    platform-enabled: false
    # workspace-dir: /var/lib/taiyiflow/skills # 缺省 <workspace>/.snz1dp/skills
    # user-dir: /var/lib/taiyiflow/user-skills # 仅显式配置才扫描
    # compat-dir: /var/lib/taiyiflow/compat-skills
    default-names: []
    bundled-resources: []                         # classpath 中具体的 SKILL.md 资源路径
    tolerate-platform-failure: true                # 默认 true；平台发现失败时保留其他来源
    sync-mode: DISABLED                       # 配置化运行时当前仅支持 DISABLED
  browser:
    mode: DISABLED                            # DISABLED | HEADLESS | REMOTE_CDP
    # executable-path: /opt/pw-browsers/chromium-1148/chrome-linux/chrome
    launch-arguments: []
    action-timeout: PT15S
    max-pages-per-session: 10
    max-read-text-characters: 30000
    max-console-messages: 200
    max-screenshot-bytes: 1048576
    execute-js-enabled: false
    allowed-hosts: []
    allowed-cidrs: []
    # remote-endpoint-secret: TAIYIFLOW_CDP_ENDPOINT       # Secret 名称，不是 URL
    # remote-authorization-secret: TAIYIFLOW_CDP_AUTHORIZATION
extensions:
  identity-validator: your.platform.PlatformWorkerIdentityValidator  # 必填类名
  # worker-registrar: your.platform.PlatformWorkerRegistrar          # 可选
  # task-handler: your.platform.BusinessTaskHandler                  # 可选
  # task-reporter: your.platform.PlatformTaskReporter                # 可选
  # approval-handler: your.platform.PlatformApprovalHandler          # 可选（PermissionHook）
  # approval-audit: your.platform.PlatformApprovalAudit              # 可选（LOCAL_CALLBACK 必需）
  # hooks: [your.platform.MetricsWorkerHook]                         # 可选列表
```

### extensions 加载机制

所有扩展类通过**当前类路径（classpath）反射 + public 无参构造**实例化（无 SPI/ServiceLoader、无 Spring 注入）：

- 业务适配器 JAR 挂载到 `/app/lib`，由 ENTRYPOINT 的 `-cp /app/worker.jar:/app/lib/*` 纳入类路径。
- `identity-validator` 必填；其余键非空白才加载；类型不匹配或加载失败抛 `Unable to load <Type>: <className>`。
- 密钥只配置环境变量名称（`token-secret`），真实令牌经环境变量/密钥（Secret）注入。

### 工具、技能与浏览器边界

- 内置工具、媒体理解、技能供应和浏览器均默认关闭；只启用当前 worker 实际使用的能力，并同步收敛 `identity.abilities` 与审批策略。
- 技能目录与 workspace/state/credentials 分离管理；首帧只发送逻辑相对目录和技能内容，不发送本机绝对路径。技能快照不写 command_payload 或长期结果库。
- HEADLESS 使用构建期预装的 Chromium；REMOTE_CDP 的 endpoint/Authorization 分别通过 `remote-endpoint-secret` / `remote-authorization-secret` 指向 Secret 名称。两种模式互斥。
- 浏览器白名单为空不等于开放公网；上线前必须配置精确主机和必要 CIDR。崩溃、断连、超时后只报告状态，不自动重放 click/type/navigate/upload/JS，也不接管已有用户标签或关闭外部 Chrome。

## 运行约束与恢复语义

- **单副本**：同一 workerId 只运行一个容器副本；编排用 Recreate 禁滚动；SDK 不做主备/选举/隔离（fencing）。
- **无 HTTP 端口**：不伪造健康端点；就绪以协同 `connection.ready` 与平台在线状态（presence）为准，存活/摘除/拉起由容器平台负责。
- **停机**：SIGTERM → 停机钩子（shutdown hook）停接新任务 → 按 drain-timeout 排空 → 关协同连接。
- **重启对账**：先重建连接再分页 `tasks.fetch_recent`；仅明确 pending 且未取消的任务自动执行；dispatched/acknowledged/running 与结果未知任务不重放。
- **目录隔离**：工作区（workspace）只放 instance_id、最小运行标识与临时文件；state-dir 只放瞬态补传发件箱（Outbox）；凭证加密文件与密钥放独立 credentials 卷，禁入 workspace/runtime-state/镜像。
- 跨容器恢复依赖平台权威关联/共享状态/幂等查询（P-RECOVERY、P-RESULT、P-APPROVAL 后端能力）；能力不足时保持 `REPORT_UNKNOWN` 人工对账，不自动启动新执行。

## 观测

经 `WorkerHook` 五回调（onReady/onTaskStart/onTaskFinish/onError/onStop）接日志、指标或追踪；建议记录 workerId、taskId、执行状态、回报未知数、重连失败次数；日志不得含令牌或完整业务结果。