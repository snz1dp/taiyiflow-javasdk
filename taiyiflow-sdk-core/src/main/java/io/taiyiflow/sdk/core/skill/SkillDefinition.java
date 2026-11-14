package io.taiyiflow.sdk.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.transport.envelope.ClientSkillFile;

import java.nio.file.Path;
import java.util.List;

public record SkillDefinition(
        String id, String name, String slug, String description, String homepage, String category,
        List<String> prerequisites, List<ClientSkillFile> files, JsonNode metadata, String summary,
        String body, Path skillFile, Path skillDir, SkillSource source, String relativeDir,
        boolean enabled, String version, boolean elevatedRisk) {
    public SkillDefinition {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        files = files == null ? List.of() : List.copyOf(files);
        metadata = metadata == null ? null : metadata.deepCopy();
        slug = slug == null || slug.isBlank() ? name : slug;
        summary = summary == null || summary.isBlank() ? SkillSummarizer.summarize(body) : summary;
    }

    public String key() { return SkillNames.key(slug); }
    public boolean matches(String candidate) {
        String key = SkillNames.key(candidate);
        return key.equals(SkillNames.key(slug)) || key.equals(SkillNames.key(name))
                || key.equals(SkillNames.key(relativeDir));
    }
}
