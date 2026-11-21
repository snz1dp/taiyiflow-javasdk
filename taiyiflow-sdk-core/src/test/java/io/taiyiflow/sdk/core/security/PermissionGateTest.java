package io.taiyiflow.sdk.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolDispatcher;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionGateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolCall call(String name, String command) {
        ObjectNode args = mapper.createObjectNode();
        if (command != null) {
            args.put("command", command);
        }
        return new ToolCall("tc-1", name, args);
    }

    private PermissionGate gate(PermissionPolicy policy, PermissionRule... rules) {
        return new PermissionGate(PermissionSettings.builder()
                .policy(policy)
                .rules(List.of(rules))
                .build());
    }

    private PermissionHook.PermissionResult decide(PermissionGate gate, ToolCall call) {
        return gate.evaluate("s-1", call).join();
    }

    // --- three-policy compatibility (defaults mirror PermissionPolicy enum) ---

    @Test
    void readonlyDeniesMutatingAllowsReads() {
        PermissionGate gate = gate(PermissionPolicy.READONLY);
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call("shell", "ls")).decision());
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("read_file", null)).decision());
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call("write_file", null)).decision());
    }

    @Test
    void confirmAsksForMutatingAllowsReads() {
        PermissionGate gate = gate(PermissionPolicy.CONFIRM);
        assertEquals(PermissionHook.Decision.ASK, decide(gate, call("shell", "ls")).decision());
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("read_file", null)).decision());
    }

    @Test
    void autoAllowsBenignButGuardsDangerous() {
        PermissionGate gate = gate(PermissionPolicy.AUTO);
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("shell", "ls -la")).decision());
        PermissionHook.PermissionResult sudo = decide(gate, call("shell", "sudo ls"));
        assertEquals(PermissionHook.Decision.DENY, sudo.decision());
        assertNotNull(sudo.reason());
        assertFalse(sudo.reason().isBlank());
    }

    // --- rule priority and matching ---

    @Test
    void denyRuleBeatsAskAndAllowRules() {
        PermissionGate gate = gate(PermissionPolicy.AUTO,
                PermissionRule.of("allow", "shell", null),
                PermissionRule.of("ask", "shell", null),
                PermissionRule.of("deny", "shell", null));
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call("shell", "ls")).decision());
    }

    @Test
    void askRuleBeatsAllowRule() {
        PermissionGate gate = gate(PermissionPolicy.AUTO,
                PermissionRule.of("allow", "shell", null),
                PermissionRule.of("ask", "shell", null));
        assertEquals(PermissionHook.Decision.ASK, decide(gate, call("shell", "ls")).decision());
    }

    @Test
    void explicitDenyAppliesBeforeAutoAllow() {
        PermissionGate gate = gate(PermissionPolicy.AUTO, PermissionRule.of("deny", "shell", null));
        PermissionHook.PermissionResult result = decide(gate, call("shell", "ls"));
        assertEquals(PermissionHook.Decision.DENY, result.decision());
        assertTrue(result.reason().contains("命中权限规则"));
    }

    @Test
    void explicitAskAppliesUnderAuto() {
        PermissionGate gate = gate(PermissionPolicy.AUTO, PermissionRule.of("ask", "shell", null));
        assertEquals(PermissionHook.Decision.ASK, decide(gate, call("shell", "ls")).decision());
    }

    @Test
    void explicitAllowBypassesConfirmForMutatingShell() {
        PermissionGate gate = gate(PermissionPolicy.CONFIRM, PermissionRule.of("allow", "shell", null));
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("shell", "ls")).decision());
    }

    @Test
    void commandPrefixRuleMatchesOnlyMatchingCommands() {
        PermissionGate gate = gate(PermissionPolicy.AUTO, PermissionRule.of("deny", null, "rm"));
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call("shell", "rm -rf build")).decision());
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("shell", "ls")).decision());
    }

    @Test
    void commandPrefixRuleRequiresToolNameWhenBothSet() {
        PermissionGate gate = gate(PermissionPolicy.AUTO, PermissionRule.of("deny", "shell", "git push"));
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call("shell", "git push origin main")).decision());
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("shell", "git status")).decision());
    }

    @Test
    void ruleNormalizationDropsEmptyAndDefaultsUnknownActionToAsk() {
        assertNull(PermissionRule.of("allow", " ", null));
        PermissionRule rule = PermissionRule.of("bogus", " Shell ", " RM ");
        assertEquals(PermissionRule.Action.ASK, rule.action());
        assertEquals("shell", rule.toolName());
        assertEquals("rm", rule.commandPrefix());
    }

    // --- opaque commands ---

    @Test
    void opaqueCommandUnderAutoIsGuardedThenAllowed() {
        PermissionGate gate = gate(PermissionPolicy.AUTO);
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("shell", "echo $(date)")).decision());
    }

    @Test
    void opaqueCommandWithDangerousCandidateIsDeniedUnderAuto() {
        PermissionGate gate = gate(PermissionPolicy.AUTO);
        PermissionHook.PermissionResult result = decide(gate, call("shell", "sudo ls $(date)"));
        assertEquals(PermissionHook.Decision.DENY, result.decision());
        assertNotNull(result.reason());
    }

    @Test
    void opaqueCommandUnderConfirmAsksWithReasons() {
        PermissionGate gate = gate(PermissionPolicy.CONFIRM);
        PermissionHook.PermissionResult result = decide(gate, call("shell", "echo $(date)"));
        assertEquals(PermissionHook.Decision.ASK, result.decision());
        assertTrue(result.reason().contains("命令无法完全解析"));
        assertTrue(result.reason().contains("command-substitution"));
    }

    @Test
    void opaqueBeatsExplicitAllowUnderConfirm() {
        PermissionGate gate = gate(PermissionPolicy.CONFIRM, PermissionRule.of("allow", "shell", null));
        assertEquals(PermissionHook.Decision.ASK, decide(gate, call("shell", "echo $(date)")).decision());
    }

    @Test
    void explicitDenyBeatsOpaqueHandling() {
        PermissionGate gate = gate(PermissionPolicy.AUTO, PermissionRule.of("deny", "shell", null));
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call("shell", "echo $(date)")).decision());
    }

    // --- write confirmation ---

    @Test
    void writeConfirmationConfirmAsks() {
        PermissionGate gate = gate(PermissionPolicy.AUTO);
        assertEquals(PermissionHook.Decision.ASK, decide(gate, call("file_edit", null)).decision());
        assertEquals(PermissionHook.Decision.ASK, decide(gate, call("write_file", null)).decision());
    }

    @Test
    void writeConfirmationDirectAllows() {
        PermissionGate gate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.AUTO)
                .writeConfirmation(WriteConfirmation.DIRECT)
                .build());
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("file_edit", null)).decision());
    }

    @Test
    void writeConfirmationDenyRuleWinsOverDirect() {
        PermissionGate gate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.AUTO)
                .writeConfirmation(WriteConfirmation.DIRECT)
                .addRule(PermissionRule.of("deny", "write_file", null))
                .build());
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call("write_file", null)).decision());
    }

    @Test
    void readonlyDeniesWriteBeforeWriteConfirmation() {
        PermissionGate gate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.READONLY)
                .writeConfirmation(WriteConfirmation.DIRECT)
                .build());
        PermissionHook.PermissionResult result = decide(gate, call("write_file", null));
        assertEquals(PermissionHook.Decision.DENY, result.decision());
        assertTrue(result.reason().contains("只读模式"));
    }

    // --- browser / computer branch ---

    @Test
    void browserReadonlySuffixAllowedEvenUnderReadonly() {
        PermissionGate gate = gate(PermissionPolicy.READONLY);
        assertEquals(PermissionHook.Decision.ALLOW,
                decide(gate, call("taiyiflow-in-chrome__read_text", null)).decision());
        assertEquals(PermissionHook.Decision.ALLOW,
                decide(gate, call("taiyiflow-computer__screenshot", null)).decision());
    }

    @Test
    void readonlyDeniesMutatingBrowserTools() {
        PermissionGate gate = gate(PermissionPolicy.READONLY);
        PermissionHook.PermissionResult result = decide(gate, call("taiyiflow-in-chrome__click", null));
        assertEquals(PermissionHook.Decision.DENY, result.decision());
        assertTrue(result.reason().contains("只读模式"));
    }

    @Test
    void browserDefaultModeAsks() {
        PermissionGate gate = gate(PermissionPolicy.CONFIRM);
        assertEquals(PermissionHook.Decision.ASK,
                decide(gate, call("taiyiflow-in-chrome__click", null)).decision());
    }

    @Test
    void browserSkipAllAllows() {
        PermissionGate gate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.CONFIRM)
                .browserPermissionMode(BrowserPermissionMode.SKIP_ALL_PERMISSION_CHECKS)
                .build());
        assertEquals(PermissionHook.Decision.ALLOW,
                decide(gate, call("taiyiflow-in-chrome__click", null)).decision());
    }

    @Test
    void browserFollowAPlanDependsOnEffectivePolicy() {
        PermissionGate confirmGate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.CONFIRM)
                .browserPermissionMode(BrowserPermissionMode.FOLLOW_A_PLAN)
                .build());
        assertEquals(PermissionHook.Decision.ASK,
                decide(confirmGate, call("taiyiflow-in-chrome__click", null)).decision());

        PermissionGate autoGate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.AUTO)
                .browserPermissionMode(BrowserPermissionMode.FOLLOW_A_PLAN)
                .build());
        assertEquals(PermissionHook.Decision.ALLOW,
                decide(autoGate, call("taiyiflow-in-chrome__click", null)).decision());
    }

    @Test
    void browserCategoryOverrideFeedsFollowAPlan() {
        PermissionGate gate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.AUTO)
                .categoryPolicy(ToolCategory.BROWSER, PermissionPolicy.CONFIRM)
                .browserPermissionMode(BrowserPermissionMode.FOLLOW_A_PLAN)
                .build());
        assertEquals(PermissionHook.Decision.ASK,
                decide(gate, call("taiyiflow-in-chrome__click", null)).decision());
    }

    @Test
    void browserDenyRuleWinsOverSkipAll() {
        PermissionGate gate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.AUTO)
                .browserPermissionMode(BrowserPermissionMode.SKIP_ALL_PERMISSION_CHECKS)
                .addRule(PermissionRule.of("deny", "taiyiflow-in-chrome__click", null))
                .build());
        assertEquals(PermissionHook.Decision.DENY,
                decide(gate, call("taiyiflow-in-chrome__click", null)).decision());
    }

    // --- category policies ---

    @Test
    void shellCategoryOverrideRelaxesReadonly() {
        PermissionGate gate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.READONLY)
                .categoryPolicy(ToolCategory.SHELL, PermissionPolicy.AUTO)
                .build());
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("shell", "ls")).decision());
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call("write_file", null)).decision());
    }

    @Test
    void fileCategoryOverrideTightensAuto() {
        PermissionGate gate = new PermissionGate(PermissionSettings.builder()
                .policy(PermissionPolicy.AUTO)
                .categoryPolicy(ToolCategory.FILE, PermissionPolicy.READONLY)
                .build());
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call("write_file", null)).decision());
        assertEquals(PermissionHook.Decision.ALLOW, decide(gate, call("shell", "ls")).decision());
    }

    @Test
    void categoryMappingCoversKnownTools() {
        assertEquals(ToolCategory.SHELL, ToolCategory.of("shell"));
        assertEquals(ToolCategory.SHELL, ToolCategory.of("process_stop"));
        assertEquals(ToolCategory.FILE, ToolCategory.of("file_edit"));
        assertEquals(ToolCategory.BROWSER, ToolCategory.of("taiyiflow-in-chrome__click"));
        assertEquals(ToolCategory.SKILL, ToolCategory.of("skill_fetch"));
        assertEquals(ToolCategory.SCHEDULE, ToolCategory.of("schedule_create"));
        assertNull(ToolCategory.of("unknown_tool"));
    }

    // --- command extraction ---

    @Test
    void arrayCommandIsJoinedForAnalysis() {
        PermissionGate gate = gate(PermissionPolicy.AUTO);
        ObjectNode args = mapper.createObjectNode();
        args.putArray("command").add("sudo").add("ls");
        ToolCall call = new ToolCall("tc-1", "shell", args);
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call).decision());
    }

    @Test
    void rawCommandFallbackIsAnalyzed() {
        PermissionGate gate = gate(PermissionPolicy.AUTO);
        ObjectNode args = mapper.createObjectNode();
        args.put("raw_command", "sudo ls");
        ToolCall call = new ToolCall("tc-1", "shell", args);
        assertEquals(PermissionHook.Decision.DENY, decide(gate, call).decision());
    }

    // --- tamper-proof immutability ---

    @Test
    void settingsAreImmutableAndDefensivelyCopied() {
        List<PermissionRule> source = new ArrayList<>();
        source.add(PermissionRule.of("deny", "shell", null));
        PermissionSettings settings = PermissionSettings.builder().rules(source).build();
        source.clear();
        assertEquals(1, settings.getRules().size());
        assertThrows(UnsupportedOperationException.class,
                () -> settings.getRules().add(PermissionRule.of("allow", "shell", null)));
        assertThrows(UnsupportedOperationException.class,
                () -> settings.getCategoryPolicies().put(ToolCategory.SHELL, PermissionPolicy.AUTO));
    }

    @Test
    void defaultsAreReadonlyConfirmAsk() {
        PermissionSettings defaults = PermissionSettings.defaults();
        assertEquals(PermissionPolicy.READONLY, defaults.getPolicy());
        assertEquals(WriteConfirmation.CONFIRM, defaults.getWriteConfirmation());
        assertEquals(BrowserPermissionMode.ASK, defaults.getBrowserPermissionMode());
        assertTrue(defaults.getRules().isEmpty());
        assertTrue(defaults.getCategoryPolicies().isEmpty());
    }

    // --- dispatcher integration: deny reason reaches the model ---

    @Test
    void dispatcherSurfacesGuardDenyReason() {
        PluginManager pm = new PluginManager();
        pm.register("shell", call -> ToolResult.success(call.getId(), mapper.createObjectNode()));
        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(), List.of(gate(PermissionPolicy.AUTO)));
        ToolResult result = dispatcher.dispatch("s-1", call("shell", "sudo ls"));
        assertFalse(result.isSuccess());
        assertNotEquals("Permission denied by hook", result.getError());
        assertFalse(result.getError().isBlank());
    }

    @Test
    void dispatcherKeepsLegacyDenyMessageForPlainHooks() {
        PluginManager pm = new PluginManager();
        PermissionHook denyHook = (sessionId, toolCall) ->
                java.util.concurrent.CompletableFuture.completedFuture(PermissionHook.Decision.DENY);
        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(), List.of(denyHook));
        ToolResult result = dispatcher.dispatch("s-1", call("shell", "ls"));
        assertFalse(result.isSuccess());
        assertEquals("Permission denied by hook", result.getError());
    }
}