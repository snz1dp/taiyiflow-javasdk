package io.taiyiflow.sdk.core.skill;

import java.util.List;
import java.util.Set;

public record SkillSelection(List<String> requestedNames, List<String> defaultNames,
                             boolean localSupplyEnabled, Set<String> allowedSkills) {
    public SkillSelection {
        requestedNames = requestedNames == null ? null : requestedNames.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        defaultNames = defaultNames == null ? List.of() : defaultNames.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        allowedSkills = allowedSkills == null ? null : allowedSkills.stream()
                .map(SkillNames::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static SkillSelection defaults() {
        return new SkillSelection(null, List.of(), true, null);
    }
}
