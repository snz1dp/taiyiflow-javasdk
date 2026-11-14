package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

public class FileClient {
    private final RestClient rest;

    public FileClient(RestClient rest) {
        this.rest = rest;
    }

    public UploadedFile upload(String flowId, String sessionId, Path file) throws Exception {
        return upload(flowId, sessionId, file, null);
    }

    public UploadedFile upload(String flowId, String sessionId, Path file, String remotePath) throws Exception {
        validateFiles(List.of(file), null);
        Map<String, String> fields = new HashMap<>();
        fields.put("session_id", sessionId);
        if (remotePath != null && !remotePath.isBlank()) {
            fields.put("file_path", remotePath);
        }
        String contentType = Files.probeContentType(file);
        JsonNode response = rest.postMultipart("/api/v1/agents/upload/" + RestClient.pathSegment(flowId), fields,
                new RestClient.PathMultipartFile("file", file.getFileName().toString(),
                        contentType == null ? "application/octet-stream" : contentType, file));
        return new UploadedFile(response.required("flow_id").asText(), response.required("file_path").asText());
    }

    public List<UploadedFile> uploadAll(String flowId, String sessionId, List<Path> files,
                                        ServiceConfig limits) throws Exception {
        validateFiles(files, limits);
        List<UploadedFile> uploaded = new ArrayList<>();
        for (Path file : files) {
            uploaded.add(upload(flowId, sessionId, file));
        }
        return List.copyOf(uploaded);
    }

    public static void validateFiles(List<Path> files, ServiceConfig limits) throws Exception {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("At least one upload file is required");
        }
        if (limits != null && files.size() > limits.onceMaxFileCount()) {
            throw new IllegalArgumentException("Upload file count exceeds once_max_file_count");
        }
        for (Path file : files) {
            if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Upload must be a regular, non-symbolic-link file");
            }
            long size = Files.size(file);
            if (size == 0) {
                throw new IllegalArgumentException("Upload file must not be empty: " + file.getFileName());
            }
            if (limits != null && size > limits.maxFileSizeBytes()) {
                throw new IllegalArgumentException("Upload file exceeds max_file_size_upload: " + file.getFileName());
            }
        }
    }

    public byte[] download(String flowId, String sessionId, String filePath) throws Exception {
        String encodedPath = Arrays.stream(filePath.split("/", -1))
                .map(RestClient::pathSegment)
                .collect(Collectors.joining("/"));
        return rest.getBytes("/api/v1/agents/download/" + RestClient.pathSegment(flowId)
                + "/" + RestClient.pathSegment(sessionId) + "/" + encodedPath);
    }

    public record UploadedFile(String flowId, String filePath) {
    }
}
