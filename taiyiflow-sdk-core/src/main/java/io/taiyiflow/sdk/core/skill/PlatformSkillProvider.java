package io.taiyiflow.sdk.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.transport.rest.SkillClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class PlatformSkillProvider implements SkillProvider {
    private final SkillClient client;
    private final SkillDocumentParser parser = new SkillDocumentParser();
    private final SkillLimits limits;
    private final Duration ttl;
    private final int cacheSize;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>();
    private final Map<CacheKey, CompletableFuture<SkillDefinition>> loading = new ConcurrentHashMap<>();

    public PlatformSkillProvider(SkillClient client) {
        this(client, SkillLimits.defaults(), Duration.ofMinutes(5), 64);
    }

    public PlatformSkillProvider(SkillClient client, SkillLimits limits, Duration ttl, int cacheSize) {
        this.client = client;
        this.limits = limits;
        this.ttl = ttl;
        this.cacheSize = cacheSize;
    }

    @Override public SkillSource source() { return SkillSource.PLATFORM; }

    @Override
    public List<SkillDefinition> discover(SkillQuery query) throws Exception {
        JsonNode response = client.list(context(query));
        JsonNode values = response != null && response.isArray() ? response
                : response == null ? null : response.path("data").isArray() ? response.path("data") : response.path("skills");
        if (values == null || !values.isArray()) return List.of();
        List<SkillDefinition> result = new ArrayList<>();
        for (JsonNode item : values) {
            if (result.size() >= limits.maxSkills()) break;
            String id = text(item, "id");
            String slug = text(item, "name");
            String name = text(item, "display_name");
            if (id == null || slug == null) continue;
            String summary = text(item, "summary");
            result.add(new SkillDefinition(id, name == null ? slug : name, slug, text(item, "description"),
                    null, text(item, "category"), List.of(), List.of(), item.get("metadata"),
                    summary, "", null, null, SkillSource.PLATFORM, slug,
                    item.path("enabled").asBoolean(true), text(item, "version"),
                    "elevated".equalsIgnoreCase(text(item, "risk_level"))));
        }
        return List.copyOf(result);
    }

    @Override
    public SkillDefinition load(SkillDefinition skill, SkillQuery query) throws Exception {
        if (skill.source() != SkillSource.PLATFORM || skill.id() == null) return skill;
        CacheKey key = new CacheKey(query.principal(), query.flowId(), query.sessionId(), skill.id(), skill.version());
        synchronized (cache) {
            CacheEntry hit = cache.get(key);
            if (hit != null && hit.expiresAt > System.nanoTime()) return hit.skill;
            cache.remove(key);
        }
        CompletableFuture<SkillDefinition> own = new CompletableFuture<>();
        CompletableFuture<SkillDefinition> pending = loading.computeIfAbsent(key, ignored -> own);
        if (pending != own) return pending.join();
        try {
            Map<String, Object> context = new LinkedHashMap<>(context(query));
            context.put("format", "content");
            JsonNode response = client.getContent(skill.id(), context);
            String markdown = text(response, "content");
            if (markdown == null && response != null) markdown = text(response.path("data"), "content");
            if (markdown == null || markdown.isBlank()) throw new IllegalStateException("Platform skill content is empty");
            SkillDefinition loaded = parser.parse(markdown, null, null, SkillSource.PLATFORM,
                    skill.relativeDir(), limits);
            SkillDefinition merged = new SkillDefinition(skill.id(), loaded.name(), skill.slug(), loaded.description(),
                    loaded.homepage(), loaded.category(), loaded.prerequisites(), loaded.files(), loaded.metadata(),
                    skill.summary(), loaded.body(), null, null, SkillSource.PLATFORM, skill.relativeDir(),
                    skill.enabled(), skill.version(), skill.elevatedRisk());
            synchronized (cache) {
                if (cache.size() >= cacheSize) cache.keySet().iterator().remove();
                cache.put(key, new CacheEntry(merged, System.nanoTime() + ttl.toNanos()));
            }
            own.complete(merged);
            return merged;
        } catch (Exception exception) {
            own.completeExceptionally(exception);
            throw exception;
        } finally {
            loading.remove(key, own);
        }
    }

    private static Map<String, Object> context(SkillQuery query) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("flow_id", query.flowId());
        context.put("session_id", query.sessionId());
        context.put("request_type", query.requestMode());
        context.put("allow_elevated_risk", false);
        if (query.availableTools() != null) context.put("available_tools", String.join(",", query.availableTools()));
        return context;
    }

    private static String text(JsonNode value, String field) {
        JsonNode item = value == null ? null : value.get(field);
        return item != null && item.isValueNode() && !item.asText().isBlank() ? item.asText() : null;
    }

    private record CacheKey(String principal, String flowId, String sessionId, String id, String version) { }
    private record CacheEntry(SkillDefinition skill, long expiresAt) { }
}
