package io.taiyiflow.sdk.worker;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

final class InstanceIdStore {
    private InstanceIdStore() {
    }

    static String loadOrCreate(Path workspace) throws IOException {
        Files.createDirectories(workspace);
        Path file = workspace.resolve("instance_id");
        if (Files.exists(file)) {
            return read(file);
        }
        String instanceId = UUID.randomUUID().toString();
        try {
            Files.writeString(file, instanceId, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return instanceId;
        } catch (FileAlreadyExistsException e) {
            return read(file);
        }
    }

    private static String read(Path file) throws IOException {
        String value = Files.readString(file).trim();
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("Persisted worker instance_id is invalid", e);
        }
    }
}
