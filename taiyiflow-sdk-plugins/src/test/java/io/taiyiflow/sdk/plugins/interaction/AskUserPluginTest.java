package io.taiyiflow.sdk.plugins.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskUserPluginTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toolIsAbsentWithoutInjectedHandler() {
        PluginManager plugins = new PluginManager();
        AskUserPlugin.register(plugins, null);
        assertFalse(plugins.hasHandler("ask_user"));
    }

    @Test
    void returnsOnlyStructuredAnswersAndPropagatesTrustedContext() {
        PluginManager plugins = new PluginManager();
        AskUserPlugin.register(plugins, (request, cancellation) -> {
            assertEquals("flow-a", request.flowId());
            assertEquals("session-a", request.sessionId());
            assertEquals("question-1", request.questions().get(0).id());
            assertEquals(Duration.ofSeconds(30), request.timeout());
            return CompletableFuture.completedFuture(new UserInteractionHandler.Response(
                    Map.of("question-1", List.of("yes", "details")), false));
        });

        com.fasterxml.jackson.databind.node.ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("timeout_seconds", 30);
        com.fasterxml.jackson.databind.node.ArrayNode questions = MAPPER.createArrayNode();
        questions.addObject().put("id", "question-1").put("prompt", "private prompt");
        arguments.set("questions", questions);
        ToolResult result = execute(plugins, arguments);

        assertTrue(result.isSuccess(), result::getError);
        assertEquals("yes", result.getResult().path("answers").path("question-1").get(0).asText());
        assertFalse(result.getResult().toString().contains("private prompt"));
    }

    @Test
    void timeoutCancelsHandlerAndReleasesExecution() {
        PluginManager plugins = new PluginManager();
        AtomicBoolean cancelled = new AtomicBoolean();
        AskUserPlugin.register(plugins, (request, cancellation) -> {
            cancellation.onCancel(() -> cancelled.set(true));
            return new CompletableFuture<>();
        });

        com.fasterxml.jackson.databind.node.ObjectNode arguments = MAPPER.createObjectNode();
        arguments.put("timeout_seconds", 1);
        com.fasterxml.jackson.databind.node.ArrayNode questions = MAPPER.createArrayNode();
        questions.addObject().put("id", "q").put("prompt", "Continue?");
        arguments.set("questions", questions);
        ToolResult result = execute(plugins, arguments);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("timed out"));
        assertTrue(cancelled.get());
    }

    @Test
    void invalidResponseCannotInjectUnknownFields() {
        PluginManager plugins = new PluginManager();
        AskUserPlugin.register(plugins, (request, cancellation) -> CompletableFuture.completedFuture(
                new UserInteractionHandler.Response(Map.of("other", List.of("value")), false)));
        com.fasterxml.jackson.databind.node.ObjectNode arguments = MAPPER.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode questions = MAPPER.createArrayNode();
        questions.addObject().put("id", "q").put("prompt", "Continue?");
        arguments.set("questions", questions);
        ToolResult result = execute(plugins, arguments);
        assertFalse(result.isSuccess());
    }

    private static ToolResult execute(PluginManager plugins, com.fasterxml.jackson.databind.JsonNode arguments) {
        return plugins.execute(ToolExecutionContext.builder("flow-a", "session-a").build(),
                new ToolCall("1", "ask_user", arguments));
    }
}
