package io.taiyiflow.sdk.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
