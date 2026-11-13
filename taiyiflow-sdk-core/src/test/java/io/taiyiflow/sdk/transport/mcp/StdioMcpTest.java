package io.taiyiflow.sdk.transport.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StdioMcpTest {
    private static final String SERVER_SCRIPT = """
            import sys, json, os, time
            spawn_file = os.environ.get('SPAWN_FILE')
            if spawn_file:
                with open(spawn_file, 'a', encoding='utf-8') as stream:
                    stream.write(str(os.getpid()) + '\\n')
            def send(value):
                sys.stdout.write(json.dumps(value, separators=(',', ':')) + '\\n')
                sys.stdout.flush()
            def result(request, text):
                send({'jsonrpc':'2.0','id':request['id'],'result':{
                    'content':[{'type':'text','text':text}]}})
            for line in sys.stdin:
                request = json.loads(line)
                method = request.get('method')
                if method == 'initialize':
                    assert request['params']['protocolVersion'] == '2025-03-26'
                    send({'jsonrpc':'2.0','id':request['id'],'result':{
                        'protocolVersion':'2025-03-26','serverInfo':{'name':'test','version':'1'}}})
                elif method == 'notifications/initialized':
                    continue
                elif method == 'tools/list':
                    send({'jsonrpc':'2.0','id':request['id'],'result':{'tools':[
                        {'name':'echo','inputSchema':{'type':'object'}},
                        {'name':'env','input_schema':{'type':'object'}},
                        {'name':'split','inputSchema':{'type':'object'}},
                        {'name':'hold','inputSchema':{'type':'object'}},
                        {'name':'exit','inputSchema':{'type':'object'}}]}})
                elif method == 'tools/call':
                    name = request['params']['name']
                    args = request['params'].get('arguments', {})
                    if name == 'echo':
                        result(request, args.get('value', ''))
                    elif name == 'env':
                        result(request, json.dumps(dict(os.environ)))
                    elif name == 'split':
                        response = json.dumps({'jsonrpc':'2.0','id':request['id'],'result':{
                            'content':[{'type':'text','text':'跨chunk完成'}]}}, ensure_ascii=False)
                        encoded = (response + '\\n').encode('utf-8')
                        middle = len(encoded) // 2
                        os.write(1, encoded[:middle])
                        time.sleep(0.05)
                        os.write(1, encoded[middle:])
                    elif name == 'hold':
                        time.sleep(float(args.get('seconds', 2)))
                        result(request, 'held')
                    elif name == 'exit':
                        os._exit(7)
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void parsesThreeBackendConfigurationFormsConsistently() throws Exception {
        Map<String, String> env = Map.of("CUSTOM", "value");
        StdioMcpServerConfig direct = StdioMcpServerConfig.of(
                "python3", List.of("-u", "server.py"), env);
        StdioMcpServerConfig uri = StdioMcpServerConfig.parse("stdio://python3  -u\tserver.py", env);
        String json = mapper.writeValueAsString(mapper.createObjectNode()
                .put("command", "python3")
                .set("args", mapper.createArrayNode().add("-u").add("server.py")));
        JsonNode dataObject = mapper.readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) dataObject).set("env", mapper.valueToTree(env));
        String data = "data:application/json;base64," + Base64.getEncoder().encodeToString(
                mapper.writeValueAsBytes(dataObject));
        StdioMcpServerConfig fromData = StdioMcpServerConfig.parse(data);

        assertConfigEquals(direct, uri);
        assertConfigEquals(direct, fromData);
        StdioMcpServerConfig plus = StdioMcpServerConfig.parse(
                "data:application/json,{\"command\":\"python3\",\"args\":[\"a+b\"]}");
        assertEquals("a+b", plus.args().get(0));
        StdioMcpServerConfig quotes = StdioMcpServerConfig.parse("stdio://python3 'hello world'");
        assertEquals(List.of("'hello", "world'"), quotes.args());
        assertFalse(direct.toString().contains("value"));
        assertFalse(direct.cacheKey().contains("value"));
    }

    @Test
    void concurrentFirstListUsesOneProcessAndFramesAcrossChunks() throws Exception {
        Path spawnFile = tempDir.resolve("spawns");
        StdioMcpPolicy policy = policy("SPAWN_FILE");
        StdioMcpServerConfig config = config(spawnFile, Map.of());
        try (StdioMcpManager manager = new StdioMcpManager(policy, Duration.ofSeconds(3));
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<List<McpToolClient.McpTool>>> calls = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                calls.add(() -> manager.listTools(config));
            }
            for (Future<List<McpToolClient.McpTool>> future : executor.invokeAll(calls)) {
                assertEquals(5, future.get().size());
            }
            assertEquals(1, Files.readAllLines(spawnFile).size());
            assertEquals(1, manager.instanceCount());

            JsonNode split = manager.callTool(config, "split", mapper.createObjectNode(), null);
            assertEquals("跨chunk完成", split.at("/content/0/text").asText());
            StdioMcpClient client = manager.instance(config);
            manager.close();
            assertFalse(client.isAlive());
        }
    }

    @Test
    void environmentContainsOnlyExplicitAndAllowedInheritedValues() throws Exception {
        Path spawnFile = tempDir.resolve("env-spawns");
        StdioMcpPolicy policy = StdioMcpPolicy.builder(tempDir)
                .allowCommand("python3")
                .allowEnvironment("SPAWN_FILE", "CUSTOM")
                .inheritedEnvironment(Set.of("PATH"))
                .build();
        StdioMcpServerConfig config = config(spawnFile, Map.of("CUSTOM", "secret-value"));
        try (StdioMcpManager manager = new StdioMcpManager(policy, Duration.ofSeconds(3))) {
            JsonNode result = manager.callTool(config, "env", mapper.createObjectNode(), null);
            JsonNode environment = mapper.readTree(result.at("/content/0/text").asText());
            assertEquals("secret-value", environment.get("CUSTOM").asText());
            assertTrue(environment.has("PATH"));
            assertFalse(environment.has("HOME"));
            assertFalse(environment.has("AWS_SECRET_ACCESS_KEY"));
        }
        assertThrows(IllegalArgumentException.class, () -> StdioMcpPolicy.builder(tempDir)
                .allowCommand("python3").build().validate(config));
        assertThrows(IllegalArgumentException.class, () -> StdioMcpPolicy.builder(tempDir)
                .allowEnvironment("CUSTOM", "SPAWN_FILE").build().validate(config));
    }

    @Test
    void timeoutRejectsAndRemovesPendingRequest() throws Exception {
        Path spawnFile = tempDir.resolve("timeout-spawns");
        StdioMcpServerConfig config = config(spawnFile, Map.of());
        try (StdioMcpManager manager = new StdioMcpManager(policy("SPAWN_FILE"), Duration.ofMillis(100))) {
            StdioMcpClient client = manager.instance(config);
            client.listTools();
            McpToolClient.McpException error = assertThrows(McpToolClient.McpException.class,
                    () -> client.callTool("hold", mapper.createObjectNode().put("seconds", 2)));
            assertTrue(error.getMessage().contains("timed out"));
            assertEquals(0, client.pendingCount());
        }
    }

    @Test
    void abnormalExitRejectsPendingWithoutImplicitReplay() throws Exception {
        Path spawnFile = tempDir.resolve("exit-spawns");
        StdioMcpServerConfig config = config(spawnFile, Map.of());
        try (StdioMcpManager manager = new StdioMcpManager(policy("SPAWN_FILE"), Duration.ofSeconds(3))) {
            StdioMcpClient client = manager.instance(config);
            client.listTools();
            assertThrows(McpToolClient.McpException.class,
                    () -> client.callTool("exit", mapper.createObjectNode()));
            await(() -> client.isClosed() && client.pendingCount() == 0);
            assertEquals(1, Files.readAllLines(spawnFile).size());
            assertEquals(0, manager.instanceCount());

            manager.listTools(config);
            assertEquals(2, Files.readAllLines(spawnFile).size());
        }
    }

    @Test
    void registeredToolCancellationDoesNotReplayOrKillSharedProcess() throws Exception {
        Path spawnFile = tempDir.resolve("cancel-spawns");
        StdioMcpServerConfig config = config(spawnFile, Map.of());
        try (StdioMcpManager manager = new StdioMcpManager(policy("SPAWN_FILE"), Duration.ofSeconds(3));
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PluginManager plugins = new PluginManager();
            manager.registerTools("test", config, plugins);
            StdioMcpClient client = manager.instance(config);
            ToolExecutionContext.CancellationSource cancellation = new ToolExecutionContext.CancellationSource();
            ToolExecutionContext context = ToolExecutionContext.builder("flow", "session")
                    .cancellation(cancellation).build();
            ToolCall call = new ToolCall("call-hold", "c_test_hold",
                    mapper.createObjectNode().put("seconds", 1));
            Future<ToolResult> future = executor.submit(() -> plugins.execute(context, call));
            Thread.sleep(100);
            cancellation.cancel();

            ToolResult result = future.get(2, TimeUnit.SECONDS);
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("cancelled"));
            assertEquals(0, client.pendingCount());
            assertTrue(client.isAlive());
            assertEquals(1, Files.readAllLines(spawnFile).size());
            plugins.close();
        }
    }

    @Test
    void startupFailureRejectsInitializationAndCleansInstance() throws Exception {
        StdioMcpServerConfig missing = StdioMcpServerConfig.of("python3",
                List.of(tempDir.resolve("missing.py").toString()), Map.of());
        try (StdioMcpManager manager = new StdioMcpManager(policy(), Duration.ofSeconds(2))) {
            assertThrows(Exception.class, () -> manager.listTools(missing));
            await(() -> manager.instanceCount() == 0);
        }
    }

    private StdioMcpPolicy policy(String... environment) {
        return StdioMcpPolicy.builder(tempDir)
                .allowCommand("python3")
                .allowEnvironment(environment)
                .inheritedEnvironment(Set.of("PATH"))
                .build();
    }

    private StdioMcpServerConfig config(Path spawnFile, Map<String, String> extraEnvironment) {
        Map<String, String> environment = new java.util.LinkedHashMap<>(extraEnvironment);
        environment.put("SPAWN_FILE", spawnFile.toString());
        return StdioMcpServerConfig.of("python3", List.of("-u", "-c", SERVER_SCRIPT), environment);
    }

    private static void assertConfigEquals(StdioMcpServerConfig expected, StdioMcpServerConfig actual) {
        assertEquals(expected.command(), actual.command());
        assertEquals(expected.args(), actual.args());
        assertEquals(expected.env(), actual.env());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("Condition was not met before timeout");
            }
            Thread.sleep(20);
        }
    }
}
