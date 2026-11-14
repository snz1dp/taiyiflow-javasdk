# 示例程序

`taiyiflow-sdk-examples` 模块包含 12 个可运行示例。

## 运行方式

examples 模块未配置默认 mainClass，需显式指定：

```bash
export TAIYIFLOW_BASE_URL=http://localhost:8080
export TAIYIFLOW_API_KEY=your-api-key
export TAIYIFLOW_FLOW_ID=your-flow-id  # 可选；缺省使用服务端默认 Flow

./mvnw -pl taiyiflow-sdk-examples -am compile exec:java \
    -Dexec.mainClass=io.taiyiflow.sdk.examples.<类名>
```

`TAIYIFLOW_BASE_URL` 和 `TAIYIFLOW_API_KEY` 必填；`TAIYIFLOW_FLOW_ID` 仅用于显式覆盖服务端默认 Flow。下表“三个标准变量”均按此规则理解。

## 示例清单

| 类名 | 演示内容 | 环境变量 |
|------|---------|---------|
| `MinimalExample` | 最小三步流式对话：builder → newSession + StreamHook → chat/await/shutdown | BASE_URL / API_KEY 必填；FLOW_ID 可选 |
| `MultiSessionExample` | 多会话并行：maxSessions(10)，3 个会话并发提问，CountDownLatch 等待全部结束 | 三个标准变量 |
| `ToolTakeoverExample` | 工具接管与权限钩子：takeover 拦截 `get_weather` 返回模拟天气；PermissionHook 对 shell/bash 返回 DENY | 三个标准变量 |
| `PauseResumeExample` | 暂停/恢复：首个增量块（chunk）到达后 pause()，再 resume() 并 await(120s) 验证续跑完成 | 三个标准变量 |
| `RetryRepeatExample` | 重试与重放：chat → await → retry() → await → repeat() → await | 三个标准变量 |
| `LocalApprovalExample` | 本地人工审批：PermissionPolicy.CONFIRM + 5 分钟审批超时，监听客户端工具（client tool）事件后从标准输入（stdin）读 approve/deny | 三个标准变量 |
| `CollaborationExample` | 无头 CLI 协同模式：WorkerAuth + TaskExecutor + PlatformTaskReporter，CollaborationPlugin 被动收任务并处理 release 推送，主线程常驻 | BASE_URL / API_KEY（**不需要 FLOW_ID**） |
| `FlowMetadataExample` | 获取 flow/agent 元数据、能力特性与知识库 | 三个标准变量 |
| `MediaUnderstandingExample` | 注册媒体理解工具，由服务端智能体按任务选择文档/图片/音频/视频分析 | 三个标准变量 + `TAIYIFLOW_MEDIA_PATH` |
| `HeadlessBrowserExample` | 无头 Chromium 新建页面并读取内容 | 三个标准变量 + `TAIYIFLOW_BROWSER_HOST` |
| `RemoteCdpBrowserExample` | 连接远程 CDP，只操作本会话新建页面 | 三个标准变量 + `TAIYIFLOW_CDP_ENDPOINT` / `TAIYIFLOW_BROWSER_HOST`，可选 `TAIYIFLOW_CDP_AUTHORIZATION` |
| `SkillSupplyExample` | 本地技能发现、全文/摘要供应、`skill_fetch` 与首帧显式选择 | 三个标准变量 + `TAIYIFLOW_SKILL_DIR` / `TAIYIFLOW_SKILL_NAME` |

## 各示例要点

### MinimalExample

零钩子（Hook）的最小配置即可跑通"发消息 → 收流式回复"。对应文档：[getting-started.md](getting-started.md)。

### MultiSessionExample

验证一个 TaiyiAgent 实例下多会话隔离与并行：每个会话独立 session_id、独立流连接，互不阻塞。对应文档：[agent-and-session.md](agent-and-session.md)。

### ToolTakeoverExample

```java
.onToolExecution(new ToolExecutionHook() {
    @Override
    public ToolResult takeover(String sessionId, ToolCall toolCall) {
        if ("get_weather".equals(toolCall.getName())) {
            return ToolResult.success(toolCall.getId(), weatherJson);
        }
        return null; // 其他工具走内置插件
    }
})
.onPermission((sessionId, toolCall) -> CompletableFuture.completedFuture(
    "shell".equals(toolCall.getName()) || "bash".equals(toolCall.getName())
        ? Decision.DENY : Decision.ALLOW))
```

对应文档：[hooks.md](hooks.md)。

### PauseResumeExample / RetryRepeatExample

演示服务端执行控制与检查点恢复、新 run 重试/重放的差异。对应文档：[execution-control.md](execution-control.md)。

### LocalApprovalExample

演示 ASK 审批的完整闭环：策略 CONFIRM → 工具进入等待 → 人工从 stdin 裁决 → `session.approve/deny`。对应文档：[hooks.md](hooks.md)。

### CollaborationExample

演示不依赖 TaiyiAgent 门面的底层协同链路：CollaborationPlugin 收任务 → TaskExecutor 幂等执行（执行中状态前置回报）→ PlatformTaskReporter 平台回报。对应文档：[collaboration.md](collaboration.md)、[digital-worker.md](digital-worker.md)。

### FlowMetadataExample

读取 `/api/v1/run/{flowIdOrName}/metadata`，展示名称、所有者、features 和知识库。元数据用于能力判断，不会自动开启本地工具或提升权限。

### MediaUnderstandingExample

媒体工具默认不注册；示例显式调用 `BuiltinPlugins.registerMedia(...)`，把文件分析交给平台智能体决策。路径必须是当前进程可访问且符合沙盒/权限策略的输入。

### HeadlessBrowserExample / RemoteCdpBrowserExample

浏览器示例只允许 `TAIYIFLOW_BROWSER_HOST`，默认禁止 JavaScript 执行。无头模式需要已安装且与 Playwright 1.49.0 匹配的 Chromium；远程模式不枚举、不接管已有用户标签，也不关闭外部浏览器。两种模式都不会在崩溃、断连或超时后自动重放有副作用操作。

### SkillSupplyExample

`TAIYIFLOW_SKILL_DIR` 指向包含 `<skill>/SKILL.md` 的目录。示例先发现并按名取回正文，再通过 `requested_skill_names` 显式选择技能发起会话。首帧不包含本机绝对路径；技能正文不扩大工具或权限范围。完整配置说明见[技能配置与供应](skills.md)。

## 数字人配置化启动

完整的 DigitalWorker 容器入口示例不在 examples 模块，而是 worker 模块的 `DigitalWorkerMain` + [worker.yml](/worker.yml)（仓库内位于 `deploy/worker.yml`）：

```bash
./mvnw -pl taiyiflow-sdk-worker -am package
java -cp 'taiyiflow-sdk-worker/target/*-all.jar:build/adapters/*' \
  io.taiyiflow.sdk.worker.DigitalWorkerMain --config deploy/worker.yml
```

见 [deployment.md](deployment.md)。