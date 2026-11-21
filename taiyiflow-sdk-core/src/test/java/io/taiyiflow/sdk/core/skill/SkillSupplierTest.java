package io.taiyiflow.sdk.core.skill;

import io.taiyiflow.sdk.transport.envelope.ClientSkillDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillSupplierTest {
    @TempDir Path temp;

    @Test
    void workspaceOverridesUserAndOnlySelectedSkillsUseFullContent() throws Exception {
        Path workspace = temp.resolve("workspace");
        Path user = temp.resolve("user");
        write(workspace, "shared", "workspace body");
        write(user, "shared", "user body");
        write(user, "other", "other body");
        SkillSupplier supplier = supplier(workspace, user);

        SkillSupplySnapshot snapshot = supplier.supply(query("agent"),
                new SkillSelection(List.of("shared"), List.of(), true, null));

        List<ClientSkillDefinition> skills = snapshot.packages().stream().flatMap(p -> p.skills().stream()).toList();
        assertEquals(2, skills.size());
        ClientSkillDefinition selected = skills.stream().filter(s -> s.slug().equals("shared")).findFirst().orElseThrow();
        assertEquals("workspace body", selected.content().trim());
        assertFalse(selected.summaryOnly());
        assertNull(selected.path());
        assertNull(snapshot.packages().get(0).rootDir());
        ClientSkillDefinition summary = skills.stream().filter(s -> s.slug().equals("other")).findFirst().orElseThrow();
        assertEquals("other body", summary.content());
        assertTrue(summary.summaryOnly());
    }

    @Test
    void filtersModeAndBrowserCategoryWithoutGrantingCapabilities() throws Exception {
        Path workspace = temp.resolve("workspace");
        write(workspace, "planning", "planning body", "request_types: [plan]");
        write(workspace, "browser", "browser body", "tool_category: browser");
        SkillSupplier supplier = supplier(workspace, null);
        SkillQuery query = new SkillQuery("flow", "session", "agent", "principal", Set.of("file_read"));

        assertTrue(supplier.visibleSkills(query, selection()).isEmpty());
        assertTrue(supplier.visibleSkills(new SkillQuery("flow", "session", "plan", "principal",
                Set.of("taiyiflow-in-chrome__navigate")), selection()).size() == 2);
    }

    @Test
    void topLevelBrowserCategoryRequiresBrowserTools() throws Exception {
        Path workspace = temp.resolve("workspace");
        Path directory = Files.createDirectories(workspace.resolve("browser-no-metadata"));
        Files.writeString(directory.resolve("SKILL.md"),
                "---\nname: browser-no-metadata\ncategory: browser\n---\n\nbody");
        SkillSupplier supplier = supplier(workspace, null);

        assertNull(supplier.fetch(query("agent"), selection(), "browser-no-metadata"));
        assertNotNull(supplier.fetch(new SkillQuery("flow", "session", "agent", "principal",
                Set.of("taiyiflow-in-chrome__navigate")), selection(), "browser-no-metadata"));
    }

    @Test
    void whiteboardCategoryRequiresNonEntryWhiteboardTools() throws Exception {
        Path workspace = temp.resolve("workspace");
        Path directory = Files.createDirectories(workspace.resolve("whiteboard"));
        Files.writeString(directory.resolve("SKILL.md"),
                "---\nname: whiteboard\ncategory: whiteboard\nmetadata:\n  request_types:\n    - agent\n---\n\nbody");
        write(workspace, "whiteboard-meta", "body", "tool_category: whiteboard");
        SkillSupplier supplier = supplier(workspace, null);

        SkillQuery closed = new SkillQuery("flow", "session", "agent", "principal", Set.of());
        SkillQuery entryOnly = new SkillQuery("flow", "session", "agent", "principal", Set.of("whiteboard_open"));
        SkillQuery open = new SkillQuery("flow", "session", "agent", "principal",
                Set.of("whiteboard_open", "whiteboard_write"));
        SkillQuery unknownTools = new SkillQuery("flow", "session", "agent", "principal", null);

        assertTrue(supplier.visibleSkills(closed, selection()).isEmpty());
        assertTrue(supplier.visibleSkills(entryOnly, selection()).isEmpty());
        assertNull(supplier.fetch(entryOnly, selection(), "whiteboard"));
        assertEquals(2, supplier.visibleSkills(open, selection()).size());
        assertNotNull(supplier.fetch(open, selection(), "whiteboard"));
        assertNotNull(supplier.fetch(open, selection(), "whiteboard-meta"));
        assertEquals(2, supplier.visibleSkills(unknownTools, selection()).size());
    }

    @Test
    void disabledLocalSupplyClosesFindAndFetch() throws Exception {
        Path workspace = temp.resolve("workspace");
        write(workspace, "one", "body");
        SkillQuery query = query("agent");
        SkillSelection selection = selection();
        SkillRegistry registry = SkillRegistry.builder().directory(workspace, SkillSource.WORKSPACE).build();
        SkillSupplier policyDisabled = new SkillSupplier(registry, SkillSupplyPolicy.disabled());

        assertNull(policyDisabled.find(query, selection, "one"));
        assertNull(policyDisabled.fetch(query, selection, "one"));
        SkillSelection selectionDisabled = new SkillSelection(List.of(), List.of(), false, null);
        SkillSupplier enabled = supplier(workspace, null);
        assertNull(enabled.find(query, selectionDisabled, "one"));
        assertNull(enabled.fetch(query, selectionDisabled, "one"));
    }

    @Test
    void malformedSkillDoesNotHideOtherSkills() throws Exception {
        Path workspace = temp.resolve("workspace");
        write(workspace, "valid", "valid body");
        Files.createDirectories(workspace.resolve("broken"));
        Files.writeString(workspace.resolve("broken/SKILL.md"), "---\nname: broken\n---\n");
        SkillSupplier supplier = supplier(workspace, null);

        assertEquals(1, supplier.visibleSkills(query("agent"), selection()).size());
    }

    @Test
    void emptyDiscoveryCannotReturnSummaryAsFetchedBody() {
        SkillSupplier supplier = new SkillSupplier(SkillRegistry.builder().build(), SkillSupplyPolicy.localOnly());
        assertNull(supplier.fetch(query("agent"), selection(), "missing"));
    }

    private static SkillSupplier supplier(Path workspace, Path user) {
        SkillRegistry registry = SkillRegistry.builder()
                .directory(workspace, SkillSource.WORKSPACE)
                .directory(user, SkillSource.USER)
                .build();
        return new SkillSupplier(registry, SkillSupplyPolicy.localOnly());
    }

    private static SkillSelection selection() {
        return new SkillSelection(List.of(), List.of(), true, null);
    }

    private static SkillQuery query(String mode) {
        return new SkillQuery("flow", "session", mode, "principal", Set.of());
    }

    private static void write(Path root, String slug, String body) throws Exception {
        write(root, slug, body, null);
    }

    private static void write(Path root, String slug, String body, String metadata) throws Exception {
        Path directory = root.resolve(slug);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: " + slug + "\n"
                + (metadata == null ? "" : "metadata:\n  " + metadata + "\n") + "---\n\n" + body);
    }
}
