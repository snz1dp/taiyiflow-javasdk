package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClientSkillDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("slug") String slug,
        @JsonProperty("description") String description,
        @JsonProperty("homepage") String homepage,
        @JsonProperty("category") String category,
        @JsonProperty("prerequisites") List<String> prerequisites,
        @JsonProperty("files") List<ClientSkillFile> files,
        @JsonProperty("metadata") JsonNode metadata,
        @JsonProperty("summary") String summary,
        @JsonProperty("content") String content,
        @JsonProperty("is_summary") boolean summaryOnly,
        @JsonProperty("path") String path,
        @JsonProperty("skill_dir") String skillDir,
        @JsonProperty("root_dir") String rootDir,
        @JsonProperty("source") String source,
        @JsonProperty("relative_dir") String relativeDir,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("version") String version) {
    public ClientSkillDefinition {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        files = files == null ? List.of() : List.copyOf(files);
        metadata = metadata == null ? null : metadata.deepCopy();
    }
}
