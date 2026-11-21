package io.taiyiflow.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestDefaultsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void defaultsMatchDecidedSessionSemantics() throws Exception {
        ChatRequest request = new ChatRequest("hi", "s");
        RequestDefaults.defaults().applyTo(request);
        assertEquals("chat", request.getInputType());
        assertEquals("chat", request.getOutputType());
        assertEquals(Boolean.FALSE, request.getAgentAutoLoopEnabled());
        assertNull(request.getImageGenerationEnabled());
        assertNull(request.getModel());
        assertNull(request.getReasoning());
        assertNull(request.getInternetSearch());
        assertNull(request.getRelationSessions());
        assertNull(request.getLongTermMemoryEnabled());
        assertNull(request.getCrossSessionMemoryEnabled());
        String json = MAPPER.writeValueAsString(request);
        assertFalse(json.contains("image_generation_enabled"));
        assertFalse(json.contains("relation_sessions"));
        assertTrue(json.contains("\"agent_auto_loop_enabled\":false"));
    }

    @Test
    void explicitRequestValuesWin() throws Exception {
        ChatRequest request = new ChatRequest("hi", "s");
        request.setInputType("text");
        request.setOutputType("text");
        request.setModel("explicit-model");
        request.setAgentAutoLoopEnabled(true);
        request.setImageGenerationEnabled(false);
        RequestDefaults.builder()
                .inputType("chat").outputType("chat")
                .model("default-model")
                .autoLoopEnabled(false)
                .imageGeneration(RequestDefaults.ImageGeneration.ON)
                .build()
                .applyTo(request);
        assertEquals("text", request.getInputType());
        assertEquals("text", request.getOutputType());
        assertEquals("explicit-model", request.getModel());
        assertEquals(Boolean.TRUE, request.getAgentAutoLoopEnabled());
        assertEquals(Boolean.FALSE, request.getImageGenerationEnabled());
    }

    @Test
    void configuredDefaultsFillUnsetFields() throws Exception {
        ChatRequest request = new ChatRequest("hi", "s");
        RequestDefaults.builder()
                .requestType("agent")
                .model("qwen-max")
                .reasoning(true)
                .internetSearch(true)
                .knowledgeScopes(MAPPER.readTree("[\"k1\"]"))
                .knowledgeDocs(MAPPER.readTree("[\"d1\"]"))
                .autoLoopEnabled(true)
                .autoLoopMaxRounds(5)
                .imageGeneration(RequestDefaults.ImageGeneration.OFF)
                .groupId("group-1")
                .relationSessions(List.of("a", "b"))
                .agentAppId("app-1")
                .agentAppOwnerId("owner-1")
                .clientContextDir("ctx")
                .longTermMemoryEnabled(true)
                .crossSessionMemoryEnabled(false)
                .build()
                .applyTo(request);
        assertEquals("agent", request.getRequestType());
        assertEquals("qwen-max", request.getModel());
        assertEquals(Boolean.TRUE, request.getReasoning());
        assertEquals(Boolean.TRUE, request.getInternetSearch());
        assertEquals("k1", request.getKnowledgeScopes().get(0).asText());
        assertEquals("d1", request.getKnowledgeDocs().get(0).asText());
        assertEquals(Boolean.TRUE, request.getAgentAutoLoopEnabled());
        assertEquals(5, request.getAgentAutoLoopMaxRounds());
        assertEquals(Boolean.FALSE, request.getImageGenerationEnabled());
        assertEquals("group-1", request.getGroupId());
        assertEquals(List.of("a", "b"), request.getRelationSessions());
        assertEquals("app-1", request.getAgentAppId());
        assertEquals("owner-1", request.getAgentAppOwnerId());
        assertEquals("ctx", request.getClientContextDir());
        assertEquals(Boolean.TRUE, request.getLongTermMemoryEnabled());
        assertEquals(Boolean.FALSE, request.getCrossSessionMemoryEnabled());
    }

    @Test
    void knowledgeNodesAreDefensivelyCopied() throws Exception {
        var scopes = MAPPER.readTree("[\"k1\"]");
        RequestDefaults defaults = RequestDefaults.builder().knowledgeScopes(scopes).build();
        ChatRequest request = new ChatRequest("hi", "s");
        defaults.applyTo(request);
        ((com.fasterxml.jackson.databind.node.ArrayNode) request.getKnowledgeScopes()).removeAll();
        assertEquals("k1", defaults.getKnowledgeScopes().get(0).asText());
    }

    @Test
    void validatesAutoLoopRoundsAndRelationSessions() {
        assertThrows(IllegalArgumentException.class,
                () -> RequestDefaults.builder().autoLoopMaxRounds(-1));
        assertThrows(IllegalArgumentException.class,
                () -> RequestDefaults.builder().relationSessions(List.of("a", "b", "c", "d", "e", "f")));
        assertThrows(IllegalArgumentException.class,
                () -> RequestDefaults.builder().relationSessions(List.of(" ")));
        assertThrows(IllegalArgumentException.class,
                () -> RequestDefaults.builder().inputType(" "));
        RequestDefaults normalized = RequestDefaults.builder()
                .relationSessions(List.of(" a ", "a", "b")).build();
        assertEquals(List.of("a", "b"), normalized.getRelationSessions());
    }
}