package io.taiyiflow.sdk.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionPolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void autoAllowsReadsAndWrites() throws Exception {
        assertEquals(PermissionHook.Decision.ALLOW, decision(PermissionPolicy.AUTO, "read_file"));
        assertEquals(PermissionHook.Decision.ALLOW, decision(PermissionPolicy.AUTO, "write_file"));
    }

    @Test
    void confirmOnlyAsksForMutatingTools() throws Exception {
        assertEquals(PermissionHook.Decision.ALLOW, decision(PermissionPolicy.CONFIRM, "read_file"));
        assertEquals(PermissionHook.Decision.ASK, decision(PermissionPolicy.CONFIRM, "write_file"));
        assertEquals(PermissionHook.Decision.ASK, decision(PermissionPolicy.CONFIRM, "shell_exec"));
        assertEquals(PermissionHook.Decision.ASK, decision(PermissionPolicy.CONFIRM, "process_stop"));
        assertEquals(PermissionHook.Decision.ALLOW, decision(PermissionPolicy.CONFIRM, "process_output"));
    }

    @Test
    void readonlyRejectsMutatingTools() throws Exception {
        assertEquals(PermissionHook.Decision.ALLOW, decision(PermissionPolicy.READONLY, "search"));
        assertEquals(PermissionHook.Decision.DENY, decision(PermissionPolicy.READONLY, "shell"));
        assertEquals(PermissionHook.Decision.DENY, decision(PermissionPolicy.READONLY, "file_edit"));
    }

    private PermissionHook.Decision decision(PermissionPolicy policy, String toolName) throws Exception {
        ToolCall call = new ToolCall("call-1", toolName, mapper.createObjectNode());
        return policy.onPermission("session-1", call).get(1, TimeUnit.SECONDS);
    }
}
