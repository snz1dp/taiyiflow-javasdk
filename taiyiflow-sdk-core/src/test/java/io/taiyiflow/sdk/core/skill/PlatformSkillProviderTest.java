package io.taiyiflow.sdk.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.transport.rest.SkillClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformSkillProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void cacheSeparatesPrincipalsAndCoalescesConcurrentLoads() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SkillClient client = new SkillClient(null) {
            @Override public JsonNode list(Map<String, ?> context) {
                var response = MAPPER.createArrayNode();
                response.addObject().put("id", "skill-id").put("name", "demo").put("display_name", "Demo")
                        .put("summary", "summary").put("version", "1");
                return response;
            }
            @Override public JsonNode getContent(String skillId, Map<String, ?> context) {
                calls.incrementAndGet();
                return MAPPER.createObjectNode().put("content", "---\nname: demo\n---\nbody");
            }
        };
        PlatformSkillProvider provider = new PlatformSkillProvider(client, SkillLimits.defaults(),
                Duration.ofMinutes(5), 8);
        SkillQuery first = query("first");
        List<SkillDefinition> discovered = provider.discover(first);
        assertEquals(1, discovered.size(), String.valueOf(client.list(first == null ? Map.of() : Map.of())));
        SkillDefinition skill = discovered.get(0);
        CompletableFuture<SkillDefinition> one = CompletableFuture.supplyAsync(() -> load(provider, skill, first));
        CompletableFuture<SkillDefinition> two = CompletableFuture.supplyAsync(() -> load(provider, skill, first));
        assertEquals("body", one.join().body());
        assertEquals("body", two.join().body());
        provider.load(skill, query("second"));
        provider.load(skill, first);

        assertEquals(2, calls.get());
    }

    private static SkillDefinition load(PlatformSkillProvider provider, SkillDefinition skill, SkillQuery query) {
        try {
            return provider.load(skill, query);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static SkillQuery query(String principal) {
        return new SkillQuery("flow", "session", "agent", principal, Set.of());
    }
}
