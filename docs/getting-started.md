# 快速开始

本文帮助你在 5 分钟内跑通第一个 TaiyiFlow Java SDK 程序。

## 环境要求

| 项 | 要求 |
|----|------|
| JDK | 编译基线 Java 17；**运行需要 Java 21+**（会话与任务执行使用虚拟线程） |
| 构建 | 仓库自带 Maven Wrapper（`./mvnw`），无需本机安装 Maven |
| 后端 | 可访问的 TaiyiFlow 平台服务，以及有效的 API 密钥（API Key）与智能体标识（flowId） |

## 安装

### 方式一：源码构建

```bash
git clone <repo-url> taiyiflow-javasdk
cd taiyiflow-javasdk
./mvnw clean install -DskipTests
```

### 方式二：Maven 依赖

```xml
<!-- 核心：TaiyiAgent / AgentSession / Hook / 传输层 -->
<dependency>
    <groupId>io.taiyiflow</groupId>
    <artifactId>taiyiflow-sdk-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- 可选：本地工具插件（shell / file / search） -->
<dependency>
    <groupId>io.taiyiflow</groupId>
    <artifactId>taiyiflow-sdk-plugins</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- 可选：数字人运行时 -->
<dependency>
    <groupId>io.taiyiflow</groupId>
    <artifactId>taiyiflow-sdk-worker</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- 可选：Spring Boot 3 自动装配 -->
<dependency>
    <groupId>io.taiyiflow</groupId>
    <artifactId>taiyiflow-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

模块依赖方向：`taiyiflow-sdk-collaboration` 零 core 依赖、可独立使用；`core` 依赖 `collaboration`；`plugins`、`worker`、`starter` 依赖 `core`。

## 配置环境变量

示例程序统一读取以下环境变量：

```bash
export TAIYIFLOW_BASE_URL=http://localhost:8080   # 平台地址
export TAIYIFLOW_API_KEY=your-api-key             # API 密钥（API Key）
export TAIYIFLOW_FLOW_ID=your-flow-id             # 智能体标识（flow ID）
```

## 最简示例

```java
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.StreamHook;

public class Demo {
    public static void main(String[] args) throws Exception {
        // 1. 创建智能体实例（绑定一个 flow）
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(System.getenv("TAIYIFLOW_BASE_URL"))
                .apiKey(System.getenv("TAIYIFLOW_API_KEY"))
                .flowId(System.getenv("TAIYIFLOW_FLOW_ID"))
                .build();

        // 2. 创建会话并挂 StreamHook 接收流式回复
        AgentSession session = agent.newSession();
        session.onStream(new StreamHook() {
            @Override
            public void onChunk(String sessionId, String chunk) {
                System.out.print(chunk);
            }
        });

        // 3. 发送消息并等待完成
        session.chat("你好，请介绍一下你自己");
        session.await();
        agent.shutdown();
    }
}
```

说明：

- `chat()` 立即返回（返回 `this`，支持链式调用），回复通过 `StreamHook.onChunk` 增量推送；`await()` 阻塞到本轮执行结束。
- 不挂任何钩子（Hook）也能运行：消息流在内部缓冲，工具调用走默认权限策略。
- `TaiyiAgent` 默认**不注册任何本地工具插件**。若智能体需要在你本机执行 shell/文件/搜索工具，见下一节。
- 首次构建时若未显式指定 `clientId`，SDK 会在 `~/.taiyiflow/client-id` 生成并持久化一个客户端标识。

## 注册本地工具插件

```java
import io.taiyiflow.sdk.plugins.BuiltinPlugins;
import java.nio.file.Path;
import java.util.List;

// 全量注册：shell（含后台进程工具）+ file + search，共 13 个工具
BuiltinPlugins.registerAll(
        agent.getPluginManager(),
        Path.of("/tmp/taiyiflow-sandbox"),   // 沙盒根目录
        List.of("ls", "cat", "echo"));       // shell 命令白名单

// 或只读注册：search / grep / read_file / list_files，共 4 个工具
BuiltinPlugins.registerReadOnly(agent.getPluginManager(), Path.of("/tmp/taiyiflow-sandbox"));
```

安全默认：

- shell 白名单按**分词后第一个词元（token）精确匹配**，命令不经系统 shell 解析；白名单为空则拒绝一切命令。
- 文件与搜索工具的路径被限制在沙盒根目录内（含符号链接逃逸校验）。
- 默认权限策略 `PermissionPolicy.READONLY`：只读工具放行，写操作工具（shell、write_file、file_edit 等）直接拒绝。需要执行写操作时改用 `CONFIRM`（进入审批）或 `AUTO`（全放行），详见 [hooks.md](hooks.md)。

## 运行仓库示例

```bash
./mvnw -pl taiyiflow-sdk-examples -am compile exec:java \
    -Dexec.mainClass=io.taiyiflow.sdk.examples.MinimalExample
```

> 注意：examples 模块未配置默认 mainClass，必须通过 `-Dexec.mainClass` 指定示例类。

全部 7 个示例的说明见 [examples.md](examples.md)。

## 下一步

- 多会话并行、Builder 全量配置：[agent-and-session.md](agent-and-session.md)
- 工具接管与审批：[hooks.md](hooks.md)
- 让 Java 进程成为常驻数字员工：[digital-worker.md](digital-worker.md)
- Spring Boot 集成：[spring-boot.md](spring-boot.md)