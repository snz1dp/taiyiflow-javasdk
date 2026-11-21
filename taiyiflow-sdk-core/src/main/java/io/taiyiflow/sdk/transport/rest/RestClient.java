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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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

    /** GET without Authorization/credential headers, for unauthenticated probe endpoints. */
    public JsonNode getUnauthenticated(String path) throws Exception {
        HttpResponse<String> response = send(path, "GET", HttpRequest.BodyPublishers.noBody(), null,
                HttpResponse.BodyHandlers.ofString(), requestTimeout, false);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RestException(response.statusCode(), response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(response.body());
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

    public RestResponse postRaw(String path, JsonNode body, Duration timeout) throws Exception {
        HttpResponse<byte[]> response = send(path, "POST", jsonBody(body), "application/json",
                HttpResponse.BodyHandlers.ofByteArray(), timeout);
        return new RestResponse(response.statusCode(), response.headers().map(), response.body());
    }

    public RestResponse getRaw(String path, Duration timeout) throws Exception {
        HttpResponse<byte[]> response = send(path, "GET", HttpRequest.BodyPublishers.noBody(), null,
                HttpResponse.BodyHandlers.ofByteArray(), timeout);
        return new RestResponse(response.statusCode(), response.headers().map(), response.body());
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

    public JsonNode postMultipart(String path, Map<String, String> fields, PathMultipartFile file) throws Exception {
        if (!Files.isRegularFile(file.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Upload must be a regular, non-symbolic-link file");
        }
        String boundary = "taiyiflow-" + UUID.randomUUID();
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            writeAscii(prefix, "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + quote(field.getKey()) + "\"\r\n\r\n"
                    + field.getValue() + "\r\n");
        }
        writeAscii(prefix, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + quote(file.fieldName())
                + "\"; filename=\"" + quote(file.filename()) + "\"\r\n"
                + "Content-Type: " + file.contentType().replace("\r", "").replace("\n", "") + "\r\n\r\n");
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(prefix.toByteArray()),
                HttpRequest.BodyPublishers.ofFile(file.path()),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        return sendJson(path, "POST", body, "multipart/form-data; boundary=" + boundary);
    }

    public void delete(String path) throws Exception {
        HttpResponse<String> response = send(path, "DELETE", HttpRequest.BodyPublishers.noBody(), null,
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
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
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
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
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RestException(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        }
        return response.body();
    }

    private <T> HttpResponse<T> send(String path, String method, HttpRequest.BodyPublisher body, String contentType,
                                     HttpResponse.BodyHandler<T> bodyHandler) throws Exception {
        return send(path, method, body, contentType, bodyHandler, requestTimeout);
    }

    private <T> HttpResponse<T> send(String path, String method, HttpRequest.BodyPublisher body, String contentType,
                                     HttpResponse.BodyHandler<T> bodyHandler, Duration timeout) throws Exception {
        return send(path, method, body, contentType, bodyHandler, timeout, true);
    }

    private <T> HttpResponse<T> send(String path, String method, HttpRequest.BodyPublisher body, String contentType,
                                     HttpResponse.BodyHandler<T> bodyHandler, Duration timeout,
                                     boolean authenticate) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout == null ? requestTimeout : timeout)
                .header("Accept", "application/json");
        if (authenticate) {
            String token = accessToken.get();
            if (token != null && !token.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + token);
            }
            credentialHeaders.forEach(requestBuilder::header);
        }
        if (contentType != null) {
            requestBuilder.header("Content-Type", contentType);
        }
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

    public record PathMultipartFile(String fieldName, String filename, String contentType, Path path) {
        public PathMultipartFile {
            path = path.toAbsolutePath().normalize();
        }
    }

    public static final class RestResponse {
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final byte[] body;

        private RestResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
            this.statusCode = statusCode;
            this.headers = headers.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT),
                    entry -> List.copyOf(entry.getValue())));
            this.body = body.clone();
        }

        public int statusCode() { return statusCode; }
        public Map<String, List<String>> headers() { return headers; }
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
        public String mediaType() { return header("content-type"); }
        public byte[] body() { return body.clone(); }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
        public JsonNode json() throws Exception { return mapper.readTree(body); }

        public RestResponse requireSuccess() {
            if (statusCode < 200 || statusCode >= 300) {
                throw new RestException(statusCode, text());
            }
            return this;
        }
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
        private final JsonNode detail;

        public RestException(int statusCode, String body) {
            super("REST error " + statusCode);
            this.statusCode = statusCode;
            this.detail = parseDetail(body);
        }

        public int getStatusCode() {
            return statusCode;
        }

        /** Structured backend detail; never requires matching localized response text. */
        public JsonNode getDetail() {
            return detail == null ? null : detail.deepCopy();
        }

        public String getDetailCode() {
            if (detail == null) {
                return null;
            }
            if (detail.isTextual()) {
                return detail.asText();
            }
            JsonNode code = detail.get("code");
            if (code == null) {
                code = detail.get("detail");
            }
            return code != null && code.isTextual() ? code.asText() : null;
        }

        private static JsonNode parseDetail(String body) {
            if (body == null || body.isBlank()) {
                return null;
            }
            try {
                JsonNode parsed = mapper.readTree(body);
                return parsed != null && parsed.has("detail") ? parsed.get("detail") : parsed;
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
