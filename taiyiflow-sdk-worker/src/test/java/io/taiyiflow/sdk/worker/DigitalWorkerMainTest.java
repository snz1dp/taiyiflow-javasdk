package io.taiyiflow.sdk.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigitalWorkerMainTest {

    @Test
    void buildsConfiguredWorkerAndIsolatesHookFailures(@TempDir Path workspace) throws Exception {
        TestHook.stops.set(0);
        WorkerConfig config = config(workspace, TestValidator.class.getName());
        DigitalWorker worker = DigitalWorkerMain.build(config,
                name -> Map.of("API_KEY_SECRET", "secret-value").get(name));

        org.junit.jupiter.api.Assertions.assertEquals(ApprovalChannel.PLATFORM, worker.getApprovalChannel());
        worker.stop();

        org.junit.jupiter.api.Assertions.assertEquals(1, TestHook.stops.get());
    }

    @Test
    void coreWorkerClassDoesNotLinkOptionalBrowserModule() throws Exception {
        byte[] bytecode;
        try (var input = DigitalWorker.class.getResourceAsStream("DigitalWorker.class")) {
            bytecode = input.readAllBytes();
        }
        String constants = new String(bytecode, java.nio.charset.StandardCharsets.ISO_8859_1);
        org.junit.jupiter.api.Assertions.assertFalse(constants.contains("io/taiyiflow/sdk/browser"));
    }

    @Test
    void missingSecretFailsWithoutIncludingCredentialMaterial(@TempDir Path workspace) {
        WorkerConfig config = config(workspace, TestValidator.class.getName());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DigitalWorkerMain.build(config, name -> null));

        assertTrue(error.getMessage().contains("API_KEY_SECRET"));
    }

    @Test
    void persistentCredentialStoreMustBeOutsideWorkspace(@TempDir Path workspace) {
        WorkerConfig previous = config(workspace, TestValidator.class.getName());
        WorkerConfig.Auth auth = new WorkerConfig.Auth(
                WorkerConfig.AuthType.PERSISTENT_TOKEN, "PERSISTENT_SECRET", "device-1",
                workspace.resolve("credentials.json"), workspace.resolve("credentials.key"));
        WorkerConfig config = new WorkerConfig(
                previous.baseUrl(), auth, previous.identity(), previous.workspace(), previous.clientId(),
                previous.deviceId(), previous.recoveryPolicy(), previous.permissionPolicy(), previous.approval(),
                previous.execution(), previous.extensions());

        assertThrows(IllegalArgumentException.class,
                () -> DigitalWorkerMain.build(config, name -> "persistent-secret"));
    }

    @Test
    void requestDefaultsPropagateToWorker(@TempDir Path workspace) throws Exception {
        WorkerConfig base = config(workspace, TestValidator.class.getName());
        WorkerConfig.Request request = new WorkerConfig.Request(
                null, "chat", "chat", "qwen-max", true, null, List.of("scope-a"), List.of("doc-1"),
                true, 6, "ON", "group-1",
                List.of("s-a"), "app-1", "owner-1", "ctx", null, null,
                List.of(workspace.resolve("instructions")));
        WorkerConfig configured = new WorkerConfig(
                base.baseUrl(), base.auth(), base.identity(), base.workspace(), base.clientId(),
                base.deviceId(), base.recoveryPolicy(), base.permissionPolicy(), base.approval(),
                base.execution(), base.extensions(), base.stateDir(), base.outbox(), base.tools(), request);
        DigitalWorker worker = DigitalWorkerMain.build(configured, name -> "secret-value");
        try {
            io.taiyiflow.sdk.RequestDefaults defaults = worker.getRequestDefaults();
            assertEquals("qwen-max", defaults.getModel());
            assertTrue(defaults.isAutoLoopEnabled());
            assertEquals(6, defaults.getAutoLoopMaxRounds());
            assertEquals(io.taiyiflow.sdk.RequestDefaults.ImageGeneration.ON, defaults.getImageGeneration());
            assertEquals(List.of("s-a"), defaults.getRelationSessions());
            assertEquals("group-1", defaults.getGroupId());
            assertEquals("scope-a", defaults.getKnowledgeScopes().get(0).asText());
            assertEquals("doc-1", defaults.getKnowledgeDocs().get(0).asText());
            assertEquals(1, worker.getInstructionDirectories().size());
        } finally {
            worker.stop();
        }

        DigitalWorker unconfigured = DigitalWorkerMain.build(base, name -> "secret-value");
        try {
            assertEquals("chat", unconfigured.getRequestDefaults().getInputType());
            assertEquals("chat", unconfigured.getRequestDefaults().getOutputType());
            org.junit.jupiter.api.Assertions.assertFalse(unconfigured.getRequestDefaults().isAutoLoopEnabled());
            assertTrue(unconfigured.getInstructionDirectories().isEmpty());
        } finally {
            unconfigured.stop();
        }
    }

    @Test
    void watchdogTimeoutsPropagateToWorker(@TempDir Path workspace) throws Exception {
        WorkerConfig base = config(workspace, TestValidator.class.getName());
        WorkerConfig configured = new WorkerConfig(
                base.baseUrl(), base.auth(), base.identity(), base.workspace(), base.clientId(),
                base.deviceId(), base.recoveryPolicy(), base.permissionPolicy(), base.approval(),
                new WorkerConfig.Execution(4, null, null, null, java.time.Duration.ZERO, java.time.Duration.ofMinutes(40)),
                base.extensions(), base.stateDir(), base.outbox(), base.tools(), base.request());
        DigitalWorker worker = DigitalWorkerMain.build(configured, name -> "secret-value");
        try {
            assertEquals(java.time.Duration.ZERO, worker.getStreamIdleTimeout());
            assertEquals(java.time.Duration.ofMinutes(40), worker.getToolExecutionTimeout());
        } finally {
            worker.stop();
        }

        DigitalWorker defaults = DigitalWorkerMain.build(base, name -> "secret-value");
        try {
            assertEquals(java.time.Duration.ofSeconds(1200), defaults.getStreamIdleTimeout());
            org.junit.jupiter.api.Assertions.assertNull(defaults.getToolExecutionTimeout());
        } finally {
            defaults.stop();
        }
    }

    @Test
    void toolAndClientTypeSettingsPropagateToWorker(@TempDir Path workspace) throws Exception {
        WorkerConfig base = config(workspace, TestValidator.class.getName());
        WorkerConfig.Tools tools = new WorkerConfig.Tools(true, List.of("sleep"), false, null, null,
                new WorkerConfig.Shell(java.time.Duration.ofSeconds(45)),
                new WorkerConfig.Subtask(java.time.Duration.ofMinutes(30), 2, 8));
        WorkerConfig.Request request = new WorkerConfig.Request(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, "desktop");
        WorkerConfig configured = new WorkerConfig(
                base.baseUrl(), base.auth(), base.identity(), base.workspace(), base.clientId(),
                base.deviceId(), base.recoveryPolicy(), base.permissionPolicy(), base.approval(),
                new WorkerConfig.Execution(4, null, null, null, null, null, 2),
                base.extensions(), base.stateDir(), base.outbox(), tools, request);
        DigitalWorker worker = DigitalWorkerMain.build(configured, name -> "secret-value");
        try {
            assertEquals("desktop", field(worker, "clientType"));
            assertEquals(java.time.Duration.ofSeconds(45), field(worker, "shellCommandTimeout"));
            assertEquals(8, field(worker, "subtaskMaxTasks"));
            assertEquals(2, field(worker, "subtaskMaxParallelism"));
            assertEquals(java.time.Duration.ofMinutes(30), field(worker, "subtaskTimeout"));
            assertEquals(2, field(worker, "maxSessionConcurrency"));
        } finally {
            worker.stop();
        }

        DigitalWorker defaults = DigitalWorkerMain.build(base, name -> "secret-value");
        try {
            org.junit.jupiter.api.Assertions.assertNull(field(defaults, "clientType"));
            org.junit.jupiter.api.Assertions.assertNull(field(defaults, "shellCommandTimeout"));
            assertEquals(16, field(defaults, "subtaskMaxTasks"));
            assertEquals(4, field(defaults, "subtaskMaxParallelism"));
            assertEquals(java.time.Duration.ofHours(1), field(defaults, "subtaskTimeout"));
            org.junit.jupiter.api.Assertions.assertNull(field(defaults, "maxSessionConcurrency"));
        } finally {
            defaults.stop();
        }
    }

    private static Object field(DigitalWorker worker, String name) throws Exception {
        java.lang.reflect.Field field = DigitalWorker.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(worker);
    }

    private static WorkerConfig config(Path workspace, String validator) {
        return new WorkerConfig(
                "http://127.0.0.1:1",
                new WorkerConfig.Auth(WorkerConfig.AuthType.API_KEY, "API_KEY_SECRET", null, null, null),
                new WorkerConfig.Identity("worker-1", "Test Worker", null, List.of("shell")),
                workspace, null, null, null, null, null,
                new WorkerConfig.Execution(4, null, null, null),
                new WorkerConfig.Extensions(validator, null, null, null, List.of(TestHook.class.getName())));
    }

    public static final class TestValidator implements WorkerIdentityValidator {
        @Override
        public void validate(WorkerIdentity identity, WorkerRole role, Set<Ability> abilities) {
        }
    }

    public static final class TestHook implements WorkerHook {
        private static final AtomicInteger stops = new AtomicInteger();

        @Override
        public void onStop() {
            stops.incrementAndGet();
            throw new IllegalStateException("hook failure must not escape");
        }
    }
}
