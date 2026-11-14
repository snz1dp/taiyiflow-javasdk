# 技能（SKILL）配置与供应

技能用于向平台智能体提供领域说明、操作流程和附属文件。SDK 可以扫描本地或平台技能，在新执行轮次（run）的首帧发送 `client_skill_packages`，也可注册 `skill_*` 工具供智能体查询和维护技能。

技能供应与平台技能管理是两条不同能力面：

- `SkillSupplier` 负责发现、过滤、摘要/全文供应和恢复快照。
- `agent.skills()`（`SkillClient`）负责平台技能上传、内容和安装管理。
- `SkillTools` 是可选本地工具注册入口，不是技能首帧供应的前提。

Spring Boot Starter 和配置化 DigitalWorker 的技能供应默认关闭；编程式使用 `TaiyiAgent` 时，只有显式调用 `skillSupplier(...)` 才会自动供应。

## 1. 编写本地技能

文件系统供应器递归扫描配置根目录下的 `SKILL.md`：

```text
skills/
├── release-notes/
│   ├── SKILL.md
│   └── templates/
│       └── weekly.md
└── incident-response/
    └── SKILL.md
```

最小技能示例：

```markdown
---
name: release-notes
slug: release-notes
description: 按团队格式整理发布说明
category: documentation
version: 1.0.0
enabled: true
files:
  - path: templates/weekly.md
    description: 周报模板
prerequisites:
  - Git 提交历史
metadata:
  requires_env: [RELEASE_API_TOKEN]
---

# 发布说明编写规则

1. 按功能、修复和破坏性变更分组。
2. 不虚构提交记录中不存在的功能。
3. 使用 `templates/weekly.md` 的输出结构。
```

`SKILL.md` 必须以 YAML frontmatter 开头，且正文非空。`name` 必填，最长 200 字符；`slug` 缺省回退为 `name`。创建工具要求目录名、slug 和 frontmatter `name` 一致，slug 只能包含小写字母、数字和连字符。

| 字段 | 说明 |
|------|------|
| `name` / `slug` | 技能名称与稳定标识；查询时可按 name、slug 或相对目录匹配 |
| `description` / `homepage` / `category` / `version` | 描述、主页、分类和版本 |
| `enabled` | 默认 `true`；`false` 的技能不可见 |
| `prerequisites` | 前置条件说明，最多 32 项 |
| `files` | 允许 `skill_fetch` 读取的附属文件声明；字符串或 `{path, source, description}` 对象 |
| `metadata.requires_env` | 环境变量名数组；`skill_list` 只报告缺失变量名，不读取或输出变量值 |
| `metadata.request_type` / `request_types` | 限制技能适用的请求模式 |
| `metadata.tool_category` | 覆盖顶层 category；browser/chrome 分类在没有浏览器工具时会被过滤 |

文件声明只能使用安全的相对路径，不能是绝对路径、盘符路径、父目录逃逸或隐藏路径。文件系统扫描跳过隐藏目录、`node_modules` 和符号链接。默认最多发现 100 个技能，单个 `SKILL.md` 最大 1,000,000 字节，正文最大 200,000 字符；可通过 `SkillLimits` 收紧限额。

## 2. 来源与覆盖顺序

| 来源 | 配置方式 | 用途与写权限 |
|------|----------|--------------|
| `workspace` | `workspace-dir` 或 `.directory(path, WORKSPACE)` | 当前工作区技能；默认可由工具创建、上传和删除 |
| `user` | `user-dir` 或 `.directory(path, USER)` | 用户级技能；仅显式配置后扫描，可写 |
| `compat` | `compat-dir` 或 `.directory(path, COMPAT)` | 兼容既有技能目录；只读 |
| `bundled` | `bundled-resources` 或 `.bundled(classLoader, resources)` | 随应用分发的只读技能；每项是 classpath 中具体的 `SKILL.md` 资源路径，不是目录 |
| `platform` | `platform-enabled=true` 或 `.platform(skillClient)` | 平台技能；上传/删除需要配置平台客户端 |

发现顺序为 `workspace → user → compat → bundled → platform`。规范化 slug 相同的技能只保留第一个来源，因此高优先级来源会覆盖低优先级来源；不同 slug 的技能会合并供应。

Starter 的 workspace 技能目录缺省为 `<sandbox-root>/.snz1dp/skills`，DigitalWorker 缺省为 `<workspace>/.snz1dp/skills`。user 和 compat 目录只有显式配置才扫描。

## 3. 编程式配置与显式选择

以下示例只供应本地技能，并把 `team-guide` 作为默认全文技能：

