# 内置工具插件

`taiyiflow-sdk-plugins` 提供本地工具执行能力。所有插件通过 `PluginManager` 注册，工具调用统一经过权限、钩子（Hook）、并发调度与取消链。

> `TaiyiAgent` 默认不注册任何插件；Spring Boot 启动器（Starter）按 `taiyiflow.plugins.*` 开关自动注册。

## 注册入口

```java
// 全量：Shell 13 个 + File 4 个 + Search 4 个，共 21 个工具
BuiltinPlugins.registerAll(PluginManager pm, Path sandboxRoot, List<String> allowedShellCommands);

// 只读：search / grep / glob_files / search_files / read_file / list_files，共 6 个工具
BuiltinPlugins.registerReadOnly(PluginManager pm, Path sandboxRoot);
```

也可单独注册：`ShellPlugin.register(pm, dir, whitelist)`、`FilePlugin.register(pm, root)`、`SearchPlugin.register(pm, root)`。注册时会同时把插件实例登记为 closeable，`agent.shutdown()` 时自动清理。

## ShellPlugin

### 构造

```java
new ShellPlugin(Path workingDirectory, List<String> allowedCommands);                    // 超时 30s
new ShellPlugin(Path workingDirectory, List<String> allowedCommands, long timeoutSeconds);
new ShellPlugin(Path workingDirectory, List<String> allowedCommands, long timeoutSeconds,
                WorkspaceAudit.Limits auditLimits);
new ShellPlugin(Path workingDirectory, List<String> allowedCommands, long timeoutSeconds,
                WorkspaceAudit workspaceAudit);
```

- `workingDirectory` 为 null 时回退进程当前目录。
- `allowedCommands` 为 null/空 → 拒绝一切命令。
- 前台输出上限 1,000,000 字符，超出截断并置 `outputTruncated=true`。
- 超时（默认 30s）强杀进程及全部子孙进程，返回 `timedOut=true`。

### 工具：shell / bash / execute_command / shell_exec（四个别名，同一处理器）

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `command` | string | 是 | — | 自定义分词器（tokenizer）解析引号/转义，**不经系统 shell**；未闭合引号报错 |
| `cwd` | string | 否 | 构造时工作目录 | 相对路径基于工作目录解析；normalize + realpath 双重校验不得逃逸沙盒 |
| `background` | boolean | 否 | false | true 时立即返回 `started=true` 与 `process_handle` |
| `scan_workspace` / `scanWorkspace` | boolean | 否 | 自动判定 | 是否做工作区变更审计扫描 |
| `fileOperations` / `file_operations` | array | 否 | 空 | 声明式文件操作：`type`、`path` 必填，`newPath`、`is_artifact`（默认 true）可选 |

**白名单校验**：对分词后的第一个词元（token）精确匹配（不做路径归一化），不在白名单返回 `Command not in whitelist: ...`。

**自动扫描判定**：解释器命令（python/python3/node/deno/bun/bash/sh/zsh）自动扫描；构建命令（npm/yarn/pnpm/mvn/mvnw/gradle/gradlew/cargo/go/make）且子命令含 run/build/install/test/generate/compile/package 之一时自动扫描。

前台返回：`{output, outputTruncated, exitCode}`，附 `file_operations`（声明 + 扫描合并去重）与 `workspace_audit` 节点。

### 后台进程工具

`background: true` 返回 SDK 私有句柄 `proc_<UUID>`。句柄只属于当前插件实例和会话，**不是平台 taskId 或 OS PID**。

| 工具 | 参数 | 行为 |
|------|------|------|
| `process_list` | 无 | 仅列出当前会话的进程 |
| `process_output` | `process_handle`（别名 `handle`，必填）、`stdout_offset`（默认 0）、`stderr_offset`（默认 0） | 按流增量返回 `{data, next_offset, dropped_chars, offset_reset}` |
| `process_stop` | `process_handle` 必填 | 停止注册表内匹配的进程树，返回 `stop_requested=true` |
| `process_cleanup` | `process_handle` 可选 | 缺省清理本会话全部已结束进程；指定句柄但仍在运行则报错 |

兼容别名：`task_list/task_output/task_stop/task_delete/task_cleanup` 分别复用进程列举、输出、停止和清理处理器，不代表平台任务。

限制与语义（默认值）：

- 最多 **64** 个后台进程；满时先驱逐最旧的已结束进程，仍满则拒绝新进程。
- 标准输出/标准错误（stdout/stderr）各自保留 **65,536 字符尾部环形缓冲**，达到上限仍持续 draining；读取偏移落在已丢弃区间时 `offset_reset=true`。
- 停止时先对子孙进程逆序 destroy，优雅等待 **2s** 后 destroyForcibly。
- 跨会话、未知或进程重启后的旧句柄返回 `status=lost, error=unknown_or_lost`；不会按旧 PID kill，也不会重启原命令。
- 会话取消、agent/worker 停机会清理后台进程；暂停/恢复不会终止它们。
- 进程表仅存内存，不写结果补传发件箱（Outbox）；启用工作区扫描时进程退出后补扫产物。
- 进程状态：`running` / `stopping` / `stopped` / `exited`。

