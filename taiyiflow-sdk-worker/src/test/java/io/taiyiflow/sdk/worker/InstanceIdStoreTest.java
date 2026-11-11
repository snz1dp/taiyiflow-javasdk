package io.taiyiflow.sdk.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InstanceIdStoreTest {

    @Test
    void reusesPersistedPrimaryInstanceId(@TempDir Path workspace) throws Exception {
        String first = InstanceIdStore.loadOrCreate(workspace);
        String second = InstanceIdStore.loadOrCreate(workspace);

        assertEquals(first, second);
        assertEquals(first, Files.readString(workspace.resolve("instance_id")));
    }

    @Test
    void rejectsInvalidPersistedInstanceId(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("instance_id"), "not-a-uuid");

        assertThrows(Exception.class, () -> InstanceIdStore.loadOrCreate(workspace));
    }
}
