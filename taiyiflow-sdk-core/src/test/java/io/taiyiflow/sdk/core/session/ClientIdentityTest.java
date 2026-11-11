package io.taiyiflow.sdk.core.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClientIdentityTest {

    @TempDir
    Path tempDir;

    @Test
    void generatedClientIdIsReusedFromPersistentStorage() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            String first = ClientIdentity.loadOrCreate();
            String second = ClientIdentity.loadOrCreate();

            assertFalse(first.isBlank());
            assertEquals(first, second);
            assertEquals(first, Files.readString(tempDir.resolve(".taiyiflow/client-id")));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }
}
