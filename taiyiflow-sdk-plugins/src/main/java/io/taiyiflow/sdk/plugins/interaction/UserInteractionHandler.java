package io.taiyiflow.sdk.plugins.interaction;

import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface UserInteractionHandler {
    CompletionStage<Response> ask(Request request, ToolExecutionContext.Cancellation cancellation);

    record Question(String id, String prompt, List<String> options, boolean multiSelect) {
        public Question {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    record Image(String mediaType, String base64Data) {
    }

    record Request(String flowId, String sessionId, String runId, String taskId,
                   List<Question> questions, List<Image> images, Duration timeout) {
        public Request {
            questions = List.copyOf(questions);
            images = List.copyOf(images);
        }
    }

    record Response(Map<String, List<String>> answers, boolean cancelled) {
        public Response {
            answers = answers == null ? Map.of() : answers.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
        }
    }
}
