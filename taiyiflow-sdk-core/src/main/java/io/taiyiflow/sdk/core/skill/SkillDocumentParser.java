package io.taiyiflow.sdk.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.taiyiflow.sdk.transport.envelope.ClientSkillFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class SkillDocumentParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();

    public SkillDefinition parse(String markdown, Path skillFile, Path skillDir, SkillSource source,
                                 String relativeDir, SkillLimits limits) {
        if (markdown == null || !markdown.startsWith("---")) {
            throw new IllegalArgumentException("SKILL.md requires YAML frontmatter");
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\\A---\\R([\\s\\S]*?)\\R---(?:\\R|$)").matcher(markdown);
        if (!matcher.find()) throw new IllegalArgumentException("Invalid SKILL.md frontmatter");
        try {
            JsonNode frontmatter = YAML.readTree(matcher.group(1));
            validateYaml(frontmatter, 0, new int[]{0});
            if (frontmatter == null || !frontmatter.isObject()) {
                throw new IllegalArgumentException("SKILL.md frontmatter must be an object");
            }
            String name = text(frontmatter, "name");
            if (name == null || name.isBlank() || name.length() > 200) {
                throw new IllegalArgumentException("SKILL.md frontmatter requires name");
            }
            String body = markdown.substring(matcher.end());
            if (body.isBlank()) throw new IllegalArgumentException("Skill body is empty");
            if (body.length() > limits.maxBodyChars()) throw new IllegalArgumentException("Skill body is too large");
            List<ClientSkillFile> files = files(frontmatter.get("files"), limits.maxFilesPerSkill());
            List<String> prerequisites = strings(frontmatter.get("prerequisites"), "prerequisites");
            return new SkillDefinition(null, name, text(frontmatter, "slug"),
                    limit(text(frontmatter, "description"), 4000), text(frontmatter, "homepage"),
                    text(frontmatter, "category"), prerequisites, files, frontmatter.get("metadata"),
                    null, body, skillFile, skillDir, source, relativeDir,
                    frontmatter.path("enabled").asBoolean(true), text(frontmatter, "version"), false);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse SKILL.md frontmatter", exception);
        }
    }

    public static List<String> missingEnvironment(SkillDefinition skill) {
        JsonNode metadata = skill.metadata();
        JsonNode required = metadata == null ? null : metadata.get("requires_env");
        if (required == null || !required.isArray()) return List.of();
        List<String> missing = new ArrayList<>();
        Map<String, String> environment = System.getenv();
        required.forEach(item -> {
            if (item.isTextual() && item.asText().matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                    && !environment.containsKey(item.asText())) missing.add(item.asText());
        });
        return List.copyOf(missing);
    }

    private static List<ClientSkillFile> files(JsonNode value, int maximum) {
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray() || value.size() > maximum) throw new IllegalArgumentException("Invalid skill files");
        List<ClientSkillFile> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (item.isTextual()) result.add(new ClientSkillFile(safeRelative(item.asText()), null, null));
            else if (item.isObject()) {
                String path = item.path("path").asText(item.path("source").asText(""));
                result.add(new ClientSkillFile(safeRelative(path), item.path("source").asText(null),
                        limit(item.path("description").asText(null), 1000)));
            } else throw new IllegalArgumentException("Invalid skill file declaration");
        }
        return List.copyOf(result);
    }

    private static String safeRelative(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.startsWith("/")
                || value.startsWith("\\") || value.matches("^[A-Za-z]:.*") || value.length() > 500) {
            throw new IllegalArgumentException("Invalid skill file path");
        }
        java.nio.file.Path path = java.nio.file.Path.of(value.replace('\\', '/')).normalize();
        if (path.isAbsolute() || path.getNameCount() > 8 || path.startsWith("..")
                || java.util.Arrays.stream(value.split("/")).anyMatch(part -> part.startsWith("."))) {
            throw new IllegalArgumentException("Invalid skill file path");
        }
        return path.toString().replace(java.io.File.separatorChar, '/');
    }

    private static List<String> strings(JsonNode value, String field) {
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray() || value.size() > 32) throw new IllegalArgumentException("Invalid " + field);
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.asText().length() > 1000) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            result.add(item.asText());
        });
        return List.copyOf(result);
    }

    private static void validateYaml(JsonNode node, int depth, int[] count) {
        if (++count[0] > 1000 || depth > 16) throw new IllegalArgumentException("YAML structure is too complex");
        if (node == null) return;
        if (node.isContainerNode()) {
            Iterator<JsonNode> children = node.elements();
            children.forEachRemaining(child -> validateYaml(child, depth + 1, count));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isValueNode() ? value.asText() : null;
    }

    private static String limit(String value, int maximum) {
        if (value != null && value.length() > maximum) throw new IllegalArgumentException("Skill text is too long");
        return value;
    }
}
