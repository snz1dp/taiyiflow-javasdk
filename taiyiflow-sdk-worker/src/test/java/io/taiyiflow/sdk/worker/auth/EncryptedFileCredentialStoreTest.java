package io.taiyiflow.sdk.worker.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptedFileCredentialStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptedStoreRoundTripsWithoutPlaintextAndUsesOwnerOnlyFiles() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path credentialFile = tempDir.resolve("secure/credentials.bin");
        Path keyFile = tempDir.resolve("keys/worker.key");
        EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(
                credentialFile, keyFile, workspace);
        String persistentToken = "test-persistent-token-not-for-production";
        String accessToken = "test-access-token-not-for-production";
        PersistentCredentials credentials = new PersistentCredentials(
                persistentToken, "device-test", accessToken);

        store.save(credentials);

        assertEquals(32, Files.readAllBytes(keyFile).length);
        byte[] firstCiphertext = Files.readAllBytes(credentialFile);
        String ciphertextText = new String(firstCiphertext, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(ciphertextText.contains(persistentToken));
        assertFalse(ciphertextText.contains(accessToken));
        assertOwnerOnly(credentialFile);
        assertOwnerOnly(keyFile);

        PersistentCredentials loaded = store.load();
        assertEquals(persistentToken, loaded.getPersistentToken());
        assertEquals("device-test", loaded.getDeviceId());
        assertEquals(accessToken, loaded.getAccessToken());
        assertFalse(loaded.toString().contains(persistentToken));
        assertFalse(loaded.toString().contains(accessToken));
        assertFalse(loaded.toString().contains("device-test"));

        store.save(credentials);
        assertFalse(java.util.Arrays.equals(firstCiphertext, Files.readAllBytes(credentialFile)),
                "A fresh random nonce must produce different ciphertext");

        store.clear();
        assertNull(store.load());
        assertTrue(Files.exists(keyFile), "Clearing credentials does not expose or require deleting the key");
    }

    @Test
    void rejectsCredentialAndKeyFilesInsideWorkspace() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Path outsideCredential = tempDir.resolve("secure/credentials.bin");
        Path outsideKey = tempDir.resolve("secure/worker.key");

        assertThrows(IllegalArgumentException.class, () -> new EncryptedFileCredentialStore(
                workspace.resolve("credentials.bin"), outsideKey, workspace));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedFileCredentialStore(
                outsideCredential, workspace.resolve("worker.key"), workspace));
        assertThrows(IllegalArgumentException.class, () -> new EncryptedFileCredentialStore(
                outsideCredential, outsideCredential, workspace));
    }

    @Test
    void wrongKeyCannotDecryptCredentials() throws Exception {
        Path credentialFile = tempDir.resolve("secure/credentials.bin");
        Path keyFile = tempDir.resolve("secure/worker.key");
        EncryptedFileCredentialStore store = new EncryptedFileCredentialStore(credentialFile, keyFile);
        store.save(new PersistentCredentials("persistent", "device", "access"));
        byte[] originalKey = Files.readAllBytes(keyFile);

        Files.write(keyFile, new byte[32]);
        assertThrows(Exception.class, store::load);

        Files.write(keyFile, originalKey);
        assertEquals("access", store.load().getAccessToken());
    }

    private static void assertOwnerOnly(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
        }
    }
}
