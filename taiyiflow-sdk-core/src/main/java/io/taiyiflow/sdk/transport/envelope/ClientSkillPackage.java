package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClientSkillPackage(@JsonProperty("package_name") String packageName,
                                 @JsonProperty("root_dir") String rootDir,
                                 @JsonProperty("is_bundled") boolean bundled,
                                 @JsonProperty("skills") List<ClientSkillDefinition> skills) {
    public ClientSkillPackage {
        skills = skills == null ? List.of() : List.copyOf(skills);
    }
}
