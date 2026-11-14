# 数字人单实例容器部署

## 运行约束

- 同一 workerId 只运行一个容器副本。编排系统使用 `Recreate` 更新策略，禁止滚动更新造成新旧副本并存。
- 容器平台负责进程存活检测、故障摘除和快速拉起；平台协同在线状态（presence）心跳用于判断在线状态。SDK 不实现主备、选举、租约或隔离（fencing）。
- 工作区（workspace）使用持久卷，但只保存 instance_id、最小运行标识和临时文件。平台任务、执行轮次/检查点（run/checkpoint）、结果、记忆、产物和审批记录才是权威状态。
- `state-dir` 使用独立的 `/var/lib/taiyiflow/runtime-state` 持久卷，只保存待补传的工具结果与检查点瞬态补传发件箱（Outbox）；补传不会重新执行工具。未配置 `state-dir` 时使用非持久内存发件箱，不适用于需要重启补传的容器部署。
- 凭证通过容器密钥（Secret）注入环境变量。持久令牌的加密文件与密钥放在独立的 `/var/lib/taiyiflow-credentials` 卷，不得放入工作区、runtime-state 或镜像。

## 构建与启动

由业务接入方实现第三方身份校验适配器 `WorkerIdentityValidator`，对接其身份与访问管理（IAM）、worker 注册中心或其他权威身份系统；workerId 及认证主体、角色、能力绑定由该第三方实现登记和治理，智能体 Agent 后台不承担 workerId 登记。按需提供 `WorkerTaskHandler`、`PermissionHook`、`ApprovalAudit` 和 `WorkerHook`。将业务适配器及依赖打包后放入 `/app/lib`，并在 `worker.yml` 的 `extensions` 中配置类名。身份校验适配器不可使用空实现。

```bash
docker build -t taiyiflow-worker:local .
docker run --rm \
  -e TAIYIFLOW_API_KEY \
  -v taiyiflow-worker-state:/var/lib/taiyiflow/worker \
  -v taiyiflow-worker-runtime-state:/var/lib/taiyiflow/runtime-state \
  -v taiyiflow-worker-credentials:/var/lib/taiyiflow-credentials \
  -v "$PWD/deploy/worker.yml:/config/worker.yml:ro" \
  -v "$PWD/build/adapters:/app/lib:ro" \
  taiyiflow-worker:local
```

`TAIYIFLOW_API_KEY` 应由编排系统的密钥（Secret）引用注入，不要写入命令历史或 `worker.yml`。本地直接运行时可执行：

```bash
./mvnw -pl taiyiflow-sdk-worker -am package
java -cp 'taiyiflow-sdk-worker/target/*-all.jar:build/adapters/*' \
  io.taiyiflow.sdk.worker.DigitalWorkerMain --config deploy/worker.yml
```

## 心跳、停机与重启

1. 配置容器平台在进程退出时立即重启，并保留单个副本。应用未监听 HTTP 端口，不用伪造 HTTP 健康端点；在线就绪状态以协同 `connection.ready` 和平台在线状态（presence）为准。
2. 停止时发送 SIGTERM。`DigitalWorkerMain` 的停机钩子（shutdown hook）停止接新任务，按 `execution.drain-timeout` 等待在途任务，然后关闭协同连接。
3. 重启后 SDK 先重建协同连接，再分页执行 `tasks.fetch_recent`。只有明确 `pending` 且未取消的任务会自动执行；`dispatched/acknowledged/running` 和结果未知任务不会重放。
4. 进程内的活动会话优先重接（attach）/恢复（resume）。跨容器恢复需要平台提供 taskId 与 session/run/checkpoint 的权威关联、跨实例共享状态和幂等结果查询；能力不足时保持 `REPORT_UNKNOWN` 并人工对账，不自动启动新执行。
5. 容器快速拉起不等于任务必然不丢不重。P-RECOVERY、P-RESULT、P-APPROVAL 的真实后端能力与联调仍是可靠恢复和平台审批的前置条件。

## 观测

通过 `WorkerHook.onReady/onTaskStart/onTaskFinish/onError/onStop` 接日志、指标或追踪系统。钩子（Hook）异常会被隔离，不改变任务执行结果。建议至少记录 workerId、taskId、执行状态、回报未知数量和重连失败次数，不在日志中记录令牌或完整业务结果。
