package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void constructorSetsFields() {
        ChatRequest request = new ChatRequest("Hello", "session-1");
        assertEquals("Hello", request.getInputValue());
        assertEquals("session-1", request.getSessionId());
        assertNull(request.getTweaks());
    }

    @Test
    void serializeUsesSnakeCase() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-2");
        String json = mapper.writeValueAsString(request);
        assertTrue(json.contains("\"input_value\":\"Hi\""));
        assertTrue(json.contains("\"session_id\":\"s-2\""));
    }

    @Test
    void tweaksSerialized() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-3");
        request.setTweaks(Map.of("node1", mapper.createObjectNode().put("key", "val")));
        String json = mapper.writeValueAsString(request);
        assertTrue(json.contains("\"tweaks\""));
        assertTrue(json.contains("\"node1\""));
    }

    @Test
    void serializesPhaseGFields() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-3");
        request.setRunId("run-1");
        request.setMessageId("message-1");
        request.setInputFiles(List.of("/tmp/a.txt"));
        request.setRequestType("agent");
        request.setAvailableTools(List.of(mapper.readTree("{\"name\":\"shell\"}")));
        request.setClientMcpServers(mapper.readTree("{\"local\":{\"tools\":[]}}"));

        var json = mapper.readTree(mapper.writeValueAsString(request));
        assertEquals("run-1", json.get("run_id").asText());
        assertEquals("message-1", json.get("message_id").asText());
        assertEquals("/tmp/a.txt", json.get("files").get(0).asText());
        assertEquals("agent", json.get("request_type").asText());
        assertEquals("shell", json.get("available_tools").get(0).get("name").asText());
        assertTrue(json.get("client_mcp_servers").has("local"));
    }

    @Test
    void serializesExecutionControlFields() throws Exception {
        ChatRequest request = new ChatRequest(null, "s-3");
        request.setExecutionMode("resume");
        request.setRunId("run-1");
        request.setExecutionAttemptId("attempt-1");
        request.setAfterEventId("event-1");
        request.setSubscriptionId("subscription-1");
        request.setCheckpointId("checkpoint-1");
        request.setCheckpointVersion(3);
        request.setToolResults(List.of(mapper.readTree("{\"call_id\":\"call-1\",\"result\":\"ok\"}")));
        request.setSubmissionId("submission-1");
        request.setClientId("client-1");
        request.setDeviceId("device-1");
        request.setClientType("device");

        var json = mapper.readTree(mapper.writeValueAsString(request));
        assertEquals("resume", json.required("execution_mode").asText());
        assertEquals("attempt-1", json.required("execution_attempt_id").asText());
        assertEquals("event-1", json.required("after_event_id").asText());
        assertEquals("subscription-1", json.required("subscription_id").asText());
        assertEquals("checkpoint-1", json.required("checkpoint_id").asText());
        assertEquals(3, json.required("checkpoint_version").asInt());
        assertEquals("call-1", json.required("tool_results").get(0).required("call_id").asText());
        assertEquals("submission-1", json.required("submission_id").asText());
        assertEquals("client-1", json.required("client_id").asText());
        assertEquals("device-1", json.required("device_id").asText());
        assertEquals("device", json.required("client_type").asText());
    }

    @Test
    void serializesClientSkillPackages() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-3");
        ClientSkillDefinition skill = new ClientSkillDefinition("demo", "demo", "description", null, null,
                List.of(), List.of(), null, "summary", "body", false, null, "demo", null,
                "workspace", "demo", true, "1");
        request.setClientSkillPackages(List.of(new ClientSkillPackage("taiyiflow-workspace", null, false, List.of(skill))));
        request.setRequestedSkillNames(List.of("demo"));

        var json = mapper.readTree(mapper.writeValueAsString(request));
        assertEquals("demo", json.required("requested_skill_names").get(0).asText());
        var serialized = json.required("client_skill_packages").get(0);
        assertEquals("taiyiflow-workspace", serialized.required("package_name").asText());
        assertFalse(serialized.has("root_dir"));
        assertFalse(serialized.get("skills").get(0).has("path"));
        assertEquals("demo", serialized.get("skills").get(0).required("skill_dir").asText());
    }

    @Test
    void omitsNullOptionalFields() throws Exception {
        String json = mapper.writeValueAsString(new ChatRequest("Hi", "s-3"));
        assertFalse(json.contains("run_id"));
        assertFalse(json.contains("files"));
        assertFalse(json.contains("client_mcp_servers"));
    }

    @Test
    void serializesPhaseAhFields() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-ah");
        request.setModel("qwen-max");
        request.setReasoning(true);
        request.setInternetSearch(false);
        request.setAgentAutoLoopEnabled(false);
        request.setAgentAutoLoopMaxRounds(0);
        request.setImageGenerationEnabled(true);
        request.setGroupId("11111111-2222-3333-4444-555555555555");
        request.setRelationSessions(List.of("session-a", "session-b"));
        request.setClientInstructions(mapper.readTree(
                "{\"layers\":[],\"merged\":\"[Instructions from AGENTS.md (project, priority 20)]\\nrule\"}"));
        request.setAgentAppId("app-1");
        request.setAgentAppOwnerId("66666666-7777-8888-9999-000000000000");
        request.setClientContextDir("project-a");
        request.setLongTermMemoryEnabled(true);
        request.setCrossSessionMemoryEnabled(false);

        var json = mapper.readTree(mapper.writeValueAsString(request));
        assertEquals("qwen-max", json.required("model").asText());
        assertTrue(json.required("reasoning").asBoolean());
        assertFalse(json.required("internet_search").asBoolean());
        assertFalse(json.required("agent_auto_loop_enabled").asBoolean());
        assertEquals(0, json.required("agent_auto_loop_max_rounds").asInt());
        assertTrue(json.required("image_generation_enabled").asBoolean());
        assertEquals("11111111-2222-3333-4444-555555555555", json.required("group_id").asText());
        assertEquals("session-a", json.required("relation_sessions").get(0).asText());
        assertEquals("session-b", json.required("relation_sessions").get(1).asText());
        assertTrue(json.required("client_instructions").required("merged").asText()
                .startsWith("[Instructions from AGENTS.md (project, priority 20)]"));
        assertEquals("app-1", json.required("agent_app_id").asText());
        assertEquals("66666666-7777-8888-9999-000000000000", json.required("agent_app_owner_id").asText());
        assertEquals("project-a", json.required("client_context_dir").asText());
        assertTrue(json.required("long_term_memory_enabled").asBoolean());
        assertFalse(json.required("cross_session_memory_enabled").asBoolean());
    }

    @Test
    void omitsPhaseAhFieldsWhenUnset() throws Exception {
        String json = mapper.writeValueAsString(new ChatRequest("Hi", "s-ah"));
        for (String field : List.of("model", "agent_auto_loop_enabled", "agent_auto_loop_max_rounds",
                "image_generation_enabled", "group_id", "relation_sessions", "client_instructions",
                "agent_app_id", "agent_app_owner_id", "client_context_dir",
                "long_term_memory_enabled", "cross_session_memory_enabled")) {
            assertFalse(json.contains("\"" + field + "\""), "unexpected field: " + field);
        }
    }

    @Test
    void serializesPhaseAlFields() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-al");
        request.setRuntimeStrategy("hermes");
        request.setCommandExecutionTarget("client");
        request.setMaxIterations(40);
        request.setAgentContinueWindows(3);
        request.setAgentInputs(mapper.readTree("{\"ticket\":\"T-1\"}"));
        request.setMasterFlowId("master-flow");
        request.setOutputComponent("ChatOutput");
        request.setPrincipalId("principal-1");
        request.setActorPrincipalId("actor-1");

        var json = mapper.readTree(mapper.writeValueAsString(request));
        assertEquals("hermes", json.required("runtime_strategy").asText());
        assertEquals("client", json.required("command_execution_target").asText());
        assertEquals(40, json.required("max_iterations").asInt());
        assertEquals(3, json.required("agent_continue_windows").asInt());
        assertEquals("T-1", json.required("agent_inputs").required("ticket").asText());
        assertEquals("master-flow", json.required("master_flow_id").asText());
        assertEquals("ChatOutput", json.required("output_component").asText());
        assertEquals("principal-1", json.required("principal_id").asText());
        assertEquals("actor-1", json.required("actor_principal_id").asText());
    }

    @Test
    void omitsPhaseAlFieldsWhenUnset() throws Exception {
        String json = mapper.writeValueAsString(new ChatRequest("Hi", "s-al"));
        for (String field : List.of("runtime_strategy", "command_execution_target", "max_iterations",
                "agent_continue_windows", "agent_inputs", "master_flow_id", "output_component",
                "principal_id", "actor_principal_id")) {
            assertFalse(json.contains("\"" + field + "\""), "unexpected field: " + field);
        }
    }

    @Test
    void copyOptionsFromCarriesPhaseAlFields() throws Exception {
        ChatRequest source = new ChatRequest("Hi", "s-al");
        source.setRuntimeStrategy("default");
        source.setCommandExecutionTarget("server");
        source.setMaxIterations(10);
        source.setAgentContinueWindows(0);
        source.setAgentInputs(mapper.readTree("{\"k\":\"v\"}"));
        source.setMasterFlowId("master-flow");
        source.setOutputComponent("ChatOutput");
        source.setPrincipalId("principal-1");
        source.setActorPrincipalId("actor-1");
        ChatRequest target = new ChatRequest();
        target.copyOptionsFrom(source);
        assertEquals("default", target.getRuntimeStrategy());
        assertEquals("server", target.getCommandExecutionTarget());
        assertEquals(10, target.getMaxIterations());
        assertEquals(0, target.getAgentContinueWindows());
        assertEquals("v", target.getAgentInputs().required("k").asText());
        assertEquals("master-flow", target.getMasterFlowId());
        assertEquals("ChatOutput", target.getOutputComponent());
        assertEquals("principal-1", target.getPrincipalId());
        assertEquals("actor-1", target.getActorPrincipalId());
        ((com.fasterxml.jackson.databind.node.ObjectNode) source.getAgentInputs()).put("k", "changed");
        assertEquals("v", target.getAgentInputs().required("k").asText());
    }

    @Test
    void relationSessionsAreStrippedDedupedAndCapped() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-rel");
        request.setRelationSessions(java.util.Arrays.asList(
                " a ", "b", "a", "", null, "   ", "c", "d", "e", "f", "g"));
        assertEquals(List.of("a", "b", "c", "d", "e"), request.getRelationSessions());
        var json = mapper.readTree(mapper.writeValueAsString(request));
        assertEquals(5, json.required("relation_sessions").size());
        assertEquals("a", json.required("relation_sessions").get(0).asText());
    }

    @Test
    void emptyRelationSessionsCollapseToNullAndAreOmitted() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-rel");
        request.setRelationSessions(java.util.Arrays.asList("", "   ", null));
        assertNull(request.getRelationSessions());
        assertFalse(mapper.writeValueAsString(request).contains("relation_sessions"));

        request.setRelationSessions(List.of());
        assertNull(request.getRelationSessions());

        request.setRelationSessions(null);
        assertNull(request.getRelationSessions());
    }

    @Test
    void deserializeRoundTrip() throws Exception {
        ChatRequest original = new ChatRequest("Test", "s-4");
        String json = mapper.writeValueAsString(original);
        ChatRequest parsed = mapper.readValue(json, ChatRequest.class);
        assertEquals("Test", parsed.getInputValue());
        assertEquals("s-4", parsed.getSessionId());
    }
}
