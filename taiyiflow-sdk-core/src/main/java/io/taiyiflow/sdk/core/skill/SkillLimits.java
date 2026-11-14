package io.taiyiflow.sdk.core.skill;

public record SkillLimits(int maxSkills, int maxDepth, int maxEntries, int maxFileBytes,
                          int maxTotalBytes, int maxBodyChars, int maxFilesPerSkill) {
    public SkillLimits {
        if (maxSkills <= 0 || maxDepth <= 0 || maxEntries <= 0 || maxFileBytes <= 0
                || maxTotalBytes <= 0 || maxBodyChars <= 0 || maxFilesPerSkill < 0) {
            throw new IllegalArgumentException("Invalid skill limits");
        }
    }

    public static SkillLimits defaults() {
        return new SkillLimits(100, 8, 2000, 1_000_000, 4_000_000, 200_000, 32);
    }
}
