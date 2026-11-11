package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerConfigLoaderTest {

    @Test
    void loadsKebabCaseYamlAndRuntimeDefaults(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("worker.yml");
        Files.writeString(config, """
                base-url: http://localhost:8080
                auth:
                  type: API_KEY
                  token-secret: TAIYIFLOW_API_KEY
                identity:
                  worker-id: worker-1
                  display-name: Test Worker
                  abilities: [shell, files]
                workspace: /tmp/taiyiflow-worker
                recovery-policy: AUTO
                permission-policy: CONFIRM
                approval:
                  channel: PLATFORM
                execution:
                  concurrency: 5
                  connect-timeout: PT20S
                extensions:
                  identity-validator: example.PlatformIdentityValidator
                  hooks: [example.MetricsHook]
                """);

        WorkerConfig loaded = new WorkerConfigLoader().load(config);

        assertEquals("http://localhost:8080", loaded.baseUrl());
        assertEquals(WorkerConfig.AuthType.API_KEY, loaded.auth().type());
        assertEquals("TAIYIFLOW_API_KEY", loaded.auth().tokenSecret());
        assertEquals("worker-1", loaded.identity().workerId());
        assertEquals(WorkerRole.COLLABORATION_WORKER, loaded.identity().role());
        assertEquals(RecoveryPolicy.AUTO, loaded.recoveryPolicy());
        assertEquals(PermissionPolicy.CONFIRM, loaded.permissionPolicy());
        assertEquals(ApprovalChannel.PLATFORM, loaded.approval().channel());
        assertEquals(5, loaded.execution().concurrency());
        assertEquals(Duration.ofSeconds(20), loaded.execution().connectTimeout());
        assertEquals(Duration.ofSeconds(30), loaded.execution().requestTimeout());
        assertEquals("example.PlatformIdentityValidator", loaded.extensions().identityValidator());
    }
}
