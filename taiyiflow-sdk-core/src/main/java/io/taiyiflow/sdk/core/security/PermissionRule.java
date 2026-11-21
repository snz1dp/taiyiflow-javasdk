package io.taiyiflow.sdk.core.security;

import java.util.Locale;

/**
 * One permission rule, mirroring permission_rules entries in taiyiflow-electron security.js:
 * an action applied to a tool name and/or a shell command prefix. Rules are immutable and can only
 * originate from SDK configuration, never from skill content or model arguments.
 */
public record PermissionRule(Action action, String toolName, String commandPrefix) {

    public enum Action {
        ALLOW, DENY, ASK
    }

    /**
     * Normalizes raw configuration values like Electron setPermissionRules: unknown actions fall
     * back to ASK, names/prefixes are lowercased and trimmed. Returns null when both selectors are
     * empty (such entries are dropped by Electron as well).
     */
    public static PermissionRule of(String action, String toolName, String commandPrefix) {
        String normalizedAction = action == null ? "" : action.toLowerCase(Locale.ROOT).trim();
        Action parsed = switch (normalizedAction) {
            case "allow" -> Action.ALLOW;
            case "deny" -> Action.DENY;
            default -> Action.ASK;
        };
        String name = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT).trim();
        String prefix = commandPrefix == null ? "" : commandPrefix.toLowerCase(Locale.ROOT).trim();
        if (name.isEmpty() && prefix.isEmpty()) {
            return null;
        }
        return new PermissionRule(parsed, name, prefix);
    }
}