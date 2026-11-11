package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.transport.rest.RestClient;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;

import java.time.Duration;
import java.util.Map;

final class WorkerRestClient extends RestClient {
    private final WorkerAuth auth;

    WorkerRestClient(String baseUrl, WorkerAuth auth, Duration timeout, Map<String, String> credentialHeaders) {
        super(baseUrl, auth::accessToken, timeout, credentialHeaders);
        this.auth = auth;
    }

    @Override
    public JsonNode get(String path) throws Exception {
        return auth.executeWithRetry(() -> super.get(path));
    }

    @Override
    public byte[] getBytes(String path) throws Exception {
        return auth.executeWithRetry(() -> super.getBytes(path));
    }

    @Override
    public JsonNode post(String path, JsonNode body) throws Exception {
        return auth.executeWithRetry(() -> super.post(path, body));
    }

    @Override
    public JsonNode patch(String path, JsonNode body) throws Exception {
        return auth.executeWithRetry(() -> super.patch(path, body));
    }

    @Override
    public JsonNode postForm(String path, Map<String, String> form) throws Exception {
        return auth.executeWithRetry(() -> super.postForm(path, form));
    }

    @Override
    public JsonNode postMultipart(String path, Map<String, String> fields, MultipartFile file) throws Exception {
        return auth.executeWithRetry(() -> super.postMultipart(path, fields, file));
    }

    @Override
    public void delete(String path) throws Exception {
        auth.executeWithRetry(() -> {
            super.delete(path);
            return null;
        });
    }
}
