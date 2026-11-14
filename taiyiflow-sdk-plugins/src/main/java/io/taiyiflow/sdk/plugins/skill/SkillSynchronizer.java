package io.taiyiflow.sdk.plugins.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.core.skill.SkillDefinition;
import io.taiyiflow.sdk.transport.rest.SkillClient;

import java.nio.file.Path;

public final class SkillSynchronizer {
    public enum Mode { DISABLED, CONFIRM, AUTO }
    public enum Status { DISABLED, CONFIRMATION_REQUIRED, INSTALLED, REJECTED }

    @FunctionalInterface
    public interface Confirmation {
        boolean approve(String skillName);
    }

    private final SkillClient client;
    private final Path userSkills;
    private final Mode mode;
    private final Confirmation confirmation;
    private final ObjectMapper mapper = new ObjectMapper();

    public SkillSynchronizer(SkillClient client, Path userSkills, Mode mode, Confirmation confirmation) {
        this.client = client;
        this.userSkills = userSkills;
        this.mode = mode;
        this.confirmation = confirmation;
    }

    public Status install(SkillDefinition skill, byte[] archive) throws Exception {
        if (mode == Mode.DISABLED) return Status.DISABLED;
        if (skill.elevatedRisk()) return Status.REJECTED;
        if (mode == Mode.CONFIRM && (confirmation == null || !confirmation.approve(skill.slug()))) {
            return Status.CONFIRMATION_REQUIRED;
        }
        SkillManager.installArchive(userSkills, skill.slug(), archive);
        if (client != null && skill.id() != null) {
            var report = mapper.createObjectNode();
            report.put("skill_package_id", skill.id());
            report.put("install_source", "java-sdk");
            report.put("client_platform", "java");
            client.reportInstallation(report);
        }
        return Status.INSTALLED;
    }
}
