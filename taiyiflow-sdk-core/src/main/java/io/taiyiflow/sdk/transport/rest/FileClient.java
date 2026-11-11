package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
        Map<String, String> fields = new HashMap<>();
        fields.put("session_id", sessionId);
        if (remotePath != null && !remotePath.isBlank()) {
            fields.put("file_path", remotePath);
        }
        String contentType = Files.probeContentType(file);
        JsonNode response = rest.postMultipart("/api/v1/agents/upload/" + RestClient.pathSegment(flowId), fields,
                new RestClient.MultipartFile("file", file.getFileName().toString(),
                        contentType == null ? "application/octet-stream" : contentType, Files.readAllBytes(file)));
        return new UploadedFile(response.required("flow_id").asText(), response.required("file_path").asText());
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
