package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.skill.SkillDefinition;
import io.taiyiflow.sdk.core.skill.SkillQuery;
import io.taiyiflow.sdk.core.skill.SkillRegistry;
import io.taiyiflow.sdk.core.skill.SkillSelection;
import io.taiyiflow.sdk.core.skill.SkillSource;
import io.taiyiflow.sdk.core.skill.SkillSupplier;
import io.taiyiflow.sdk.core.skill.SkillSupplyPolicy;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class SkillSupplyExample {
    public static void main(String[] args) throws Exception {
        String selected = FlowMetadataExample.env("TAIYIFLOW_SKILL_NAME");
        SkillSupplier supplier = new SkillSupplier(SkillRegistry.builder()
                .directory(Path.of(FlowMetadataExample.env("TAIYIFLOW_SKILL_DIR")), SkillSource.WORKSPACE)
                .build(), SkillSupplyPolicy.localOnly());
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl(FlowMetadataExample.env("TAIYIFLOW_BASE_URL"))
                .apiKey(FlowMetadataExample.env("TAIYIFLOW_API_KEY"))
                .flowId(System.getenv("TAIYIFLOW_FLOW_ID"))
                .skillSupplier(supplier, List.of()).build();
        try {
            SkillQuery query = new SkillQuery(agent.getFlowId(), "skill-example", "agent", "example", Set.of());
            SkillSelection selection = new SkillSelection(List.of(selected), List.of(), true, null);
            supplier.supply(query, selection).packages().forEach(pkg -> pkg.skills().forEach(skill ->
                    System.out.println(skill.slug() + ": source=" + skill.source() + ", summary=" + skill.summaryOnly())));
            SkillDefinition fetched = supplier.fetch(query, selection, selected);
            if (fetched == null) throw new IllegalStateException("Selected skill is unavailable");
            System.out.println("skill_fetch loaded " + fetched.body().length() + " characters");

            AgentSession session = agent.newSession("skill-example");
            session.onStream(new io.taiyiflow.sdk.hooks.StreamHook() {
                @Override public void onChunk(String sessionId, String chunk) { System.out.print(chunk); }
            });
            ChatRequest request = new ChatRequest("请按选中技能处理当前请求。", "skill-example");
            request.setRequestedSkillNames(List.of(selected));
            session.chat(request);
            if (!session.await(Duration.ofMinutes(2))) throw new IllegalStateException("Skill example timed out");
        } finally {
            agent.shutdown();
        }
    }
}
