package io.taiyiflow.sdk.core.skill;

import java.util.Set;

public record SkillQuery(String flowId, String sessionId, String requestMode,
                         String principal, Set<String> availableTools) {
    public SkillQuery {
        requestMode = switch (requestMode == null ? "agent" : requestMode) {
            case "ask", "plan" -> requestMode;
            default -> "agent";
        };
        availableTools = availableTools == null ? null : Set.copyOf(availableTools);
    }

    public static SkillQuery of(String flowId, String sessionId, String requestMode) {
        return new SkillQuery(flowId, sessionId, requestMode, flowId + "\0" + sessionId, null);
    }
}
