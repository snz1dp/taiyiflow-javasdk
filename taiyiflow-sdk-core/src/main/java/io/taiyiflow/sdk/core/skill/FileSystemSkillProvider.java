package io.taiyiflow.sdk.core.skill;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class FileSystemSkillProvider implements SkillProvider {
    private final Path root;
    private final SkillSource source;
    private final SkillLimits limits;
    private final SkillDocumentParser parser = new SkillDocumentParser();
    private volatile List<String> diagnostics = List.of();

    public FileSystemSkillProvider(Path root, SkillSource source, SkillLimits limits) {
        if (source == SkillSource.PLATFORM || source == SkillSource.BUNDLED) {
            throw new IllegalArgumentException("Unsupported file system skill source");
        }
        this.root = root.toAbsolutePath().normalize();
        this.source = source;
        this.limits = limits;
    }

    @Override public SkillSource source() { return source; }
    public Path root() { return root; }
    public List<String> diagnostics() { return diagnostics; }

    @Override
    public List<SkillDefinition> discover(SkillQuery query) throws IOException {
        List<SkillDefinition> result = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        diagnostics = List.of();
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return List.of();
        Path realRoot = root.toRealPath();
        AtomicInteger entries = new AtomicInteger();
        AtomicInteger totalBytes = new AtomicInteger();
        Files.walkFileTree(realRoot, java.util.Set.of(), limits.maxDepth(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (entries.incrementAndGet() > limits.maxEntries()) return FileVisitResult.TERMINATE;
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(realRoot) && (name.startsWith(".") || name.equals("node_modules")
                        || Files.isSymbolicLink(dir))) return FileVisitResult.SKIP_SUBTREE;
                return result.size() >= limits.maxSkills() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (entries.incrementAndGet() > limits.maxEntries()) return FileVisitResult.TERMINATE;
                if (!file.getFileName().toString().equalsIgnoreCase("SKILL.md")
                        || Files.isSymbolicLink(file) || !attrs.isRegularFile()
                        || attrs.size() > limits.maxFileBytes()
                        || totalBytes.addAndGet(Math.toIntExact(attrs.size())) > limits.maxTotalBytes()) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    Path realFile = file.toRealPath();
                    if (!realFile.startsWith(realRoot)) return FileVisitResult.CONTINUE;
                    String relative = realRoot.relativize(realFile.getParent()).toString()
                            .replace(java.io.File.separatorChar, '/');
                    result.add(parser.parse(Files.readString(realFile), realFile, realFile.getParent(),
                            source, relative, limits));
                } catch (Exception exception) {
                    problems.add(source.wireName() + ": invalid skill at "
                            + realRoot.relativize(file.getParent()));
                }
                return result.size() >= limits.maxSkills() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                problems.add(source.wireName() + ": unreadable entry");
                return FileVisitResult.CONTINUE;
            }
        });
        diagnostics = List.copyOf(problems);
        return result.stream().sorted(Comparator.comparing(SkillDefinition::key)).toList();
    }
}
