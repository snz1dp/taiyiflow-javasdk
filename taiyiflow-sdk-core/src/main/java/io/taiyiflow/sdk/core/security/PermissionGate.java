package io.taiyiflow.sdk.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.transport.envelope.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Fine-grained permission decision engine ported from taiyiflow-electron security.js
 * evaluateApproval/evaluatePermissionAction, with documented Java deviations:
 * <ul>
 *   <li>Java has no "unclassified → confirm" bucket; classification reuses
 *       {@link PermissionPolicy#isMutating(String)} (non-mutating → allow).</li>
 *   <li>READONLY also denies mutating browser/computer tools (stricter than Electron).</li>
 *   <li>The dangerous-command guard always applies: the Java shell runtime is always sandboxed,
 *       so Electron's sandbox-off networkDownload exemption does not exist here.</li>
 *   <li>skill_fetch review policy is not ported; Java skill supply has its own risk gating.</li>
 * </ul>
 */
public final class PermissionGate implements PermissionHook {

    private static final String BROWSER_PREFIX = "taiyiflow-in-chrome__";
    private static final String COMPUTER_PREFIX = "taiyiflow-computer__";

    private final PermissionSettings settings;

    public PermissionGate(PermissionSettings settings) {
        this.settings = settings;
    }

    public PermissionSettings getSettings() {
        return settings;
    }

    @Override
    public CompletableFuture<Decision> onPermission(String sessionId, ToolCall toolCall) {
        return evaluate(sessionId, toolCall).thenApply(PermissionResult::decision);
    }

    @Override
    public CompletableFuture<PermissionResult> evaluate(String sessionId, ToolCall toolCall) {
        return CompletableFuture.completedFuture(decide(toolCall));
    }

    public PermissionResult decide(ToolCall toolCall) {
        String name = toolCall.getName() == null ? "" : toolCall.getName();
        String lowerName = name.toLowerCase(Locale.ROOT);
        boolean mutating = PermissionPolicy.isMutating(name);
        String command = extractCommand(toolCall.getArguments());
        ShellCommandAnalysis.Result analysis = command.isBlank()
                ? null : ShellCommandAnalysis.analyze(command);
        PermissionRule.Action matched = matchRules(lowerName, analysis);

        if (name.startsWith(BROWSER_PREFIX) || name.startsWith(COMPUTER_PREFIX)) {
            return decideBrowser(name, lowerName, mutating, matched);
        }

        PermissionPolicy effective = effectivePolicy(name);
        if (effective == PermissionPolicy.READONLY && mutating) {
            return deny("只读模式禁止执行该工具 (" + name + ")");
        }

        if (lowerName.equals("file_edit") || lowerName.equals("write_file")) {
            if (matched == PermissionRule.Action.DENY) {
                return deny("工具 " + name + " 命中权限规则 (deny)");
            }
            if (settings.getWriteConfirmation() == WriteConfirmation.DIRECT) {
                return allow();
            }
            return ask("写入操作需要用户确认 (" + name + ")");
        }

        if (matched == PermissionRule.Action.DENY) {
            return deny(ruleDenyReason(name, command, analysis));
        }
        if (matched == PermissionRule.Action.ASK) {
            return ask("命中权限规则 (ask)");
        }

        if (analysis != null && analysis.opaque()) {
            if (effective == PermissionPolicy.AUTO) {
                String guardMessage = DangerousCommandGuard.guard(command, analysis);
                return guardMessage != null ? deny(guardMessage) : allow();
            }
            return ask("命令无法完全解析 (" + String.join(", ", analysis.reasons())
                    + ")，需确认未覆盖的命令规则");
        }

        if (matched == PermissionRule.Action.ALLOW) {
            return allow();
        }

        if (effective == PermissionPolicy.AUTO) {
            if (analysis != null) {
                String guardMessage = DangerousCommandGuard.guard(command, analysis);
                if (guardMessage != null) {
                    return deny(guardMessage);
                }
            }
            return allow();
        }
        if (!mutating) {
            return allow();
        }
        return ask(null);
    }

    private PermissionResult decideBrowser(String name, String lowerName,
                                           boolean mutating, PermissionRule.Action matched) {
        if (matched == PermissionRule.Action.DENY) {
            return deny("工具 " + name + " 命中权限规则 (deny)");
        }
        if (matched == PermissionRule.Action.ASK) {
            return ask("命中权限规则 (ask)");
        }
        if (!mutating) {
            return allow();
        }
        PermissionPolicy effective = settings.getCategoryPolicies()
                .getOrDefault(ToolCategory.BROWSER, settings.getPolicy());
        if (effective == PermissionPolicy.READONLY) {
            return deny("只读模式禁止执行该工具 (" + name + ")");
        }
        if (matched == PermissionRule.Action.ALLOW) {
            return allow();
        }
        return switch (settings.getBrowserPermissionMode()) {
            case SKIP_ALL_PERMISSION_CHECKS -> allow();
            case FOLLOW_A_PLAN -> effective == PermissionPolicy.CONFIRM ? ask(null) : allow();
            case ASK -> ask(null);
        };
    }

    private PermissionPolicy effectivePolicy(String toolName) {
        ToolCategory category = ToolCategory.of(toolName);
        if (category == null) {
            return settings.getPolicy();
        }
        return settings.getCategoryPolicies().getOrDefault(category, settings.getPolicy());
    }

    private PermissionRule.Action matchRules(String lowerName, ShellCommandAnalysis.Result analysis) {
        PermissionRule.Action best = null;
        for (PermissionRule rule : settings.getRules()) {
            if (!ruleMatches(rule, lowerName, analysis)) {
                continue;
            }
            if (rule.action() == PermissionRule.Action.DENY) {
                return PermissionRule.Action.DENY;
            }
            if (rule.action() == PermissionRule.Action.ASK
                    || best == null) {
                best = rule.action();
            }
        }
        return best;
    }

    private static boolean ruleMatches(PermissionRule rule, String lowerName,
                                       ShellCommandAnalysis.Result analysis) {
        boolean hasName = !rule.toolName().isEmpty();
        boolean hasPrefix = !rule.commandPrefix().isEmpty();
        if (hasName && !rule.toolName().equals(lowerName)) {
            return false;
        }
        if (hasPrefix) {
            if (analysis == null) {
                return false;
            }
            boolean prefixMatched = false;
            for (ShellCommandAnalysis.Candidate candidate : analysis.candidates()) {
                if (ShellCommandAnalysis.matchesCommandPrefix(candidate, rule.commandPrefix())) {
                    prefixMatched = true;
                    break;
                }
            }
            if (!prefixMatched) {
                return false;
            }
        }
        return true;
    }

    private static String ruleDenyReason(String name, String command,
                                         ShellCommandAnalysis.Result analysis) {
        if (analysis != null && !command.isBlank()) {
            String display = command.length() > 60 ? command.substring(0, 60) + "…" : command;
            return "命令 \"" + display + "\" 命中权限规则 (deny)";
        }
        return "工具 " + name + " 命中权限规则 (deny)";
    }

    private static String extractCommand(JsonNode arguments) {
        if (arguments == null) {
            return "";
        }
        JsonNode command = arguments.get("command");
        if (command == null || command.isNull()) {
            command = arguments.get("raw_command");
        }
        if (command == null || command.isNull()) {
            return "";
        }
        if (command.isTextual()) {
            return command.asText();
        }
        if (command.isArray()) {
            List<String> parts = new ArrayList<>();
            command.forEach(node -> parts.add(node.asText()));
            return String.join(" ", parts);
        }
        return "";
    }

    private static PermissionResult allow() {
        return new PermissionResult(Decision.ALLOW, null);
    }

    private static PermissionResult deny(String reason) {
        return new PermissionResult(Decision.DENY, reason);
    }

    private static PermissionResult ask(String reason) {
        return new PermissionResult(Decision.ASK, reason);
    }
}