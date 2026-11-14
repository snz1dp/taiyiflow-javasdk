package io.taiyiflow.sdk.core.skill;

import io.taiyiflow.sdk.transport.envelope.ClientSkillPackage;

import java.nio.file.Path;
import java.util.List;

public final class SkillPackageValidator {
    private SkillPackageValidator() { }

    public static void validate(List<ClientSkillPackage> packages) {
        if (packages == null) return;
        if (packages.size() > 50) throw new IllegalArgumentException("Too many client skill packages");
        int skills = 0;
        int characters = 0;
        for (ClientSkillPackage pkg : packages) {
            if (pkg == null || pkg.packageName() == null || pkg.packageName().isBlank()
                    || pkg.rootDir() != null || pkg.skills().isEmpty()) {
                throw new IllegalArgumentException("Invalid client skill package");
            }
            for (var skill : pkg.skills()) {
                if (++skills > 100 || skill == null || skill.name() == null || skill.name().isBlank()
                        || skill.path() != null || skill.rootDir() != null) {
                    throw new IllegalArgumentException("Invalid or excessive client skill content");
                }
                validateRelative(skill.skillDir());
                validateRelative(skill.relativeDir());
                characters += length(skill.content()) + length(skill.summary()) + length(skill.description());
                if (characters > 4_000_000 || skill.files().size() > 32) {
                    throw new IllegalArgumentException("Client skill content exceeds limits");
                }
                for (var file : skill.files()) validateRelative(file.path());
            }
        }
    }

    private static void validateRelative(String value) {
        if (value == null || value.isBlank()) return;
        if (value.indexOf('\0') >= 0 || value.startsWith("/") || value.startsWith("\\")
                || value.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("Absolute skill paths are forbidden");
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.getNameCount() > 8) {
            throw new IllegalArgumentException("Invalid skill path");
        }
    }

    private static int length(String value) { return value == null ? 0 : value.length(); }
}
