package io.taiyiflow.sdk.core.skill;

public enum SkillSource {
    WORKSPACE(0, false),
    USER(1, false),
    COMPAT(2, true),
    BUNDLED(3, true),
    PLATFORM(4, true);

    private final int priority;
    private final boolean readOnly;

    SkillSource(int priority, boolean readOnly) {
        this.priority = priority;
        this.readOnly = readOnly;
    }

    public int priority() { return priority; }
    public boolean readOnly() { return readOnly; }
    public String wireName() { return name().toLowerCase(java.util.Locale.ROOT); }
}
