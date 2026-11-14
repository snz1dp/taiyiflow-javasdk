package io.taiyiflow.sdk.core.skill;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkillRegistry {
    private final List<SkillProvider> providers;

    public SkillRegistry(List<SkillProvider> providers) {
        this.providers = providers.stream().sorted(Comparator.comparingInt(p -> p.source().priority())).toList();
    }

    public static Builder builder() { return new Builder(); }

    public Discovery discover(SkillQuery query, boolean includePlatform, boolean toleratePlatformFailure) {
        Map<String, SkillDefinition> skills = new LinkedHashMap<>();
        Map<String, SkillProvider> owners = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        for (SkillProvider provider : providers) {
            if (provider.source() == SkillSource.PLATFORM && !includePlatform) continue;
            try {
                for (SkillDefinition skill : provider.discover(query)) {
                    if (skills.putIfAbsent(skill.key(), skill) == null) owners.put(skill.key(), provider);
                }
                if (provider instanceof FileSystemSkillProvider files) diagnostics.addAll(files.diagnostics());
            } catch (Exception exception) {
                if (provider.source() == SkillSource.PLATFORM && !toleratePlatformFailure) {
                    throw new IllegalStateException("Unable to discover platform skills", exception);
                }
                diagnostics.add(provider.source().wireName() + ": discovery unavailable");
            }
        }
        return new Discovery(List.copyOf(skills.values()), Map.copyOf(owners), List.copyOf(diagnostics));
    }

    public SkillDefinition load(SkillDefinition skill, SkillQuery query, Discovery discovery) throws Exception {
        SkillProvider provider = discovery.providers().get(skill.key());
        return provider == null ? skill : provider.load(skill, query);
    }

    public record Discovery(List<SkillDefinition> skills, Map<String, SkillProvider> providers,
                            List<String> diagnostics) { }

    public static final class Builder {
        private final List<SkillProvider> providers = new ArrayList<>();
        private SkillLimits limits = SkillLimits.defaults();

        public Builder limits(SkillLimits limits) { this.limits = limits; return this; }
        public Builder directory(Path root, SkillSource source) {
            if (root != null) providers.add(new FileSystemSkillProvider(root, source, limits));
            return this;
        }
        public Builder bundled(ClassLoader loader, List<String> resources) {
            if (resources != null && !resources.isEmpty()) {
                providers.add(new ClasspathSkillProvider(loader, resources, limits));
            }
            return this;
        }
        public Builder platform(io.taiyiflow.sdk.transport.rest.SkillClient client) {
            if (client != null) providers.add(new PlatformSkillProvider(client, limits,
                    java.time.Duration.ofMinutes(5), 64));
            return this;
        }
        public Builder provider(SkillProvider provider) { providers.add(provider); return this; }
        public SkillRegistry build() { return new SkillRegistry(providers); }
    }
}
