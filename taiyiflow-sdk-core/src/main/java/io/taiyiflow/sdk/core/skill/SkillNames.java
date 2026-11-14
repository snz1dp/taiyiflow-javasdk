package io.taiyiflow.sdk.core.skill;

import java.util.Locale;

final class SkillNames {
    private SkillNames() { }

    static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
