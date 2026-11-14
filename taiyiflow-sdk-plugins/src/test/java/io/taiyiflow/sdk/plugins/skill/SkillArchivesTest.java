package io.taiyiflow.sdk.plugins.skill;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SkillArchivesTest {
    @Test
    void acceptsNormalArchive() throws Exception {
        byte[] archive = archive("demo/SKILL.md", "---\nname: demo\n---\nbody");
        assertTrue(SkillArchives.inspect(archive).containsKey("demo/SKILL.md"));
    }

    @Test
    void rejectsTraversalAbsolutePathsAndMissingSkillDocument() {
        assertThrows(IllegalArgumentException.class, () -> SkillArchives.inspect(archive("../SKILL.md", "body")));
        assertThrows(IllegalArgumentException.class, () -> SkillArchives.inspect(archive("/tmp/SKILL.md", "body")));
        assertThrows(IllegalArgumentException.class, () -> SkillArchives.inspect(archive("demo/readme.txt", "body")));
        assertThrows(IllegalArgumentException.class, () -> SkillArchives.inspect(archive("demo/SKILL.md", "body")));
    }

    @Test
    void rejectsOversizedAndHighlyCompressedEntries() {
        String large = "x".repeat(2 * 1024 * 1024);
        assertThrows(IllegalArgumentException.class, () -> SkillArchives.inspect(archive("demo/SKILL.md",
                "---\nname: demo\n---\n" + large)));
    }

    @Test
    void rejectsUnixSymlinkFromCentralDirectoryAttributes() throws Exception {
        byte[] archive = archive("demo/SKILL.md", "---\nname: demo\n---\nbody", "demo/link", "target");
        int central = indexOf(archive, new byte[]{0x50, 0x4b, 0x01, 0x02});
        int entries = 0;
        while (central >= 0 && entries++ < 2) {
            int nameLength = Short.toUnsignedInt(ByteBuffer.wrap(archive, central + 28, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort());
            String name = new String(archive, central + 46, nameLength, StandardCharsets.UTF_8);
            if (name.equals("demo/link")) {
                ByteBuffer.wrap(archive, central + 38, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0xA1FF0000);
                break;
            }
            int extra = Short.toUnsignedInt(ByteBuffer.wrap(archive, central + 30, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort());
            int comment = Short.toUnsignedInt(ByteBuffer.wrap(archive, central + 32, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort());
            central = indexOf(archive, new byte[]{0x50, 0x4b, 0x01, 0x02}, central + 46 + nameLength + extra + comment);
        }
        assertThrows(IllegalArgumentException.class, () -> SkillArchives.inspect(archive));
    }

    private static byte[] archive(String... entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        return indexOf(bytes, pattern, 0);
    }

    private static int indexOf(byte[] bytes, byte[] pattern, int start) {
        for (int i = Math.max(0, start); i <= bytes.length - pattern.length; i++) {
            if (Arrays.equals(pattern, Arrays.copyOfRange(bytes, i, i + pattern.length))) return i;
        }
        return -1;
    }
}
