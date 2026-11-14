package io.taiyiflow.sdk.core.skill;

import java.util.List;

public interface SkillProvider {
    SkillSource source();
    List<SkillDefinition> discover(SkillQuery query) throws Exception;

    default SkillDefinition load(SkillDefinition skill, SkillQuery query) throws Exception {
        return skill;
    }
}
