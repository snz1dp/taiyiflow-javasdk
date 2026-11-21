package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(loaded.tools().skills().toleratePlatformFailure());
    }

    @Test
    void watchdogTimeoutsBindWithSafeDefaults(@TempDir Path directory) throws Exception {
        Path omitted = directory.resolve("omitted.yml");
        Files.writeString(omitted, """
                execution:
                  concurrency: 2
                """);
        Path configured = directory.resolve("configured.yml");
        Files.writeString(configured, """
                execution:
                  stream-idle-timeout: PT0S
                  tool-execution-timeout: PT40M
                """);

        WorkerConfigLoader loader = new WorkerConfigLoader();
        WorkerConfig defaults = loader.load(omitted);
        assertEquals(Duration.ofSeconds(1200), defaults.execution().streamIdleTimeout());
        assertNull(defaults.execution().toolExecutionTimeout());

        WorkerConfig custom = loader.load(configured);
        assertEquals(Duration.ZERO, custom.execution().streamIdleTimeout());
        assertEquals(Duration.ofMinutes(40), custom.execution().toolExecutionTimeout());
    }

    @Test
    void defaultsAndOverridesPlatformFailureTolerance(@TempDir Path directory) throws Exception {
        Path omitted = directory.resolve("omitted.yml");
        Files.writeString(omitted, """
                tools:
                  skills:
                    enabled: true
                """);
        Path explicitFalse = directory.resolve("explicit-false.yml");
        Files.writeString(explicitFalse, """
                tools:
                  skills:
                    enabled: true
                    tolerate-platform-failure: false
                """);

        WorkerConfigLoader loader = new WorkerConfigLoader();

        assertTrue(loader.load(omitted).tools().skills().toleratePlatformFailure());
        assertFalse(loader.load(explicitFalse).tools().skills().toleratePlatformFailure());
    }

    @Test
    void requestDefaultsBindFromYamlWithSafeDefaults(@TempDir Path directory) throws Exception {
        Path omitted = directory.resolve("omitted.yml");
        Files.writeString(omitted, "base-url: http://localhost:8080\n");
        WorkerConfig.Request defaults = new WorkerConfigLoader().load(omitted).request();
        assertEquals("chat", defaults.inputType());
        assertEquals("chat", defaults.outputType());
        assertEquals(Boolean.FALSE, defaults.autoLoopEnabled());
        assertEquals("AUTO", defaults.imageGeneration());
        assertTrue(defaults.instructionDirectories().isEmpty());
        assertTrue(defaults.relationSessions().isEmpty());
        assertTrue(defaults.knowledgeScopes().isEmpty());
        assertTrue(defaults.knowledgeDocs().isEmpty());

        Path configured = directory.resolve("configured.yml");
        Files.writeString(configured, """
                request:
                  model: qwen-max
                  reasoning: true
                  internet-search: true
                  knowledge-scopes: [scope-a, scope-b]
                  knowledge-docs: [doc-1]
                  auto-loop-enabled: true
                  auto-loop-max-rounds: 6
                  image-generation: "ON"
                  group-id: group-1
                  relation-sessions: [s-a, s-b]
                  agent-app-id: app-1
                  agent-app-owner-id: owner-1
                  client-context-dir: ctx
                  long-term-memory-enabled: true
                  cross-session-memory-enabled: false
                  instruction-directories: [/etc/taiyiflow/instructions]
                """);
        WorkerConfig.Request request = new WorkerConfigLoader().load(configured).request();
        assertEquals("qwen-max", request.model());
        assertEquals(Boolean.TRUE, request.reasoning());
        assertEquals(Boolean.TRUE, request.internetSearch());
        assertEquals(java.util.List.of("scope-a", "scope-b"), request.knowledgeScopes());
        assertEquals(java.util.List.of("doc-1"), request.knowledgeDocs());
        assertEquals(Boolean.TRUE, request.autoLoopEnabled());
        assertEquals(6, request.autoLoopMaxRounds());
        assertEquals("ON", request.imageGeneration());
        assertEquals("group-1", request.groupId());
        assertEquals(java.util.List.of("s-a", "s-b"), request.relationSessions());
        assertEquals("app-1", request.agentAppId());
        assertEquals("owner-1", request.agentAppOwnerId());
        assertEquals("ctx", request.clientContextDir());
        assertEquals(Boolean.TRUE, request.longTermMemoryEnabled());
        assertEquals(Boolean.FALSE, request.crossSessionMemoryEnabled());
        assertEquals(java.util.List.of(Path.of("/etc/taiyiflow/instructions")), request.instructionDirectories());
    }

    @Test
    void permissionSectionBindsAndNormalizesToStricterDefaults(@TempDir Path directory) throws Exception {
        Path omitted = directory.resolve("omitted.yml");
        Files.writeString(omitted, "base-url: http://localhost:8080\n");
        WorkerConfig defaults = new WorkerConfigLoader().load(omitted);
        var defaultSettings = defaults.permission().toSettings(defaults.permissionPolicy(), null);
        assertEquals(PermissionPolicy.CONFIRM, defaultSettings.getPolicy());
        assertEquals(io.taiyiflow.sdk.core.security.WriteConfirmation.CONFIRM, defaultSettings.getWriteConfirmation());
        assertEquals(io.taiyiflow.sdk.core.security.BrowserPermissionMode.ASK, defaultSettings.getBrowserPermissionMode());
        assertTrue(defaultSettings.getRules().isEmpty());
        assertTrue(defaultSettings.getCategoryPolicies().isEmpty());

        Path configured = directory.resolve("configured.yml");
        Files.writeString(configured, """
                permission-policy: AUTO
                permission:
                  rules:
                    - action: deny
                      command-prefix: rm
                    - action: bogus
                      tool-name: Shell
                    - action: allow
                  write-confirmation: direct
                  categories:
                    shell: auto
                    file: readonly
                    bogus: auto
                    skill: bogus
                tools:
                  browser:
                    permission-mode: follow_a_plan
                """);
        WorkerConfig loaded = new WorkerConfigLoader().load(configured);
        assertEquals("follow_a_plan", loaded.tools().browser().permissionMode());
        var settings = loaded.permission().toSettings(loaded.permissionPolicy(),
                loaded.tools().browser().permissionMode());
        assertEquals(PermissionPolicy.AUTO, settings.getPolicy());
        assertEquals(io.taiyiflow.sdk.core.security.WriteConfirmation.DIRECT, settings.getWriteConfirmation());
        assertEquals(io.taiyiflow.sdk.core.security.BrowserPermissionMode.FOLLOW_A_PLAN,
                settings.getBrowserPermissionMode());
        // empty-selector rule dropped; unknown action normalized to ASK with lowercased name
        assertEquals(2, settings.getRules().size());
        assertEquals(io.taiyiflow.sdk.core.security.PermissionRule.Action.DENY, settings.getRules().get(0).action());
        assertEquals("rm", settings.getRules().get(0).commandPrefix());
        assertEquals(io.taiyiflow.sdk.core.security.PermissionRule.Action.ASK, settings.getRules().get(1).action());
        assertEquals("shell", settings.getRules().get(1).toolName());
        // unknown category keys and policy values are dropped
        assertEquals(2, settings.getCategoryPolicies().size());
        assertEquals(PermissionPolicy.AUTO,
                settings.getCategoryPolicies().get(io.taiyiflow.sdk.core.security.ToolCategory.SHELL));
        assertEquals(PermissionPolicy.READONLY,
                settings.getCategoryPolicies().get(io.taiyiflow.sdk.core.security.ToolCategory.FILE));
    }

    @Test
    void shellSubtaskClientTypeAndConcurrencyBindWithDefaults(@TempDir Path directory) throws Exception {
        Path omitted = directory.resolve("omitted.yml");
        Files.writeString(omitted, "base-url: http://localhost:8080\n");
        WorkerConfig defaults = new WorkerConfigLoader().load(omitted);
        assertNull(defaults.tools().shell().commandTimeout());
        assertNull(defaults.tools().subtask().timeout());
        assertNull(defaults.tools().subtask().maxParallelism());
        assertNull(defaults.tools().subtask().maxTasks());
        assertNull(defaults.request().clientType());
        assertNull(defaults.execution().maxSessionConcurrency());

        Path configured = directory.resolve("configured.yml");
        Files.writeString(configured, """
                execution:
                  max-session-concurrency: 2
                request:
                  client-type: desktop
                tools:
                  shell:
                    command-timeout: PT45S
                  subtask:
                    timeout: PT30M
                    max-parallelism: 2
                    max-tasks: 8
                """);
        WorkerConfig loaded = new WorkerConfigLoader().load(configured);
        assertEquals(2, loaded.execution().maxSessionConcurrency());
        assertEquals("desktop", loaded.request().clientType());
        assertEquals(Duration.ofSeconds(45), loaded.tools().shell().commandTimeout());
        assertEquals(Duration.ofMinutes(30), loaded.tools().subtask().timeout());
        assertEquals(2, loaded.tools().subtask().maxParallelism());
        assertEquals(8, loaded.tools().subtask().maxTasks());
    }

    @Test
    void unknownBrowserPermissionModeFallsBackToAsk(@TempDir Path directory) throws Exception {
        Path configured = directory.resolve("configured.yml");
        Files.writeString(configured, """
                tools:
                  browser:
                    permission-mode: yolo
                """);
        WorkerConfig loaded = new WorkerConfigLoader().load(configured);
        var settings = loaded.permission().toSettings(loaded.permissionPolicy(),
                loaded.tools().browser().permissionMode());
        assertEquals(io.taiyiflow.sdk.core.security.BrowserPermissionMode.ASK,
                settings.getBrowserPermissionMode());
    }
}
