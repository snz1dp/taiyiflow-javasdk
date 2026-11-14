package io.taiyiflow.sdk.core.skill;

import io.taiyiflow.sdk.transport.envelope.ClientSkillDefinition;
import io.taiyiflow.sdk.transport.envelope.ClientSkillPackage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SkillSupplier {
    private final SkillRegistry registry;
    private final SkillSupplyPolicy policy;

    public SkillSupplier(SkillRegistry registry, SkillSupplyPolicy policy) {
        this.registry = registry;
        this.policy = policy;
    }

    public SkillRegistry registry() { return registry; }
    public SkillSupplyPolicy policy() { return policy; }

    public SkillSupplySnapshot supply(SkillQuery query, SkillSelection selection) {
        List<String> explicit = selection.requestedNames() == null ? List.of() : selection.requestedNames();
        if (!policy.localSupplyEnabled() || !selection.localSupplyEnabled()) {
            return new SkillSupplySnapshot(List.of(), stable(explicit), List.of());
        }
        SkillRegistry.Discovery discovery = registry.discover(query, policy.platformSupplyEnabled(),
                policy.toleratePlatformFailure());
        List<String> diagnostics = new ArrayList<>(discovery.diagnostics());
        Set<String> fullNames = new LinkedHashSet<>();
        selection.defaultNames().forEach(name -> fullNames.add(SkillNames.key(name)));
        explicit.forEach(name -> fullNames.add(SkillNames.key(name)));
        Map<SkillSource, List<ClientSkillDefinition>> grouped = new LinkedHashMap<>();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (SkillDefinition original : discovery.skills()) {
            if (!visible(original, query, selection)) continue;
            SkillDefinition skill = original;
            boolean full = original.source() == SkillSource.BUNDLED || fullNames.contains(original.key())
                    || fullNames.contains(SkillNames.key(original.name()));
            if (full && original.body().isBlank()) {
                try {
                    skill = registry.load(original, query, discovery);
                } catch (Exception exception) {
                    diagnostics.add(original.source().wireName() + ": full content unavailable for " + original.key());
                }
            }
            boolean hasFullBody = full && skill.body() != null && !skill.body().isBlank();
            String content = hasFullBody ? skill.body() : skill.summary();
            if (content == null || content.isBlank()) content = skill.description();
            if (content == null || content.isBlank()) continue;
            String name = original.source() == SkillSource.BUNDLED ? skill.name() : skill.slug();
            ClientSkillDefinition client = new ClientSkillDefinition(name, skill.slug(), skill.description(),
                    skill.homepage(), skill.category(), skill.prerequisites(), skill.files(), skill.metadata(),
                    skill.summary(), content, !hasFullBody, null, skill.relativeDir(), null,
                    skill.source().wireName(), skill.relativeDir(), skill.enabled(), skill.version());
            grouped.computeIfAbsent(skill.source(), ignored -> new ArrayList<>()).add(client);
            names.add(name);
        }
        names.addAll(selection.defaultNames());
        names.addAll(explicit);
        List<ClientSkillPackage> packages = new ArrayList<>();
        grouped.forEach((source, skills) -> packages.add(new ClientSkillPackage(
                "taiyiflow-" + source.wireName(), null, source == SkillSource.BUNDLED, skills)));
        return new SkillSupplySnapshot(packages, stable(names), List.copyOf(diagnostics));
    }

    public List<SkillDefinition> visibleSkills(SkillQuery query, SkillSelection selection) {
        if (!policy.localSupplyEnabled() || !selection.localSupplyEnabled()) return List.of();
        return registry.discover(query, policy.platformSupplyEnabled(), policy.toleratePlatformFailure()).skills()
                .stream().filter(skill -> visible(skill, query, selection)).toList();
    }

    public SkillDefinition find(SkillQuery query, SkillSelection selection, String reference) {
        if (!policy.localSupplyEnabled() || !selection.localSupplyEnabled()) return null;
        SkillRegistry.Discovery discovery = registry.discover(query, policy.platformSupplyEnabled(),
                policy.toleratePlatformFailure());
        if (discovery.skills().isEmpty()) return null;
        return discovery.skills().stream()
                .filter(skill -> visible(skill, query, selection) && skill.matches(reference))
                .findFirst().orElse(null);
    }

    public SkillDefinition fetch(SkillQuery query, SkillSelection selection, String reference) {
        if (!policy.localSupplyEnabled() || !selection.localSupplyEnabled()) return null;
        SkillRegistry.Discovery discovery = registry.discover(query, policy.platformSupplyEnabled(),
                policy.toleratePlatformFailure());
        if (discovery.skills().isEmpty()) return null;
        SkillDefinition match = discovery.skills().stream()
                .filter(skill -> visible(skill, query, selection) && skill.matches(reference))
                .findFirst().orElse(null);
        if (match == null) return null;
        try {
            SkillDefinition loaded = registry.load(match, query, discovery);
            if (loaded.body() == null || loaded.body().isBlank()) {
                throw new IllegalStateException("Skill content is unavailable");
            }
            return loaded;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load skill content", exception);
        }
    }

    private static boolean visible(SkillDefinition skill, SkillQuery query, SkillSelection selection) {
        if (!skill.enabled() || skill.elevatedRisk()) return false;
        if (selection.allowedSkills() != null && !selection.allowedSkills().contains(skill.key())
                && !selection.allowedSkills().contains(SkillNames.key(skill.name()))) return false;
        if (skill.metadata() != null) {
            var types = skill.metadata().has("request_types") ? skill.metadata().get("request_types")
                    : skill.metadata().get("request_type");
            if (types != null && !types.isNull()) {
                boolean match = types.isTextual() ? types.asText().equalsIgnoreCase(query.requestMode())
                        : types.isArray() && java.util.stream.StreamSupport.stream(types.spliterator(), false)
                        .anyMatch(type -> type.asText().equalsIgnoreCase(query.requestMode()));
                if (!match) return false;
            }
        }
        String category = skill.metadata() == null ? skill.category()
                : skill.metadata().path("tool_category").asText(skill.category());
        if (query.availableTools() != null && category != null
                && (category.equalsIgnoreCase("browser") || category.equalsIgnoreCase("chrome"))
                && query.availableTools().stream().noneMatch(tool -> tool.startsWith("taiyiflow-in-chrome__"))) {
            return false;
        }
        return true;
    }

    private static List<String> stable(Iterable<String> values) {
        Map<String, String> unique = new LinkedHashMap<>();
        values.forEach(value -> unique.putIfAbsent(SkillNames.key(value), value));
        return List.copyOf(unique.values());
    }
}