进程信息字段：`process_handle, status, command[], cwd, session_id, run_id?, task_id?, created_at, ended_at?, exit_code`。

## FilePlugin

```java
new FilePlugin(Path sandboxRoot);
```

| 工具 | 参数 | 返回 |
|------|------|------|
| `read_file` | `path`（必填） | `{path, content, size, sha256}`；严格 UTF-8，非法字节报错 |
| `write_file` | `path`、`content`（必填） | `{path, written, size, sha256_after}`；自动创建父目录 |
| `file_edit` | 见下 | 成功 `status=applied` + 变更统计；冲突 `PATCH_CONFLICT/<reason>` |
| `list_files` | `path`（默认 `.`） | `{path, files:[{name, directory, size}]}`，仅一层 |

### file_edit 参数

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `path` | string | 是 | — | 目标必须已存在 |
| `old_string` | string | 是 | — | 不得为空串 |
| `new_string` | string | 是 | — | 可为空串（删除语义） |
| `expected_sha256_before` | string | 否 | — | 乐观并发校验（忽略大小写） |
| `dry_run` | boolean | 否 | false | 只预演不落盘 |
| `replace_all` | boolean | 否 | false | 多处匹配时必须显式置 true |

### PATCH_CONFLICT 冲突协议

失败返回 error `PATCH_CONFLICT/<reason>`：

| reason | 含义 |
|--------|------|
| `STALE_CONTENT` | 哈希不符或提交前文件被修改 |
| `MATCH_NOT_FOUND` | old_string 未命中 |
| `AMBIGUOUS_MATCH` | 多处匹配且未 replace_all |

诊断体含 `code, reason, status, path, sha256_before, occurrences, replacements=0, dry_run, preview_before`（old_string 截断 160 字符）等，可据此重试。支持 CRLF/LF 换行自适应匹配（命中时附 `normalized_old_string_match=true`）。

### 路径沙盒与原子写

- 读：normalize + startsWith 校验后再 realpath 二次校验。
- 写：祖先目录 realpath 校验 → 创建父目录 → 复检 → **拒绝符号链接目标**；原子写前后复检父目录未漂移。
- 原子写：同目录私有临时文件 `.taiyiflow-*.tmp`（POSIX `rw-------`）→ fsync → 保留原文件权限 → `ATOMIC_MOVE` 替换；不支持原子移动时抛 IOException 且目标不被修改。
- 并发：按路径加锁，编辑提交前再做 SHA-256 复核。

## SearchPlugin

```java
new SearchPlugin(Path sandboxRoot);
```

工具 `search` 与 `grep`（同一处理器）：

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `pattern` | string | 是 | — | 搜索模式 |
| `regex` | boolean | 否 | false | false 按字面量，true 编译为正则 |
| `path` | string | 否 | `.` | 搜索根目录（沙盒内） |

返回 `{matches:[{file, line, content}], totalMatches, truncated}`；最多 **100** 条，达到即截断；仅遍历常规文件（不跟随符号链接），不可读文件静默跳过。

`glob_files` 按 glob 匹配相对路径：`pattern` 默认 `**/*`、`path` 默认 `.`，返回 `{files, count, truncated}`，最多 1000 个排序后的常规文件。

### search_files（Electron 契约别名）

与 Electron `file-tools.js` 的 `search_files` schema 对齐，供同一 Flow 下按 Electron schema 生成参数的模型直接调用；只读，权限链与 `search` 一致。

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `pattern` | string | 是 | — | 文本或正则 |
| `path` | string | 否 | `.` | 搜索根（沙盒内），可指向单个文件 |
| `glob` | string | 否 | — | 文件 glob 过滤，如 `**/*.js` |
| `includePatterns` | string[] | 否 | 空 | 仅搜索命中任一模式的文件 |
| `excludePatterns` | string[] | 否 | 空 | 命中任一模式的文件跳过 |
| `caseSensitive` | boolean | 否 | **true** | 与 Electron 一致（`search`/`grep` 无此参数，恒大小写敏感） |
| `isRegexp` | boolean | 否 | false | false 按字面量（`Pattern.quote`） |
| `includeHidden` | boolean | 否 | false | 是否搜索隐藏文件/目录 |
| `outputMode` | string | 否 | `content` | `content` / `files_with_matches` / `count` |
| `offset` | integer | 否 | 0 | 分页偏移，仅 `content` 模式生效 |
| `maxResults` | integer | 否 | 200 | 上限 200（超出收紧，≤0 回落 200） |