```java
Path workspaceSkills = Path.of("/var/lib/taiyiflow/skills");
Path userSkills = Path.of("/var/lib/taiyiflow/user-skills");

SkillSupplier supplier = new SkillSupplier(
    SkillRegistry.builder()
        .directory(workspaceSkills, SkillSource.WORKSPACE)
        .directory(userSkills, SkillSource.USER)
        .bundled(getClass().getClassLoader(), List.of("skills/team-guide/SKILL.md"))
        .build(),
    SkillSupplyPolicy.localOnly());

TaiyiAgent agent = TaiyiAgent.builder()
    .baseUrl(baseUrl)
    .apiKey(apiKey)
    .flowId(flowId)
    .skillSupplier(supplier, List.of("team-guide"))
    .build();

ChatRequest request = new ChatRequest("按事故响应技能处理这个问题", sessionId);
request.setRequestedSkillNames(List.of("incident-response"));
session.chat(request);
```

`requested_skill_names` 是本轮显式选择。摘要/全文规则如下：

- `default-names` 和 `requested_skill_names` 命中的技能供应正文全文。
- bundled 技能始终供应全文。
- 其他可见技能只供应摘要；没有摘要时由正文生成，仍为空才回退描述。
- 显式传入 `client_skill_packages` 时，SDK 不重复自动供应，但仍执行技能包校验。

供应策略：

| 策略 | 本地供应 | 平台供应 | 平台发现失败 |
|------|----------|----------|--------------|
| `SkillSupplyPolicy.defaults()` | 开启 | 开启 | 容错 |
| `SkillSupplyPolicy.localOnly()` | 开启 | 关闭 | 不适用 |
| `SkillSupplyPolicy.disabled()` | 关闭 | 关闭 | 不适用 |
| `new SkillSupplyPolicy(true, true, false)` | 开启 | 开启 | 抛出异常 |

不能在关闭本地供应的同时单独开启平台供应。容错开启时，平台发现失败不会阻断其他来源，诊断信息记录为 `platform: discovery unavailable`；容错关闭时抛出 `IllegalStateException`。平台全文加载失败时，首帧供应回退摘要；`skill_fetch` 因必须取得正文而返回失败。

## 4. 技能工具

引入 `taiyiflow-sdk-plugins` 后，可额外注册五个工具：

```java
SkillManager manager = new SkillManager(workspaceSkills, userSkills, agent.skills());
BuiltinPlugins.registerSkills(
    agent.getPluginManager(), supplier, manager, List.of("team-guide"));
```

| 工具 | 职责 | 权限判定 |
|------|------|----------|
| `skill_list` | 列出当前会话可见技能、来源、摘要和缺失环境变量名 | 只读 |
| `skill_fetch` | 获取可见技能正文，或读取 frontmatter `files` 已声明的附件 | 只读 |
| `skill_create` | 在 workspace 或 user 范围创建本地技能 | 写操作 |
| `skill_upload` | 校验并打包可写本地技能，上传到平台 | 写操作 |
| `skill_delete` | 删除可写本地技能，或按平台技能 ID 删除平台技能 | 写操作 |

compat 和 bundled 技能不可修改或上传。工具仍经过统一的 `PermissionHook`、`ToolExecutionHook`、并发和取消链；默认 `READONLY` 策略会拒绝三个写工具。

只使用首帧技能供应时不必注册这些工具。反之，仅注册工具但没有配置供应器，也不会自动生成 `client_skill_packages`。

## 5. 配置化启用

Spring Boot：

```yaml
taiyiflow:
  skills:
    enabled: true
    platform-enabled: false
    workspace-dir: /var/lib/taiyiflow/skills
    user-dir: /var/lib/taiyiflow/user-skills
    default-names: [team-guide]
    bundled-resources: [skills/team-guide/SKILL.md]
    tolerate-platform-failure: true
```

DigitalWorker 使用 `tools.skills` 下的同名配置，另有 `sync-mode`；当前配置化运行时只接受 `DISABLED`，不会自动安装远端技能。完整配置树见 [Spring Boot 集成](spring-boot.md)和[构建与容器部署](deployment.md)。

## 6. 恢复、缓存与安全边界

- 新 start 首帧自动构造技能供应；attach/resume 复用本轮已保存的技能快照，不重新扫描目录或平台。
- 技能包只进入首帧协议与受控能力快照，不写入 `command_payload`、Outbox 或长期结果库。
- 平台正文缓存默认 5 分钟、最多 64 项；缓存键包含调用方 principal、flow、session、技能 ID 和版本，避免跨身份或会话混用。
- 首帧只发送逻辑相对目录和技能内容，不发送本机绝对路径。
- 技能正文和附件属于不可信提示内容，不会扩大 `available_tools`、审批、沙盒、网络、环境变量或子任务权限。

完整可运行示例见 `SkillSupplyExample` 与[示例程序](examples.md)。
