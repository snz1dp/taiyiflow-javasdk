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
        Tools tools) {

    public WorkerConfig {
        recoveryPolicy = recoveryPolicy == null ? RecoveryPolicy.AUTO : recoveryPolicy;
        permissionPolicy = permissionPolicy == null ? PermissionPolicy.CONFIRM : permissionPolicy;
        approval = approval == null ? new Approval(null) : approval;
        execution = execution == null ? new Execution(null, null, null, null) : execution;
        extensions = extensions == null ? new Extensions(null, null, null, null, null, null, null) : extensions;
        outbox = outbox == null ? new Outbox(null, null, null, null) : outbox;
        tools = tools == null ? new Tools(false, List.of(), false, null, null) : tools;
    }

    /** Source-compatible constructor for callers predating transient outbox configuration. */
    public WorkerConfig(String baseUrl, Auth auth, Identity identity, Path workspace, String clientId,
                        String deviceId, RecoveryPolicy recoveryPolicy, PermissionPolicy permissionPolicy,
                        Approval approval, Execution execution, Extensions extensions) {
        this(baseUrl, auth, identity, workspace, clientId, deviceId, recoveryPolicy, permissionPolicy,
                approval, execution, extensions, null, null, null);
    }

    public WorkerConfig(String baseUrl, Auth auth, Identity identity, Path workspace, String clientId,
                        String deviceId, RecoveryPolicy recoveryPolicy, PermissionPolicy permissionPolicy,
                        Approval approval, Execution execution, Extensions extensions, Path stateDir, Outbox outbox) {
        this(baseUrl, auth, identity, workspace, clientId, deviceId, recoveryPolicy, permissionPolicy,
                approval, execution, extensions, stateDir, outbox, null);
    }

    public record Tools(boolean builtinTools, List<String> allowedShellCommands, boolean media,
                        Browser browser, Skills skills) {
        public Tools {
            allowedShellCommands = allowedShellCommands == null ? List.of() : List.copyOf(allowedShellCommands);
            browser = browser == null ? new Browser(null, null, null, null, null, null, null, null, null,
                    null, null, null, null) : browser;
            skills = skills == null ? new Skills(false, false, null, null, null, List.of(), List.of(),
                    true, null) : skills;
        }
    }

    public record Browser(String mode, Path executablePath, List<String> launchArguments,
                          Duration actionTimeout, Integer maxPagesPerSession, Integer maxReadTextCharacters,
                          Integer maxConsoleMessages, Integer maxScreenshotBytes, Boolean executeJsEnabled,
                          java.util.Set<String> allowedHosts, java.util.Set<String> allowedCidrs,
                          String remoteEndpointSecret, String remoteAuthorizationSecret) { }

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
            Duration requestTimeout) {
        public Execution {
            concurrency = concurrency == null ? 3 : concurrency;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(15) : connectTimeout;
            drainTimeout = drainTimeout == null ? Duration.ofSeconds(30) : drainTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
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
