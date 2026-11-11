package io.taiyiflow.sdk.core.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

public final class ClientIdentity {

    private ClientIdentity() {
    }

    public static String loadOrCreate() {
        Path path = Path.of(System.getProperty("user.home"), ".taiyiflow", "client-id");
        try {
            String existing = read(path);
            if (existing != null) {
                return existing;
            }
            synchronized (ClientIdentity.class) {
                existing = read(path);
                if (existing != null) {
                    return existing;
                }
                Files.createDirectories(path.getParent());
                String clientId = UUID.randomUUID().toString();
                Files.writeString(path, clientId);
                try {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                }
                return clientId;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist TaiyiFlow client identity at " + path, e);
        }
    }

    private static String read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        String value = Files.readString(path).trim();
        return value.isEmpty() ? null : value;
    }
}
