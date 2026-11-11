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
        Extensions extensions) {

    public WorkerConfig {
        recoveryPolicy = recoveryPolicy == null ? RecoveryPolicy.AUTO : recoveryPolicy;
        permissionPolicy = permissionPolicy == null ? PermissionPolicy.CONFIRM : permissionPolicy;
        approval = approval == null ? new Approval(null) : approval;
        execution = execution == null ? new Execution(null, null, null, null) : execution;
        extensions = extensions == null ? new Extensions(null, null, null, null, null) : extensions;
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

    public record Extensions(
            String identityValidator,
            String taskHandler,
            String approvalHandler,
            String approvalAudit,
            List<String> hooks) {
        public Extensions {
            hooks = hooks == null ? List.of() : List.copyOf(hooks);
        }
    }
}