返回（`engine` 恒为 `java`，`root` 为沙盒相对搜索根，路径均不暴露绝对主机路径）：

- `content`：`{matches:[{path, relative_path, line, text}], count, total_matches, offset, has_more, root, engine, output_mode}`；`path` 相对沙盒根、`relative_path` 相对搜索根，`text` 去尾部空白；`has_more = count >= maxResults`。
- `files_with_matches`：`{files:[{relative_path, count}]（按路径排序）, count, total_matches, ...}`。
- `count`：`{count, matches:[], total_matches, ...}`，全量扫描计数。

遍历语义对齐 Electron Node 回退实现：跳过 `SKIP_DIRS` 重目录（node_modules/.git/dist/target 等）与隐藏项（除非 `includeHidden`）、按扩展名集合 + 文件头 512 字节 NUL 检测跳过二进制、不跟随符号链接、不可读文件静默跳过；glob 支持花括号展开且 `**/` 匹配零或多层目录。`total_matches` 与 Electron 一致为**扫描停止前**的计数（content/files 模式窗口满即停，count 模式全量），不是穷尽总数。**不解析 .gitignore**（Electron Node 回退会解析，Java 侧以 SKIP_DIRS + 显式 excludePatterns 覆盖）。

## 媒体理解：MediaUnderstandingPlugin

```java
BuiltinPlugins.registerMedia(agent.getPluginManager(), agent.getRestClient());
```

| 工具 | 能力 | 执行方式 |
|------|------|----------|
| `document_unstructured` | 文档结构化，支持 PDF、Office 及服务端允许的音视频文档输入 | 同步非流式运行 |
| `image_understanding` | 图片内容理解 | 异步任务提交、轮询与取消 |
| `audio_understanding` | 音频转写与内容分析 | 异步任务提交、轮询与取消 |
| `video_understanding` | 视频画面、语音及整体内容分析 | 异步任务提交、轮询与取消 |

输入使用 `path` 或 `paths`，可附 `input`、`input_type` 和 `working_directory`。文件从会话 workspace 解析并上传，媒体类型同时受 SDK 扩展名集合与 `/config` 服务端限制约束；图片不能走文档工具。平台必须配置对应的理解 Flow。提交或理解结果未知时返回保留本地上传信息的安全错误，不自动重放。

## 技能工具：SkillTools

`BuiltinPlugins.registerSkills(...)` 注册 `skill_list/fetch/create/upload/delete`，完整配置、权限和示例见[技能配置与供应](skills.md)。

## 会话上下文与用户交互

```java
BuiltinPlugins.registerSessionContext(agent.getPluginManager(), agent::getFlowMetadata);
BuiltinPlugins.registerAskUser(agent.getPluginManager(), userInteractionHandler);
BuiltinPlugins.registerTodo(agent.getPluginManager());
```

- `session_context`：返回当前 flow/session/run/task、请求模式、能力快照、允许工具和 Flow Metadata。
- `ask_user`：通过业务实现的 `UserInteractionHandler` 发起结构化问题，支持选项、多选、图片、超时和取消；未注入 handler 时不注册。
- `todo_write`：整体替换当前会话的瞬态 TODO 列表，最多 100 项；仅存进程内存，真实 `SessionHook.onClose` 或 agent shutdown 时清理，不作为平台长期任务历史。

## 平台定时工具：ScheduleTools

```java
BuiltinPlugins.registerSchedules(agent.getPluginManager(), agent.getRestClient());
```

注册 `schedule_list/create/update/delete/toggle/runs`，操作平台定时任务，不是本地 `SchedulePlugin` 的调度器。创建和更新支持 once/interval/cron，interval 最少 60 秒，cron 使用五段表达式；create/update/delete/toggle 属于写操作，仍走权限审批。

## 浏览器工具：taiyiflow-sdk-browser

浏览器是独立可选模块，默认 `DISABLED`。引入依赖后通过 `BrowserToolProvider` 注册：

```java
BrowserSettings settings = BrowserSettings.builder()
    .mode(BrowserSettings.Mode.HEADLESS)
    .executablePath(Path.of("/opt/pw-browsers/chromium/chrome"))
    .allowedHosts(Set.of("docs.example.com"))
    .executeJsEnabled(false)
    .build();
BrowserRuntime browser = BrowserToolProvider.register(
    agent.getPluginManager(), agent.getRestClient(), settings);
```

工具统一使用 `taiyiflow-in-chrome__` 前缀，17 个可用动作 + 1 个显式不支持声明：

- 标签与导航：`tabs_context`、`tabs_create`、`navigate`、`set_view_size`、`wait_for`。
- 页面操作：`click`、`type`、`hover`、`scroll`、`select_option`、`press_key`。
- 读取与诊断：`get_element_info`、`get_element_ref`、`read_text`、`read_console`、`screenshot`。
- 独立开关：`execute_js`，默认关闭。
- `set_browser_type`：注册但恒返回明确「不支持（Chromium-only）」错误结果（提示模型勿重试），替代泛化 "No handler for tool"；Java SDK 不做 UA 预设切换。

