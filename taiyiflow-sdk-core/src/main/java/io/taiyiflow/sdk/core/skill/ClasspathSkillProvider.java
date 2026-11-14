package io.taiyiflow.sdk.core.skill;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ClasspathSkillProvider implements SkillProvider {
    private final ClassLoader classLoader;
    private final List<String> resources;
    private final SkillLimits limits;
    private final SkillDocumentParser parser = new SkillDocumentParser();

    public ClasspathSkillProvider(ClassLoader classLoader, List<String> resources, SkillLimits limits) {
        this.classLoader = classLoader;
        this.resources = List.copyOf(resources);
        this.limits = limits;
    }

    @Override public SkillSource source() { return SkillSource.BUNDLED; }

    @Override
    public List<SkillDefinition> discover(SkillQuery query) throws Exception {
        List<SkillDefinition> result = new ArrayList<>();
        int total = 0;
        for (String resource : resources) {
            if (result.size() >= limits.maxSkills()) break;
            if (resource == null || resource.startsWith("/") || resource.contains("..")) continue;
            try (var input = classLoader.getResourceAsStream(resource)) {
                if (input == null) continue;
                byte[] bytes = input.readNBytes(limits.maxFileBytes() + 1);
                total += bytes.length;
                if (bytes.length > limits.maxFileBytes() || total > limits.maxTotalBytes()) continue;
                String logical = resource.replace('\\', '/');
                int separator = logical.lastIndexOf('/');
                String relative = separator < 0 ? "" : logical.substring(0, separator);
                result.add(parser.parse(new String(bytes, StandardCharsets.UTF_8), null, null,
                        SkillSource.BUNDLED, relative, limits));
            } catch (IllegalArgumentException ignored) {
                // A malformed optional bundled resource does not prevent other sources loading.
            }
        }
        return List.copyOf(result);
    }
}
