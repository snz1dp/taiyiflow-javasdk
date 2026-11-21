package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.hooks.PermissionPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record WorkerConfig(
        String baseUrl,
        Auth auth,
        Identity identity,
        Path workspace,
        String clientId,
        String deviceId,
        RecoveryPolicy recoveryPolicy,
        PermissionPolicy permissionPolicy,
        Approval approval,
        Execution execution,
        Extensions extensions,
        Path stateDir,
        Outbox outbox,
        Tools tools,
        Request request,
        Permission permission) {

    public WorkerConfig {
        recoveryPolicy = recoveryPolicy == null ? RecoveryPolicy.AUTO : recoveryPolicy;
        permissionPolicy = permissionPolicy == null ? PermissionPolicy.CONFIRM : permissionPolicy;
        approval = approval == null ? new Approval(null) : approval;
        execution = execution == null ? new Execution(null, null, null, null, null, null, null) : execution;
        extensions = extensions == null ? new Extensions(null, null, null, null, null, null, null) : extensions;
        outbox = outbox == null ? new Outbox(null, null, null, null) : outbox;
        tools = tools == null ? new Tools(false, List.of(), false, null, null, null, null) : tools;
        request = request == null ? Request.defaults() : request;
        permission = permission == null ? new Permission(null, null, null) : permission;
    }

    /** Source-compatible constructor for callers predating the fine-grained permission section. */
    public WorkerConfig(String baseUrl, Auth auth, Identity identity, Path workspace, String clientId,
                        String deviceId, RecoveryPolicy recoveryPolicy, PermissionPolicy permissionPolicy,
                        Approval approval, Execution execution, Extensions extensions, Path stateDir, Outbox outbox,
                        Tools tools, Request request) {
        this(baseUrl, auth, identity, workspace, clientId, deviceId, recoveryPolicy, permissionPolicy,
                approval, execution, extensions, stateDir, outbox, tools, request, null);
    }

    /** Source-compatible constructor for callers predating transient outbox configuration. */
    public WorkerConfig(String baseUrl, Auth auth, Identity identity, Path workspace, String clientId,
                        String deviceId, RecoveryPolicy recoveryPolicy, PermissionPolicy permissionPolicy,
                        Approval approval, Execution execution, Extensions extensions) {
        this(baseUrl, auth, identity, workspace, clientId, deviceId, recoveryPolicy, permissionPolicy,
                approval, execution, extensions, null, null, null, null);
    }

    public WorkerConfig(String baseUrl, Auth auth, Identity identity, Path workspace, String clientId,
                        String deviceId, RecoveryPolicy recoveryPolicy, PermissionPolicy permissionPolicy,
                        Approval approval, Execution execution, Extensions extensions, Path stateDir, Outbox outbox) {
        this(baseUrl, auth, identity, workspace, clientId, deviceId, recoveryPolicy, permissionPolicy,
                approval, execution, extensions, stateDir, outbox, null, null);
    }

    public WorkerConfig(String baseUrl, Auth auth, Identity identity, Path workspace, String clientId,
                        String deviceId, RecoveryPolicy recoveryPolicy, PermissionPolicy permissionPolicy,
                        Approval approval, Execution execution, Extensions extensions, Path stateDir, Outbox outbox,
                        Tools tools) {
        this(baseUrl, auth, identity, workspace, clientId, deviceId, recoveryPolicy, permissionPolicy,
                approval, execution, extensions, stateDir, outbox, tools, null);
    }

    /** First-frame request defaults and opt-in instruction directories (worker.yml {@code request:}). */
    public record Request(
            String requestType,
            String inputType,
            String outputType,
            String model,
            Boolean reasoning,
            Boolean internetSearch,
            List<String> knowledgeScopes,
            List<String> knowledgeDocs,
            Boolean autoLoopEnabled,
            Integer autoLoopMaxRounds,
            String imageGeneration,
            String groupId,
            List<String> relationSessions,
            String agentAppId,
            String agentAppOwnerId,
            String clientContextDir,
            Boolean longTermMemoryEnabled,
            Boolean crossSessionMemoryEnabled,
            List<Path> instructionDirectories,
            String clientType) {
        public Request {
            inputType = inputType == null ? "chat" : inputType;
            outputType = outputType == null ? "chat" : outputType;
            knowledgeScopes = knowledgeScopes == null ? List.of() : List.copyOf(knowledgeScopes);
            knowledgeDocs = knowledgeDocs == null ? List.of() : List.copyOf(knowledgeDocs);
            autoLoopEnabled = autoLoopEnabled == null ? Boolean.FALSE : autoLoopEnabled;
            imageGeneration = imageGeneration == null ? "AUTO" : imageGeneration;
            relationSessions = relationSessions == null ? List.of() : List.copyOf(relationSessions);
            instructionDirectories = instructionDirectories == null ? List.of() : List.copyOf(instructionDirectories);
        }

        /** Source-compatible constructor for callers predating request.client-type. */
        public Request(String requestType, String inputType, String outputType, String model, Boolean reasoning,
                       Boolean internetSearch, List<String> knowledgeScopes, List<String> knowledgeDocs,
                       Boolean autoLoopEnabled, Integer autoLoopMaxRounds, String imageGeneration, String groupId,
                       List<String> relationSessions, String agentAppId, String agentAppOwnerId,
                       String clientContextDir, Boolean longTermMemoryEnabled, Boolean crossSessionMemoryEnabled,
                       List<Path> instructionDirectories) {
            this(requestType, inputType, outputType, model, reasoning, internetSearch, knowledgeScopes, knowledgeDocs,
                    autoLoopEnabled, autoLoopMaxRounds, imageGeneration, groupId, relationSessions, agentAppId,
                    agentAppOwnerId, clientContextDir, longTermMemoryEnabled, crossSessionMemoryEnabled,
                    instructionDirectories, null);
        }

        public static Request defaults() {
            return new Request(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record Tools(boolean builtinTools, List<String> allowedShellCommands, boolean media,
                        Browser browser, Skills skills, Shell shell, Subtask subtask) {
        public Tools {
            allowedShellCommands = allowedShellCommands == null ? List.of() : List.copyOf(allowedShellCommands);
            browser = browser == null ? new Browser(null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null) : browser;
            skills = skills == null ? new Skills(false, false, null, null, null, List.of(), List.of(),
                    true, null) : skills;
            shell = shell == null ? new Shell(null) : shell;
            subtask = subtask == null ? new Subtask(null, null, null) : subtask;
        }

        /** Source-compatible constructor for callers predating tools.shell/tools.subtask. */
        public Tools(boolean builtinTools, List<String> allowedShellCommands, boolean media,
                     Browser browser, Skills skills) {
            this(builtinTools, allowedShellCommands, media, browser, skills, null, null);
        }
    }

    /** Built-in shell tool settings (worker.yml {@code tools.shell:}). */
    public record Shell(Duration commandTimeout) {
        public Shell {
            if (commandTimeout != null && (commandTimeout.isZero() || commandTimeout.isNegative())) {
                throw new IllegalArgumentException("tools.shell.command-timeout must be positive");
            }
        }
    }

    /** run_subtask assembly limits (worker.yml {@code tools.subtask:}); server /config quota can only tighten parallelism. */
    public record Subtask(Duration timeout, Integer maxParallelism, Integer maxTasks) {
        public Subtask {
            if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
                throw new IllegalArgumentException("tools.subtask.timeout must be positive");
            }
            if (maxParallelism != null && maxParallelism < 1) {
                throw new IllegalArgumentException("tools.subtask.max-parallelism must be >= 1");
            }
            if (maxTasks != null && maxTasks < 1) {
                throw new IllegalArgumentException("tools.subtask.max-tasks must be >= 1");
            }
        }
    }

    public record Browser(String mode, Path executablePath, List<String> launchArguments,
                          Duration actionTimeout, Integer maxPagesPerSession, Integer maxReadTextCharacters,
                          Integer maxConsoleMessages, Integer maxScreenshotBytes, Boolean executeJsEnabled,
                          java.util.Set<String> allowedHosts, java.util.Set<String> allowedCidrs,
                          String remoteEndpointSecret, String remoteAuthorizationSecret, String permissionMode) {

        /** Source-compatible constructor for callers predating browser permission-mode. */
        public Browser(String mode, Path executablePath, List<String> launchArguments,
                       Duration actionTimeout, Integer maxPagesPerSession, Integer maxReadTextCharacters,
                       Integer maxConsoleMessages, Integer maxScreenshotBytes, Boolean executeJsEnabled,
                       java.util.Set<String> allowedHosts, java.util.Set<String> allowedCidrs,
                       String remoteEndpointSecret, String remoteAuthorizationSecret) {
            this(mode, executablePath, launchArguments, actionTimeout, maxPagesPerSession, maxReadTextCharacters,
                    maxConsoleMessages, maxScreenshotBytes, executeJsEnabled, allowedHosts, allowedCidrs,
                    remoteEndpointSecret, remoteAuthorizationSecret, null);
        }
    }

    /**
     * Fine-grained permission section (worker.yml {@code permission:}). Rules and policies can only
     * originate from this configuration file, never from skill content or model arguments.
     */
    public record Permission(List<Rule> rules, String writeConfirmation, java.util.Map<String, String> categories) {
        public Permission {
            rules = rules == null ? List.of() : List.copyOf(rules);
            categories = categories == null ? java.util.Map.of() : java.util.Map.copyOf(categories);
        }

        public record Rule(String action, String toolName, String commandPrefix) { }

        /** Normalizes raw configuration into immutable settings; invalid entries fall back stricter. */
        public io.taiyiflow.sdk.core.security.PermissionSettings toSettings(
                PermissionPolicy policy, String browserPermissionMode) {
            var builder = io.taiyiflow.sdk.core.security.PermissionSettings.builder()
                    .policy(policy == null ? PermissionPolicy.CONFIRM : policy)
                    .writeConfirmation(io.taiyiflow.sdk.core.security.WriteConfirmation.of(writeConfirmation))
                    .browserPermissionMode(io.taiyiflow.sdk.core.security.BrowserPermissionMode.of(browserPermissionMode));
            for (Rule rule : rules) {
                builder.addRule(io.taiyiflow.sdk.core.security.PermissionRule.of(
                        rule.action(), rule.toolName(), rule.commandPrefix()));
            }
            categories.forEach((key, value) -> {
                io.taiyiflow.sdk.core.security.ToolCategory category =
                        io.taiyiflow.sdk.core.security.ToolCategory.fromKey(key);
                PermissionPolicy categoryPolicy = PermissionPolicy.fromKey(value);
                if (category != null && categoryPolicy != null) {
                    builder.categoryPolicy(category, categoryPolicy);
                }
            });
            return builder.build();
        }
    }

    public record Skills(boolean enabled, boolean platformEnabled, Path workspaceDir, Path userDir,
                         Path compatDir, List<String> defaultNames, List<String> bundledResources,
                         Boolean toleratePlatformFailure, String syncMode) {
        public Skills {
            toleratePlatformFailure = toleratePlatformFailure == null ? Boolean.TRUE : toleratePlatformFailure;
        }
    }

    public enum AuthType {
        API_KEY,
        JWT,
        PERSISTENT_TOKEN
    }

    public record Auth(
            AuthType type,
            String tokenSecret,
            String deviceId,
            Path credentialFile,
            Path keyFile) {
    }

    public record Identity(
            String workerId,
            String displayName,
            WorkerRole role,
            List<String> abilities) {
        public Identity {
            role = role == null ? WorkerRole.COLLABORATION_WORKER : role;
            abilities = abilities == null ? List.of() : List.copyOf(abilities);
        }
    }

    public record Approval(ApprovalChannel channel) {
        public Approval {
            channel = channel == null ? ApprovalChannel.PLATFORM : channel;
        }
    }

    public record Execution(
            Integer concurrency,
            Duration connectTimeout,
            Duration drainTimeout,
            Duration requestTimeout,
            Duration streamIdleTimeout,
            Duration toolExecutionTimeout,
            Integer maxSessionConcurrency) {
        public Execution {
            concurrency = concurrency == null ? 3 : concurrency;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(15) : connectTimeout;
            drainTimeout = drainTimeout == null ? Duration.ofSeconds(30) : drainTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
            streamIdleTimeout = streamIdleTimeout == null ? Duration.ofSeconds(1200) : streamIdleTimeout;
            if (streamIdleTimeout.isNegative()) {
                throw new IllegalArgumentException("execution.stream-idle-timeout must be >= 0 (0 disables idle checks)");
            }
            if (toolExecutionTimeout != null && (toolExecutionTimeout.isZero() || toolExecutionTimeout.isNegative())) {
                throw new IllegalArgumentException("execution.tool-execution-timeout must be positive");
            }
            if (maxSessionConcurrency != null && maxSessionConcurrency < 0) {
                throw new IllegalArgumentException("execution.max-session-concurrency must be >= 0");
            }
        }

        public Execution(Integer concurrency, Duration connectTimeout, Duration drainTimeout, Duration requestTimeout) {
            this(concurrency, connectTimeout, drainTimeout, requestTimeout, null, null, null);
        }

        /** Source-compatible constructor for callers predating execution.max-session-concurrency. */
        public Execution(Integer concurrency, Duration connectTimeout, Duration drainTimeout, Duration requestTimeout,
                         Duration streamIdleTimeout, Duration toolExecutionTimeout) {
            this(concurrency, connectTimeout, drainTimeout, requestTimeout, streamIdleTimeout, toolExecutionTimeout, null);
        }
    }

    public record Outbox(
            Integer maxEntries,
            Long maxEntryBytes,
            Long maxTotalBytes,
            Integer maxRetries) {
        public Outbox {
            maxEntries = maxEntries == null ? 1000 : maxEntries;
            maxEntryBytes = maxEntryBytes == null ? 1024L * 1024L : maxEntryBytes;
            maxTotalBytes = maxTotalBytes == null ? 16L * 1024L * 1024L : maxTotalBytes;
            maxRetries = maxRetries == null ? 32 : maxRetries;
        }
    }

    public record Extensions(
            String identityValidator,
            String workerRegistrar,
            String taskHandler,
            String taskReporter,
            String approvalHandler,
            String approvalAudit,
            List<String> hooks) {
        public Extensions {
            hooks = hooks == null ? List.of() : List.copyOf(hooks);
        }

        public Extensions(String identityValidator, String taskHandler, String approvalHandler,
                          String approvalAudit, List<String> hooks) {
            this(identityValidator, null, taskHandler, null, approvalHandler, approvalAudit, hooks);
        }
    }
}
