package io.taiyiflow.sdk.plugins.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAuditTest {

    @TempDir
    Path tempDir;

    @TempDir
    Path outside;

    private WorkspaceAudit.Limits limits(int depth, int entries) {
        return new WorkspaceAudit.Limits(depth, entries, 5_000, 32, 1024 * 1024);
    }

    @Test
    void diffDetectsCreatesEditsAndDeletesWithBoundedCandidateHashes() throws Exception {
        Path existing = tempDir.resolve("existing.txt");
        Files.writeString(existing, "before");
        WorkspaceAudit audit = new WorkspaceAudit(tempDir, limits(4, 20));
        WorkspaceAudit.Scan baseline = audit.captureBaseline("session-1", "run-1");
        assertTrue(baseline.complete());

        Files.writeString(existing, "after-with-new-size");
        Path created = tempDir.resolve("created.txt");
        Files.writeString(created, "created");
        WorkspaceAudit.AuditResult result = audit.diffAndRefresh("session-1", "run-1",
                baseline, Set.of());

        assertTrue(result.complete());
        assertEquals(2, result.changes().size());
        WorkspaceAudit.Change edit = result.changes().stream()
                .filter(change -> change.type().equals("edit")).findFirst().orElseThrow();
        assertEquals(existing.toRealPath(), edit.path());
        assertEquals(Files.size(existing), edit.size());
        assertEquals(Files.getLastModifiedTime(existing).toMillis(), edit.mtime());
        assertEquals(64, edit.sha256().length());

        WorkspaceAudit.Scan refreshed = audit.captureBaseline("session-1", "run-1");
        WorkspaceAudit.AuditResult noChanges = audit.diffAndRefresh("session-1", "run-1",
                refreshed, Set.of());
        assertTrue(noChanges.complete());
        assertTrue(noChanges.changes().isEmpty());

        Files.delete(created);
        WorkspaceAudit.AuditResult deletion = audit.diffAndRefresh("session-1", "run-1",
                audit.captureBaseline("session-1", "run-1"), Set.of());
        assertTrue(deletion.complete());
        assertEquals(1, deletion.changes().size());
        assertEquals("delete", deletion.changes().get(0).type());
        assertEquals(tempDir.toRealPath().resolve("created.txt"), deletion.changes().get(0).path());
    }

    @Test
    void sameSizeEditWithChangedMtimeIsDetected() throws Exception {
        Path file = tempDir.resolve("same-size.txt");
        Files.writeString(file, "aaaa");
        WorkspaceAudit audit = new WorkspaceAudit(tempDir, limits(2, 10));
        WorkspaceAudit.Scan baseline = audit.captureBaseline("s", "r");

        Files.writeString(file, "bbbb");
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 5_000));
        WorkspaceAudit.AuditResult result = audit.diffAndRefresh("s", "r", baseline, Set.of());

        assertTrue(result.complete());
        assertEquals(1, result.changes().size());
        assertEquals("edit", result.changes().get(0).type());
    }

    @Test
    void declaredPathsAreDeduplicatedWithoutHidingOtherArtifacts() throws Exception {
        WorkspaceAudit audit = new WorkspaceAudit(tempDir, limits(2, 10));
        WorkspaceAudit.Scan baseline = audit.captureBaseline("s", "r");
        Path declared = tempDir.resolve("declared.txt");
        Path discovered = tempDir.resolve("discovered.txt");
        Files.writeString(declared, "declared");
        Files.writeString(discovered, "discovered");

        WorkspaceAudit.AuditResult result = audit.diffAndRefresh("s", "r", baseline,
                Set.of(declared));

        assertTrue(result.complete());
        assertEquals(1, result.changes().size());
        assertEquals(discovered.toRealPath(), result.changes().get(0).path());
    }

    @Test
    void sessionAndRunBucketsHaveIndependentBaselines() throws Exception {
        WorkspaceAudit audit = new WorkspaceAudit(tempDir, limits(2, 10));
        WorkspaceAudit.Scan first = audit.captureBaseline("s1", "r1");
        Path artifact = tempDir.resolve("artifact.txt");
        Files.writeString(artifact, "content");
        WorkspaceAudit.Scan second = audit.captureBaseline("s2", "r2");

        assertEquals(1, audit.diffAndRefresh("s1", "r1", first, Set.of()).changes().size());
        assertTrue(audit.diffAndRefresh("s2", "r2", second, Set.of()).changes().isEmpty());
    }

    @Test
    void entryLimitReturnsExplicitIncompleteDegradation() throws Exception {
        Files.writeString(tempDir.resolve("one"), "1");
        Files.writeString(tempDir.resolve("two"), "2");
        WorkspaceAudit audit = new WorkspaceAudit(tempDir, limits(2, 1));

        WorkspaceAudit.Scan baseline = audit.captureBaseline("s", "r");
        WorkspaceAudit.AuditResult result = audit.diffAndRefresh("s", "r", baseline, Set.of());

        assertFalse(baseline.complete());
        assertTrue(baseline.entries().isEmpty());
        assertEquals(List.of("MAX_ENTRIES_EXCEEDED"), baseline.degradationReasons());
        assertFalse(result.complete());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void depthAndTimeLimitsAreNotReportedAsEmptyCompleteScans() {
        WorkspaceAudit depthAudit = new WorkspaceAudit(tempDir, limits(0, 10));
        assertDoesNotThrow(() -> Files.createDirectories(tempDir.resolve("nested")));
        WorkspaceAudit.Scan depth = depthAudit.captureBaseline("depth", "r");
        assertFalse(depth.complete());
        assertEquals(List.of("MAX_DEPTH_EXCEEDED"), depth.degradationReasons());

        WorkspaceAudit timeAudit = new WorkspaceAudit(tempDir,
                new WorkspaceAudit.Limits(2, 10, 0, 1, 10));
        WorkspaceAudit.Scan time = timeAudit.captureBaseline("time", "r");
        assertFalse(time.complete());
        assertEquals(List.of("TIME_BUDGET_EXCEEDED"), time.degradationReasons());
    }

    @Test
    void ignoredDirectoriesAndSymlinksAreExcludedWithoutFollowingThem() throws Exception {
        Files.createDirectories(tempDir.resolve("node_modules"));
        Files.createDirectories(tempDir.resolve("build"));
        Files.createDirectories(tempDir.resolve("state-dir"));
        Files.createDirectories(tempDir.resolve("credentials"));
        Files.writeString(tempDir.resolve("node_modules/dependency"), "ignored");
        Files.writeString(tempDir.resolve("build/output"), "ignored");
        Files.writeString(tempDir.resolve("state-dir/state"), "ignored");
        Files.writeString(tempDir.resolve("credentials/token"), "ignored");
        Files.writeString(outside.resolve("secret"), "outside");
        Files.createSymbolicLink(tempDir.resolve("outside-link"), outside);

        WorkspaceAudit audit = new WorkspaceAudit(tempDir, limits(4, 10));
        WorkspaceAudit.Scan baseline = audit.captureBaseline("s", "r");
        assertTrue(baseline.complete());
        assertTrue(baseline.entries().isEmpty());

        Path artifact = tempDir.resolve("artifact.txt");
        Files.writeString(artifact, "visible");
        WorkspaceAudit.AuditResult result = audit.diffAndRefresh("s", "r", baseline, Set.of());

        assertTrue(result.complete());
        assertEquals(1, result.changes().size());
        assertEquals(artifact.toRealPath(), result.changes().get(0).path());
        assertFalse(result.changes().stream().anyMatch(change ->
                change.path().toString().contains("credentials")
                        || change.path().toString().contains("outside-link")));
    }

    @Test
    void zeroHashBudgetStillReturnsCompleteMetadataAudit() throws Exception {
        WorkspaceAudit audit = new WorkspaceAudit(tempDir,
                new WorkspaceAudit.Limits(2, 10, 5_000, 0, 10));
        WorkspaceAudit.Scan baseline = audit.captureBaseline("s", "r");
        Files.writeString(tempDir.resolve("artifact"), "content");

        WorkspaceAudit.AuditResult result = audit.diffAndRefresh("s", "r", baseline, Set.of());

        assertTrue(result.complete());
        assertEquals(1, result.changes().size());
        assertNull(result.changes().get(0).sha256());
        assertNotNull(result.changes().get(0).size());
        assertNotNull(result.changes().get(0).mtime());
    }
}
