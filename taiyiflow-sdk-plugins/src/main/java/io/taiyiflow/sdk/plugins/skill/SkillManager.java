package io.taiyiflow.sdk.plugins.skill;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.core.skill.SkillDefinition;
import io.taiyiflow.sdk.core.skill.SkillDocumentParser;
import io.taiyiflow.sdk.core.skill.SkillLimits;
import io.taiyiflow.sdk.core.skill.SkillSource;
import io.taiyiflow.sdk.transport.rest.SkillClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;

public final class SkillManager {
    private final Path workspaceSkills;
    private final Path userSkills;
    private final SkillClient client;
    private final SkillDocumentParser parser = new SkillDocumentParser();

    public SkillManager(Path workspaceSkills, Path userSkills, SkillClient client) {
        this.workspaceSkills = workspaceSkills;
        this.userSkills = userSkills;
        this.client = client;
    }

    public SkillDefinition create(SkillSource scope, String slug, String markdown) throws IOException {
        if (scope != SkillSource.WORKSPACE && scope != SkillSource.USER) {
            throw new IllegalArgumentException("Skills can only be created in workspace or user scope");
        }
        validateSlug(slug);
        Path root = (scope == SkillSource.WORKSPACE ? workspaceSkills : userSkills);
        if (root == null) throw new IllegalStateException("Requested skill scope is not configured");
        Path skillDir = root.resolve(slug).normalize();
        Path realRoot = root.toAbsolutePath().normalize();
        if (!skillDir.startsWith(realRoot) || Files.exists(skillDir)) {
            throw new IllegalArgumentException("Skill directory already exists or is invalid");
        }
        SkillDefinition parsed = parser.parse(markdown, null, null, scope, slug, SkillLimits.defaults());
        if (!parsed.name().equals(slug)) throw new IllegalArgumentException("Frontmatter name must match skill slug");
        Files.createDirectories(realRoot);
        Path temporary = Files.createTempDirectory(realRoot, ".install-");
        try {
            Path staging = temporary.resolve(slug);
            Files.createDirectory(staging);
            Files.writeString(staging.resolve("SKILL.md"), markdown);
            Files.createDirectories(realRoot);
            try {
                Files.move(staging, skillDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(staging, skillDir);
            }
            return parser.parse(markdown, skillDir.resolve("SKILL.md"), skillDir, scope, slug, SkillLimits.defaults());
        } finally {
            deleteTree(temporary);
        }
    }

    public JsonNode upload(SkillDefinition skill) throws Exception {
        if (client == null) throw new IllegalStateException("Platform skill client is not configured");
        return client.uploadArchive(skill.slug(), skill.name(), skill.slug() + ".zip", SkillArchives.create(skill));
    }

    public void delete(SkillDefinition skill) throws Exception {
        if (skill.source() == SkillSource.PLATFORM) {
            if (client == null || skill.id() == null) throw new IllegalStateException("Platform delete is unavailable");
            client.delete(skill.id());
            return;
        }
        if (skill.source().readOnly() || skill.skillDir() == null) {
            throw new IllegalArgumentException("This skill source is read-only");
        }
        Path root = (skill.source() == SkillSource.WORKSPACE ? workspaceSkills : userSkills);
        if (root == null) throw new IllegalStateException("Skill scope is not configured");
        Path realRoot = root.toRealPath();
        Path realSkill = skill.skillDir().toRealPath();
        if (!realSkill.startsWith(realRoot) || realSkill.equals(realRoot)
                || !realSkill.getFileName().toString().equals(skill.slug())
                || Files.isSymbolicLink(skill.skillDir())) {
            throw new IllegalArgumentException("Skill directory is outside its configured scope");
        }
        deleteTree(realSkill);
    }

    public static void installArchive(Path userSkills, String slug, byte[] archive) throws IOException {
        validateSlug(slug);
        if (userSkills == null) throw new IllegalStateException("User skill scope is not configured");
        Map<String, byte[]> entries = SkillArchives.inspect(archive);
        Path root = userSkills.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path destination = root.resolve(slug);
        if (Files.exists(destination)) throw new IllegalArgumentException("Skill is already installed");
        Path temporary = Files.createTempDirectory(root, ".install-");
        try {
            for (var entry : entries.entrySet()) {
                String relative = entry.getKey();
                String prefix = slug + "/";
                if (!relative.startsWith(prefix)) throw new IllegalArgumentException("Archive contains another skill root");
                Path output = temporary.resolve(relative.substring(prefix.length())).normalize();
                if (!output.startsWith(temporary)) throw new IllegalArgumentException("Archive path escapes staging");
                Files.createDirectories(output.getParent());
                Files.write(output, entry.getValue());
            }
            Path staged = temporary.resolve("SKILL.md");
            if (!Files.isRegularFile(staged)) throw new IllegalArgumentException("Archive misses SKILL.md");
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
        } finally {
            deleteTree(temporary);
        }
    }

    private static void validateSlug(String slug) {
        if (slug == null || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length() > 64) {
            throw new IllegalArgumentException("Skill slug must use lowercase letters, digits and hyphens");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) Files.delete(path);
                else Files.delete(path);
            }
        }
    }
}
