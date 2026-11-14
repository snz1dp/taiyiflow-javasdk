package io.taiyiflow.sdk.core.skill;

public final class SkillSummarizer {
    private SkillSummarizer() { }

    public static String summarize(String body) {
        if (body == null) return "";
        StringBuilder paragraph = new StringBuilder();
        boolean codeBlock = false;
        for (String raw : body.split("\\R")) {
            String line = raw.trim();
            if (line.matches("(`{3,}|~{3,}).*")) {
                codeBlock = !codeBlock;
                continue;
            }
            if (codeBlock) continue;
            if (line.isEmpty()) {
                if (!paragraph.isEmpty()) break;
                continue;
            }
            if (line.startsWith("#") || line.matches("([-*_])(\\s*\\1){2,}")) continue;
            line = line.replaceFirst("^(?:>\\s*|[-+*]\\s+|\\d+[.)]\\s+)", "").trim();
            if (line.isEmpty()) continue;
            if (!paragraph.isEmpty()) paragraph.append(' ');
            paragraph.append(line);
        }
        String value = paragraph.toString().trim();
        return value.length() <= 120 ? value : value.substring(0, 120) + "...";
    }
}
