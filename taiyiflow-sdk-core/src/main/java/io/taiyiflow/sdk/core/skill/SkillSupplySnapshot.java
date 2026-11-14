package io.taiyiflow.sdk.core.skill;

import io.taiyiflow.sdk.transport.envelope.ClientSkillPackage;

import java.util.List;

public record SkillSupplySnapshot(List<ClientSkillPackage> packages,
                                  List<String> requestedSkillNames,
                                  List<String> diagnostics) {
    public SkillSupplySnapshot {
        packages = packages == null ? List.of() : List.copyOf(packages);
        requestedSkillNames = requestedSkillNames == null ? List.of() : List.copyOf(requestedSkillNames);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static SkillSupplySnapshot empty() {
        return new SkillSupplySnapshot(List.of(), List.of(), List.of());
    }

    public SkillSupplySnapshot subset(List<String> allowedNames) {
        java.util.Set<String> allowed = allowedNames == null ? java.util.Set.of() : allowedNames.stream()
                .map(SkillNames::key).collect(java.util.stream.Collectors.toSet());
        List<ClientSkillPackage> selected = packages.stream().map(pkg -> {
            var skills = pkg.skills().stream().filter(skill -> allowed.contains(SkillNames.key(skill.slug()))
                    || allowed.contains(SkillNames.key(skill.name()))).toList();
            return skills.isEmpty() ? null : new ClientSkillPackage(pkg.packageName(), pkg.rootDir(), pkg.bundled(), skills);
        }).filter(java.util.Objects::nonNull).toList();
        return new SkillSupplySnapshot(selected, requestedSkillNames.stream()
                .filter(name -> allowed.contains(SkillNames.key(name))).toList(), diagnostics);
    }
}
