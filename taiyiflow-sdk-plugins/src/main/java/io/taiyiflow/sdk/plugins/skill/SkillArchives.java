package io.taiyiflow.sdk.plugins.skill;

import io.taiyiflow.sdk.core.skill.SkillDefinition;
import io.taiyiflow.sdk.transport.envelope.ClientSkillFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class SkillArchives {
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_ENTRY_BYTES = 1_000_000;
    private static final int MAX_TOTAL_BYTES = 4_000_000;
    private static final Set<String> EXTENSIONS = Set.of("md", "txt", "json", "yaml", "yml", "csv",
            "xml", "html", "css", "js", "ts", "py", "sh", "png", "jpg", "jpeg", "webp");

    private SkillArchives() { }

    static byte[] create(SkillDefinition skill) throws IOException {
        if (skill.skillFile() == null || skill.skillDir() == null || skill.slug() == null
                || !skill.slug().matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("Only a validated local skill can be uploaded");
        }
        Path root = skill.skillDir().toRealPath();
        List<ClientSkillFile> declared = new ArrayList<>();
        declared.add(new ClientSkillFile("SKILL.md", null, null));
        declared.addAll(skill.files());
        if (declared.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many skill archive entries");
        Set<String> written = new HashSet<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (ClientSkillFile file : declared) {
                String relative = safePath(file.path());
                if (!written.add(relative)) continue;
                Path source = root.resolve(relative).normalize();
                if (!source.startsWith(root) || Files.isSymbolicLink(source) || !Files.isRegularFile(source)) {
                    throw new IllegalArgumentException("Declared skill file is not a regular file");
                }
                Path real = source.toRealPath();
                if (!real.startsWith(root) || Files.size(real) > MAX_ENTRY_BYTES) {
                    throw new IllegalArgumentException("Declared skill file exceeds safety limits");
                }
                zip.putNextEntry(new ZipEntry(skill.slug() + "/" + relative));
                Files.copy(real, zip);
                zip.closeEntry();
            }
        }
        if (bytes.size() > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Skill archive is too large");
        return bytes.toByteArray();
    }

    static Map<String, byte[]> inspect(byte[] archive) throws IOException {
        if (archive == null || archive.length == 0 || archive.length > MAX_TOTAL_BYTES * 2) {
            throw new IllegalArgumentException("Invalid skill archive size");
        }
        rejectLinks(archive);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int total = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) throw new IllegalArgumentException("Too many skill archive entries");
                if (entry.isDirectory()) continue;
                String name = safePath(entry.getName());
                if (entries.containsKey(name)) throw new IllegalArgumentException("Duplicate skill archive entry");
                byte[] bytes = zip.readNBytes(MAX_ENTRY_BYTES + 1);
                if (bytes.length > MAX_ENTRY_BYTES || zip.read() != -1) {
                    throw new IllegalArgumentException("Skill archive entry is too large");
                }
                total += bytes.length;
                long compressed = entry.getCompressedSize();
                if (total > MAX_TOTAL_BYTES || (compressed > 0 && bytes.length > compressed * 200L)) {
                    throw new IllegalArgumentException("Skill archive exceeds expansion limits");
                }
                entries.put(name, bytes);
            }
        }
        if (total > archive.length * 200L + 1024) throw new IllegalArgumentException("Suspicious archive compression ratio");
        long skillDocuments = entries.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("/SKILL.md") || entry.getKey().equals("SKILL.md"))
                .filter(entry -> new String(entry.getValue(), StandardCharsets.UTF_8).startsWith("---"))
                .count();
        if (skillDocuments != 1) throw new IllegalArgumentException("Skill archive must contain one SKILL.md");
        return entries;
    }

    static String safePath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.startsWith("/")
                || value.startsWith("\\") || value.contains("\\") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Unsafe skill archive path");
        }
        Path path = Path.of(value).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.getNameCount() > 8) {
            throw new IllegalArgumentException("Unsafe skill archive path");
        }
        for (Path part : path) if (part.toString().startsWith(".")) {
            throw new IllegalArgumentException("Hidden skill archive entries are forbidden");
        }
        String normalized = path.toString().replace(java.io.File.separatorChar, '/');
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || !EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported skill archive file type");
        }
        return normalized;
    }

    private static void rejectLinks(byte[] archive) throws IOException {
        int eocd = -1;
        for (int i = archive.length - 22; i >= Math.max(0, archive.length - 65_557); i--) {
            if (readInt(archive, i) == 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) throw new IllegalArgumentException("Invalid ZIP directory");
        int count = readShort(archive, eocd + 10);
        int offset = readInt(archive, eocd + 16);
        for (int i = 0; i < count; i++) {
            if (readInt(archive, offset) != 0x02014b50) throw new IllegalArgumentException("Invalid ZIP entry");
            int mode = readInt(archive, offset + 38) >>> 16;
            int kind = mode & 0170000;
            if (kind == 0120000 || kind == 010000) throw new IllegalArgumentException("Archive links are forbidden");
            int nameLength = readShort(archive, offset + 28);
            int extraLength = readShort(archive, offset + 30);
            int commentLength = readShort(archive, offset + 32);
            offset += 46 + nameLength + extraLength + commentLength;
        }
    }

    private static int readShort(byte[] bytes, int offset) {
        if (offset < 0 || offset + 2 > bytes.length) throw new IllegalArgumentException("Invalid ZIP data");
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static int readInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) throw new IllegalArgumentException("Invalid ZIP data");
        return readShort(bytes, offset) | (readShort(bytes, offset + 2) << 16);
    }
}