**Electron 参数别名兼容**（仅参数名映射，不放宽 URL/网络/execute_js 安全策略；规范名优先）：`type` 的 `append`（true=不清空原内容）↔`clear`、`press_enter`↔`submit`；`select_option` 的 `text`↔`label`；`read_text` 的 `max_chars`↔`max_characters`；`screenshot` 的 `input_value`↔`input`（理解提示词）。`screenshot` **显式拒绝自定义 `path`**（返回错误并提示改用返回值路径），截图固定落会话 workspace `.snz1dp/browser/`，不静默忽略。

HEADLESS 使用 Playwright Chromium；REMOTE_CDP 连接指定端点，不枚举或接管已有用户标签，也不关闭外部浏览器。所有页面操作在单一驱动线程串行执行，会话只访问自己创建的标签。导航和网络访问受 `allowedHosts/allowedCidrs` 约束；远程 endpoint、header 和 query token 在日志与异常中脱敏。截图写入会话 workspace，`understand=true` 时复用平台图片理解，理解失败仍保留截图路径。

崩溃、断连、超时或取消后，不自动重放 click/type/navigate/upload/JS 等有副作用操作。完整配置见 [Spring Boot 集成](spring-boot.md)、[构建与容器部署](deployment.md)及两个浏览器示例。

## WorkspaceAudit（工作区审计）

有界的纯内存工作区**元数据基线**，用于 shell 命令前后差异对比（diff）做"尽力而为"的产物发现。**不是事务日志，不提供回滚。**

```java
new WorkspaceAudit(Path root);                 // Limits.defaults()
new WorkspaceAudit(Path root, Limits limits);
```

默认扫描预算 `Limits(maxDepth=12, maxEntries=20000, maxScanMillis=250, maxHashes=256, maxHashBytes=16MiB)`。

- 忽略目录：`.git node_modules dist build out target __pycache__ .venv .cache .snz1dp .idea .vscode .gradle state state-dir credentials secrets` 等；跳过符号链接与临时文件。
- 降级原因码：`MAX_DEPTH_EXCEEDED`、`MAX_ENTRIES_EXCEEDED`、`TIME_BUDGET_EXCEEDED`、`SCAN_CONFLICT`、`SCAN_IO_ERROR`；降级时 `complete=false`，绝不把部分结果当完整变更集。
- 差异对比产出 create/edit/delete；声明过的路径从扫描结果排除；按指纹去重避免重复上报同一修订。
- **edit 归属过滤**（对齐 Electron `hasFileSnapshot` 口径）：扫描发现的 `edit` 仅当本会话 agent 写过/声明过该路径（有内容快照）才补报；从未触碰的文件被编辑视为外部进程改动，不补报。`create`/`delete` 不受此过滤。快照来源：shell 声明操作（`fileOperations`）与 `write_file`/`file_edit` 成功写入（经 `BuiltinPlugins.registerAll` 或 starter 注册时共享同一 `WorkspaceAudit`）；按会话分桶、每会话上限 10000 条先进先出，`clearSnapshots(sessionId)` 或 `clear(null,null)` 清除。
- **同工作区占用警告**：同一 agent 的多个会话并行执行共享工作区根目录，新发起对话时若已有其他执行中会话，记非阻断 `WARN` 日志（并行可能互相覆盖文件），不阻断发起、无协议变更；自动续跑/检查点恢复不经此路径，不重复提示。

## 自定义工具

实现 `PluginManager.ToolHandler`（或需要取消信号/上下文时用 `ContextualToolHandler`）并注册：

```java
agent.getPluginManager().register("my_tool", toolCall -> {
    ObjectNode result = mapper.createObjectNode();
    result.put("echo", toolCall.getArguments().path("input").asText());
    return ToolResult.success(toolCall.getId(), result);
});

// 需要资源清理时
agent.getPluginManager().registerCloseable(myResource);
```

`PluginManager` 其他方法：`unregister(name)`、`hasHandler(name)`、`execute(toolCall)`、`execute(context, toolCall)`、`close()`。

自定义工具同样经过 PermissionHook → before → takeover → 执行 → after 的完整链路；工具名若不在写操作名单内按只读处理，需要审批时请自行在 PermissionHook 中判定。

## 安全边界说明

- `ShellPlugin` 的工作目录**不是操作系统级文件系统沙盒**；应按最小权限配置命令白名单。
- 文件路径隔离由 `FilePlugin` 与 `SearchPlugin` 的沙盒校验提供。
- 默认权限策略 READONLY 会拒绝写工具；放开前请评估白名单与沙盒范围。