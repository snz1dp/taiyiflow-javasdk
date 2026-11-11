package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * REST client for auxiliary API calls.
 */
public class RestClient {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final Supplier<String> accessToken;
    private final Map<String, String> credentialHeaders;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public RestClient(String baseUrl, String apiKey, Duration timeout) {
        this(baseUrl, apiKey, timeout, Map.of());
    }

    public RestClient(String baseUrl, String apiKey, Duration timeout, Map<String, String> credentialHeaders) {
        this(baseUrl, () -> apiKey, timeout, credentialHeaders);
    }

    public RestClient(String baseUrl, Supplier<String> accessToken, Duration timeout,
                      Map<String, String> credentialHeaders) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.accessToken = accessToken;
        this.credentialHeaders = Map.copyOf(credentialHeaders);
        this.requestTimeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    public JsonNode get(String path) throws Exception {
        return sendJson(path, "GET", HttpRequest.BodyPublishers.noBody(), null);
    }

    public byte[] getBytes(String path) throws Exception {
        return sendBytes(path);
    }

    public JsonNode post(String path, JsonNode body) throws Exception {
        return sendJson(path, "POST", jsonBody(body), "application/json");
    }

    public JsonNode patch(String path, JsonNode body) throws Exception {
        return sendJson(path, "PATCH", jsonBody(body), "application/json");
    }

    public JsonNode postForm(String path, Map<String, String> form) throws Exception {
        StringJoiner body = new StringJoiner("&");
        form.forEach((key, value) -> body.add(encode(key) + "=" + encode(value)));
        return sendJson(path, "POST", HttpRequest.BodyPublishers.ofString(body.toString()),
                "application/x-www-form-urlencoded");
    }

    public JsonNode postMultipart(String path, Map<String, String> fields, MultipartFile file) throws Exception {
        String boundary = "taiyiflow-" + UUID.randomUUID();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            writeAscii(output, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + quote(field.getKey()) + "\"\r\n\r\n"
                    + field.getValue() + "\r\n");
        }
        writeAscii(output, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + quote(file.fieldName())
                + "\"; filename=\"" + quote(file.filename()) + "\"\r\n"
                + "Content-Type: " + file.contentType().replace("\r", "").replace("\n", "") + "\r\n\r\n");
        output.write(file.content());
        writeAscii(output, "\r\n--" + boundary + "--\r\n");
        return sendJson(path, "POST", HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()),
                "multipart/form-data; boundary=" + boundary);
    }

    public void delete(String path) throws Exception {
        HttpResponse<String> response = send(path, "DELETE", HttpRequest.BodyPublishers.noBody(), null,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RestException(response.statusCode(), response.body());
        }
    }

    public static String appendQuery(String path, Map<String, ?> parameters) {
        StringJoiner query = new StringJoiner("&");
        new TreeMap<>(parameters).forEach((key, value) -> {
            if (value == null) {
                return;
            }
            if (value instanceof Iterable<?> values) {
                values.forEach(item -> {
                    if (item != null) {
                        query.add(encode(key) + "=" + encode(String.valueOf(item)));
                    }
                });
            } else {
                query.add(encode(key) + "=" + encode(String.valueOf(value)));
            }
        });
        String encoded = query.toString();
        if (encoded.isEmpty()) {
            return path;
        }
        return path + (path.contains("?") ? "&" : "?") + encoded;
    }

    public static String pathSegment(String value) {
        return encode(value).replace("+", "%20");
    }

    private JsonNode sendJson(String path, String method, HttpRequest.BodyPublisher body, String contentType)
            throws Exception {
        HttpResponse<String> response = send(path, method, body, contentType, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RestException(response.statusCode(), response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(response.body());
    }

    private byte[] sendBytes(String path) throws Exception {
        HttpResponse<byte[]> response = send(path, "GET", HttpRequest.BodyPublishers.noBody(), null,
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new RestException(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        }
        return response.body();
    }

    private <T> HttpResponse<T> send(String path, String method, HttpRequest.BodyPublisher body, String contentType,
                                     HttpResponse.BodyHandler<T> bodyHandler) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
        String token = accessToken.get();
        if (token != null && !token.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            requestBuilder.header("Content-Type", contentType);
        }
        credentialHeaders.forEach(requestBuilder::header);
        return httpClient.send(requestBuilder.method(method, body).build(), bodyHandler);
    }

    private static HttpRequest.BodyPublisher jsonBody(JsonNode body) throws Exception {
        return HttpRequest.BodyPublishers.ofString(body == null ? "" : mapper.writeValueAsString(body));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return value.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    public record MultipartFile(String fieldName, String filename, String contentType, byte[] content) {
        public MultipartFile {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    public static class RestException extends RuntimeException {
        private final int statusCode;

        public RestException(int statusCode, String body) {
            super("REST error " + statusCode + ": " + body);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
