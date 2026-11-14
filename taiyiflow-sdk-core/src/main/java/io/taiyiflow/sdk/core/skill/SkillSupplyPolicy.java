package io.taiyiflow.sdk.core.skill;

public record SkillSupplyPolicy(boolean localSupplyEnabled, boolean platformSupplyEnabled,
                                boolean toleratePlatformFailure) {
    public SkillSupplyPolicy {
        if (!localSupplyEnabled && platformSupplyEnabled) {
            throw new IllegalArgumentException("Local skill supply cannot be disabled independently of platform supply");
        }
    }

    public static SkillSupplyPolicy defaults() { return new SkillSupplyPolicy(true, true, true); }
    public static SkillSupplyPolicy localOnly() { return new SkillSupplyPolicy(true, false, true); }
    public static SkillSupplyPolicy disabled() { return new SkillSupplyPolicy(false, false, true); }
}
