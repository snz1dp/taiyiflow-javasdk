package io.taiyiflow.sdk.worker;

import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.subtask.SubtaskOrchestrator;
import io.taiyiflow.sdk.transport.rest.ConfigClient;
import io.taiyiflow.sdk.transport.rest.RestClient;
import io.taiyiflow.sdk.transport.rest.ServiceConfig;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigitalWorkerSubtaskQuotaTest {

    private static ServiceConfig serviceConfig(String json) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/config", exchange -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RestClient rest = new RestClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "key", Duration.ofSeconds(2));
            return new ConfigClient(rest).getServiceConfig();
        } finally {
            server.stop(0);
        }
    }

    private static DigitalWorker worker(Path workspace, BuilderCustomizer customizer) {
        DigitalWorker.Builder builder = DigitalWorker.builder()
                .baseUrl("http://127.0.0.1:1")
                .auth(WorkerAuth.apiKey("test-key"))
                .identity(WorkerIdentity.of("worker-1", "Test Worker"))
                .workspace(workspace)
                .identityValidator((identity, role, abilities) -> { });
        return customizer.apply(builder).build();
    }

    private static void identify(DigitalWorker worker) throws Exception {
        setField(worker, "clientId", "client-1");
        setField(worker, "deviceId", "device-1");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    void serverZeroDisablesRunSubtaskSupply(@TempDir Path workspace) throws Exception {
        DigitalWorker worker = worker(workspace, b -> b);
        identify(worker);
        try {
            assertNull(worker.createSubtaskOrchestrator(serviceConfig("{\"max_parallelism_subtask\":0}")));
            TaiyiAgent agent = newAgent(worker);
            try {
                assertFalse(agent.getPluginManager().hasHandler("run_subtask"));
            } finally {
                agent.shutdown();
            }
        } finally {
            worker.stop();
        }
    }

    @Test
    void serverQuotaOnlyTightensLocalParallelism(@TempDir Path workspace) throws Exception {
        DigitalWorker worker = worker(workspace, b -> b.subtaskMaxParallelism(4));
        identify(worker);
        try {
            SubtaskOrchestrator tightened = worker.createSubtaskOrchestrator(
                    serviceConfig("{\"max_parallelism_subtask\":2}"));
            assertNotNull(tightened);
            assertEquals(2, field(tightened, "maxParallelism"));

            SubtaskOrchestrator notWidened = worker.createSubtaskOrchestrator(
                    serviceConfig("{\"max_parallelism_subtask\":10}"));
            assertEquals(4, field(notWidened, "maxParallelism"));

            SubtaskOrchestrator absent = worker.createSubtaskOrchestrator(serviceConfig("{}"));
            assertEquals(4, field(absent, "maxParallelism"));

            SubtaskOrchestrator unavailable = worker.createSubtaskOrchestrator(null);
            assertEquals(4, field(unavailable, "maxParallelism"));

            setField(worker, "subtaskOrchestrator", tightened);
            TaiyiAgent agent = newAgent(worker);
            try {
                assertTrue(agent.getPluginManager().hasHandler("run_subtask"));
            } finally {
                agent.shutdown();
            }
        } finally {
            worker.stop();
        }
    }

    @Test
    void builderLimitsAndTimeoutFlowIntoOrchestrator(@TempDir Path workspace) throws Exception {
        DigitalWorker worker = worker(workspace, b -> b
                .subtaskMaxTasks(3).subtaskMaxParallelism(8).subtaskTimeout(Duration.ofSeconds(30)));
        identify(worker);
        try {
            SubtaskOrchestrator orchestrator = worker.createSubtaskOrchestrator(serviceConfig("{}"));
            assertEquals(3, field(orchestrator, "maxTasks"));
            assertEquals(3, field(orchestrator, "maxParallelism"));
            assertEquals(Duration.ofSeconds(30), field(orchestrator, "maxTimeout"));
        } finally {
            worker.stop();
        }
    }

    private static TaiyiAgent newAgent(DigitalWorker worker) throws Exception {
        var method = DigitalWorker.class.getDeclaredMethod("newAgent", String.class);
        method.setAccessible(true);
        return (TaiyiAgent) method.invoke(worker, "flow-1");
    }

    @FunctionalInterface
    private interface BuilderCustomizer {
        DigitalWorker.Builder apply(DigitalWorker.Builder builder);
    }
}