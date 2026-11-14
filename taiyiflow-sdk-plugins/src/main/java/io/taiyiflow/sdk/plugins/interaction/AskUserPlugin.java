package io.taiyiflow.sdk.plugins.interaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.tool;

public final class AskUserPlugin {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final UserInteractionHandler handler;

    private AskUserPlugin(UserInteractionHandler handler) {
        this.handler = handler;
    }

    public static void register(PluginManager plugins, UserInteractionHandler handler) {
        if (handler == null) return;
        AskUserPlugin plugin = new AskUserPlugin(handler);
        String schema = "{\"questions\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"id\":{\"type\":\"string\"},\"prompt\":{\"type\":\"string\"},"
                + "\"options\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                + "\"multi_select\":{\"type\":\"boolean\"}},\"required\":[\"id\",\"prompt\"]}},"
                + "\"images\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"media_type\":{\"type\":\"string\"},\"base64_data\":{\"type\":\"string\"}},"
                + "\"required\":[\"media_type\",\"base64_data\"]}},\"timeout_seconds\":{\"type\":\"integer\"}}";
        plugins.register(tool("ask_user", "Ask structured questions through the injected user interaction handler.",
                schema, "questions"), (PluginManager.ContextualToolHandler) plugin::ask);
    }

    private ToolResult ask(ToolExecutionContext context, ToolCall call) {
        ToolExecutionContext.CancellationSource local = new ToolExecutionContext.CancellationSource();
        ToolExecutionContext.CancellationRegistration parentRegistration = context.getCancellation().onCancel(local::cancel);
        CompletableFuture<UserInteractionHandler.Response> future = null;
        ToolExecutionContext.CancellationRegistration futureCancellation = null;
        try {
            JsonNode arguments = call.getArguments();
            RequestData data = parse(arguments);
            UserInteractionHandler.Request request = new UserInteractionHandler.Request(context.getFlowId(),
                    context.getSessionId(), context.getRunId(), context.getTaskId(), data.questions, data.images,
                    data.timeout);
            CompletableFuture<UserInteractionHandler.Response> pending = handler.ask(request, local).toCompletableFuture();
            future = pending;
            futureCancellation = local.onCancel(() -> pending.cancel(true));
            long deadline = System.nanoTime() + data.timeout.toNanos();
            while (true) {
                try {
                    UserInteractionHandler.Response response = future.get(50, TimeUnit.MILLISECONDS);
                    ObjectNode result = MAPPER.createObjectNode();
                    result.put("cancelled", response.cancelled());
                    ObjectNode answers = result.putObject("answers");
                    Set<String> validIds = new HashSet<>();
                    data.questions.forEach(question -> validIds.add(question.id()));
                    int characters = 0;
                    for (var entry : response.answers().entrySet()) {
                        if (!validIds.contains(entry.getKey()) || entry.getValue().size() > 20) {
                            throw new IllegalArgumentException("Interaction response contains an unknown question or too many answers");
                        }
                        ArrayNode values = answers.putArray(entry.getKey());
                        for (String value : entry.getValue()) {
                            if (value == null || value.length() > 4000) {
                                throw new IllegalArgumentException("Interaction answer exceeds the length limit");
                            }
                            characters += value.length();
                            if (characters > 64_000) throw new IllegalArgumentException("Interaction response is too large");
                            values.add(value);
                        }
                    }
                    return ToolResult.success(call.getId(), result);
                } catch (TimeoutException exception) {
                    if (System.nanoTime() >= deadline) {
                        local.cancel();
                        throw new TimeoutException("User interaction timed out");
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            local.cancel();
            return ToolResult.error(call.getId(), "User interaction interrupted");
        } catch (ExecutionException exception) {
            local.cancel();
            return ToolResult.error(call.getId(), "User interaction failed");
        } catch (Exception exception) {
            local.cancel();
            return ToolResult.error(call.getId(), exception.getMessage());
        } finally {
            if (futureCancellation != null) futureCancellation.close();
            parentRegistration.close();
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }

    private static RequestData parse(JsonNode arguments) {
        JsonNode questionsNode = arguments == null ? null : arguments.get("questions");
        if (questionsNode == null || !questionsNode.isArray() || questionsNode.isEmpty()
                || questionsNode.size() > 10) {
            throw new IllegalArgumentException("ask_user requires 1 to 10 questions");
        }
        List<UserInteractionHandler.Question> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode item : questionsNode) {
            String id = item.path("id").asText("");
            String prompt = item.path("prompt").asText("").trim();
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id) || prompt.isEmpty() || prompt.length() > 4000) {
                throw new IllegalArgumentException("Invalid or duplicate question");
            }
            List<String> options = new ArrayList<>();
            if (item.path("options").isArray()) {
                if (item.path("options").size() > 20) throw new IllegalArgumentException("Too many question options");
                item.path("options").forEach(option -> {
                    String value = option.asText();
                    if (value.length() > 500) throw new IllegalArgumentException("Question option is too long");
                    options.add(value);
                });
            }
            questions.add(new UserInteractionHandler.Question(id, prompt, options,
                    item.path("multi_select").asBoolean(false)));
        }
        List<UserInteractionHandler.Image> images = new ArrayList<>();
        int imageCharacters = 0;
        JsonNode imagesNode = arguments.path("images");
        if (imagesNode.isArray()) {
            if (imagesNode.size() > 4) throw new IllegalArgumentException("Too many interaction images");
            for (JsonNode image : imagesNode) {
                String mediaType = image.path("media_type").asText("");
                String data = image.path("base64_data").asText("");
                if (!mediaType.matches("image/(png|jpeg|webp)") || data.isBlank()) {
                    throw new IllegalArgumentException("Unsupported interaction image");
                }
                imageCharacters += data.length();
                if (imageCharacters > 2_000_000) throw new IllegalArgumentException("Interaction images are too large");
                images.add(new UserInteractionHandler.Image(mediaType, data));
            }
        }
        int timeoutSeconds = arguments.path("timeout_seconds").asInt(300);
        if (timeoutSeconds < 1 || timeoutSeconds > 1800) throw new IllegalArgumentException("Invalid interaction timeout");
        return new RequestData(questions, images, Duration.ofSeconds(timeoutSeconds));
    }

    private record RequestData(List<UserInteractionHandler.Question> questions,
                               List<UserInteractionHandler.Image> images, Duration timeout) {
    }
}
