package io.taiyiflow.sdk.worker.auth;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Set;

/**
 * AES-256-GCM credential storage. The key and encrypted credentials must live
 * outside the worker's ordinary workspace.
 */
public final class EncryptedFileCredentialStore implements CredentialStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path credentialFile;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();

    public EncryptedFileCredentialStore(Path credentialFile, Path keyFile) throws IOException {
        this(credentialFile, keyFile, null);
    }

    public EncryptedFileCredentialStore(Path credentialFile, Path keyFile, Path workspace) throws IOException {
        this.credentialFile = normalize(credentialFile, "credentialFile");
        this.keyFile = normalize(keyFile, "keyFile");
        if (this.credentialFile.equals(this.keyFile)) {
            throw new IllegalArgumentException("credentialFile and keyFile must be different");
        }
        if (workspace != null) {
            Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
            requireOutside(this.credentialFile, normalizedWorkspace, "credentialFile");
            requireOutside(this.keyFile, normalizedWorkspace, "keyFile");
        }
        Path credentialParent = this.credentialFile.getParent();
        Path keyParent = this.keyFile.getParent();
        if (credentialParent == null || keyParent == null) {
            throw new IllegalArgumentException("Credential paths must have parent directories");
        }
        Files.createDirectories(credentialParent);
        Files.createDirectories(keyParent);
    }

    @Override
    public synchronized PersistentCredentials load() throws Exception {
        if (Files.notExists(credentialFile)) {
            return null;
        }
        byte[] key = readKey();
        byte[] encrypted = Files.readAllBytes(credentialFile);
        if (encrypted.length < NONCE_BYTES + 16) {
            throw new IOException("Encrypted credential file is invalid");
        }
        byte[] plaintext = decrypt(key, encrypted);
        try {
            return MAPPER.readValue(new String(plaintext, StandardCharsets.UTF_8), PersistentCredentials.class);
        } catch (Exception exception) {
            throw new IOException("Encrypted credential file is invalid");
        }
    }

    @Override
    public synchronized void save(PersistentCredentials credentials) throws Exception {
        Objects.requireNonNull(credentials, "credentials");
        byte[] key = Files.exists(keyFile) ? readKey() : createKey();
        final byte[] plaintext;
        try {
            plaintext = MAPPER.writeValueAsBytes(credentials);
        } catch (Exception exception) {
            throw new IOException("Unable to serialize worker credentials");
        }
        writeAtomic(credentialFile, encrypt(key, plaintext));
    }

    @Override
    public synchronized void clear() throws Exception {
        Files.deleteIfExists(credentialFile);
    }

    private byte[] readKey() throws IOException {
        final byte[] key;
        try {
            key = Files.readAllBytes(keyFile);
        } catch (IOException exception) {
            throw new IOException("Unable to read credential encryption key");
        }
        if (key.length != KEY_BYTES) {
            throw new IOException("Credential encryption key is invalid");
        }
        return key;
    }

    private byte[] createKey() throws IOException {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        writeAtomic(keyFile, key);
        return key;
    }

    private byte[] encrypt(byte[] key, byte[] plaintext) throws IOException {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(NONCE_BYTES + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to encrypt worker credentials");
        }
    }

    private byte[] decrypt(byte[] key, byte[] encrypted) throws IOException {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ciphertext = new byte[encrypted.length - NONCE_BYTES];
            System.arraycopy(encrypted, 0, nonce, 0, NONCE_BYTES);
            System.arraycopy(encrypted, NONCE_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Unable to decrypt worker credentials");
        }
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            setOwnerOnly(temporary);
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic credential file replacement is not supported");
            }
            moved = true;
            setOwnerOnly(target);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void setOwnerOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file systems rely on their native ACL configuration.
        }
    }

    private static Path normalize(Path path, String name) {
        Objects.requireNonNull(path, name);
        return path.toAbsolutePath().normalize();
    }

    private static void requireOutside(Path candidate, Path workspace, String name) {
        if (candidate.startsWith(workspace)) {
            throw new IllegalArgumentException(name + " must be outside the ordinary workspace");
        }
    }
}
