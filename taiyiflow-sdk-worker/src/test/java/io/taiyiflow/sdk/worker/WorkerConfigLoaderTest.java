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
                state-dir: /tmp/taiyiflow-runtime-state
                outbox:
                  max-entries: 20
                  max-entry-bytes: 4096
                  max-total-bytes: 65536
                  max-retries: 5
                recovery-policy: AUTO
                permission-policy: CONFIRM
                approval:
                  channel: PLATFORM
                execution:
                  concurrency: 5
                  connect-timeout: PT20S
                extensions:
                  identity-validator: example.PlatformIdentityValidator
                  worker-registrar: example.IamWorkerRegistrar
                  task-handler: example.BusinessTaskHandler
                  task-reporter: example.BusinessTaskReporter
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
        assertEquals(Path.of("/tmp/taiyiflow-runtime-state"), loaded.stateDir());
        assertEquals(20, loaded.outbox().maxEntries());
        assertEquals(4096L, loaded.outbox().maxEntryBytes());
        assertEquals(65536L, loaded.outbox().maxTotalBytes());
        assertEquals(5, loaded.outbox().maxRetries());
        assertEquals(ApprovalChannel.PLATFORM, loaded.approval().channel());
        assertEquals(5, loaded.execution().concurrency());
        assertEquals(Duration.ofSeconds(20), loaded.execution().connectTimeout());
        assertEquals(Duration.ofSeconds(30), loaded.execution().requestTimeout());
        assertEquals("example.PlatformIdentityValidator", loaded.extensions().identityValidator());
        assertEquals("example.IamWorkerRegistrar", loaded.extensions().workerRegistrar());
        assertEquals("example.BusinessTaskHandler", loaded.extensions().taskHandler());
        assertEquals("example.BusinessTaskReporter", loaded.extensions().taskReporter());
    }
}
