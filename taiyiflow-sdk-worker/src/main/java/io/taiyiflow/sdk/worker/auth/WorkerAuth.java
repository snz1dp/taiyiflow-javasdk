package io.taiyiflow.sdk.worker.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.transport.rest.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Authentication for a digital worker. Only access tokens are exposed to callers.
 */
public abstract class WorkerAuth {

    private static final Duration AUTHENTICATION_TIMEOUT = Duration.ofSeconds(10);

    private final Object refreshMonitor = new Object();
    private volatile long generation;
    private RefreshOutcome lastRefresh;

    protected WorkerAuth() {
    }

    public static WorkerAuth apiKey(String apiKey) {
        return new StaticWorkerAuth(requireToken(apiKey, "apiKey"));
    }

    public static WorkerAuth jwt(String jwt) {
        return new StaticWorkerAuth(requireToken(jwt, "jwt"));
    }

    public static WorkerAuth persistentToken(String persistentToken, String deviceId, CredentialStore store) {
        return new PersistentWorkerAuth(persistentToken, deviceId, Objects.requireNonNull(store, "store"));
    }

    /**
     * Authenticates against the supplied platform base URL.
     */
    public final void authenticate(String baseUrl) throws Exception {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (!supportsRefresh()) {
            doAuthenticate(baseUrl);
            return;
        }
        synchronized (refreshMonitor) {
            try {
                doAuthenticate(baseUrl);
            } finally {
                generation++;
                lastRefresh = null;
            }
        }
    }

    public abstract String accessToken();

    public final long generation() {
        return generation;
    }

    /**
     * Refreshes once for an observed generation. Callers holding an older generation
     * reuse the completed refresh instead of issuing another exchange.
     */
    public final boolean refreshAfterUnauthorized(long observedGeneration) throws Exception {
        if (!supportsRefresh()) {
            return false;
        }
        synchronized (refreshMonitor) {
            if (observedGeneration != generation) {
                RefreshOutcome completed = lastRefresh;
                if (completed != null && completed.sourceGeneration == observedGeneration) {
                    if (completed.failure != null) {
                        throw completed.failure;
                    }
                    return completed.refreshed;
                }
                return accessToken() != null;
            }
            try {
                boolean refreshed = doRefresh();
                lastRefresh = new RefreshOutcome(observedGeneration, refreshed, null);
                generation++;
                return refreshed;
            } catch (Exception exception) {
                lastRefresh = new RefreshOutcome(observedGeneration, false, exception);
                generation++;
                throw exception;
            }
        }
    }

    public String deviceId() {
        return null;
    }

    /**
     * Executes an operation, retrying it once only when a 401 causes a refresh
     * (or joins a refresh already completed for the same generation).
     */
    public final <T> T executeWithRetry(Callable<T> operation) throws Exception {
        Objects.requireNonNull(operation, "operation");
        long observedGeneration = generation();
        try {
            return operation.call();
        } catch (RestClient.RestException exception) {
            if (exception.getStatusCode() != 401 || !refreshAfterUnauthorized(observedGeneration)) {
                throw exception;
            }
            return operation.call();
        }
    }

    protected abstract void doAuthenticate(String baseUrl) throws Exception;

    protected abstract boolean doRefresh() throws Exception;

    protected abstract boolean supportsRefresh();

    private static String requireToken(String token, String name) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return token;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class StaticWorkerAuth extends WorkerAuth {
        private final String token;

        private StaticWorkerAuth(String token) {
            this.token = token;
        }

        @Override
        public String accessToken() {
            return token;
        }

        @Override
        protected void doAuthenticate(String baseUrl) {
            // Static API keys and JWTs do not require an exchange.
        }

        @Override
        protected boolean doRefresh() {
            return false;
        }

        @Override
        protected boolean supportsRefresh() {
            return false;
        }
    }

    private static final class PersistentWorkerAuth extends WorkerAuth {
        private final CredentialStore store;
        private volatile String persistentToken;
        private volatile String deviceId;
        private volatile String accessToken;
        private volatile String baseUrl;

        private PersistentWorkerAuth(String persistentToken, String deviceId, CredentialStore store) {
            this.store = store;
            this.persistentToken = isBlank(persistentToken) ? null : persistentToken;
            this.deviceId = isBlank(deviceId) ? null : deviceId;
            if (this.persistentToken == null) {
                loadStoredCredentials();
            }
        }

        private void loadStoredCredentials() {
            final PersistentCredentials stored;
            try {
                stored = store.load();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to load stored worker credentials");
            }
            if (stored == null) {
                return;
            }
            persistentToken = isBlank(stored.getPersistentToken()) ? null : stored.getPersistentToken();
            if (deviceId == null && !isBlank(stored.getDeviceId())) {
                deviceId = stored.getDeviceId();
            }
            accessToken = isBlank(stored.getAccessToken()) ? null : stored.getAccessToken();
        }

        @Override
        public String accessToken() {
            return accessToken;
        }

        @Override
        public String deviceId() {
            return deviceId;
        }

        @Override
        protected void doAuthenticate(String baseUrl) throws Exception {
            this.baseUrl = baseUrl;
            exchange();
        }

        @Override
        protected boolean doRefresh() throws Exception {
            exchange();
            return true;
        }

        @Override
        protected boolean supportsRefresh() {
            return true;
        }

        private void exchange() throws Exception {
            String currentPersistentToken = persistentToken;
            String currentDeviceId = deviceId;
            String currentBaseUrl = baseUrl;
            if (isBlank(currentPersistentToken) || isBlank(currentDeviceId)) {
                throw new IllegalStateException("Persistent token and deviceId are required");
            }
            if (isBlank(currentBaseUrl)) {
                throw new IllegalStateException("authenticate must be called before refreshing credentials");
            }

            try {
                RestClient client = new RestClient(currentBaseUrl, null, AUTHENTICATION_TIMEOUT);
                String saltPath = RestClient.appendQuery("/xeai/sso/salt",
                        Map.of("persistent_token", currentPersistentToken));
                JsonNode saltData = successfulData(client.get(saltPath));
                String ticket = requiredText(saltData, "ticket");
                String value = requiredText(saltData, "value");
                String sign = sign(currentDeviceId, value);

                Map<String, String> form = new LinkedHashMap<>();
                form.put("persistent_token", currentPersistentToken);
                form.put("salt_ticket", ticket);
                form.put("sign", sign);
                form.put("alg", "SHA-256");
                JsonNode tokenData = successfulData(client.postForm("/xeai/sso", form));
                String newAccessToken = requiredText(tokenData, "access_token");
                String rotatedPersistentToken = tokenData.path("persistent_token").isTextual()
                        && !tokenData.path("persistent_token").asText().isBlank()
                        ? tokenData.path("persistent_token").asText()
                        : currentPersistentToken;

                PersistentCredentials updated = new PersistentCredentials(
                        rotatedPersistentToken, currentDeviceId, newAccessToken);
                store.save(updated);
                persistentToken = rotatedPersistentToken;
                accessToken = newAccessToken;
            } catch (RestClient.RestException exception) {
                int status = exception.getStatusCode();
                if (isDefinitiveRejection(status)) {
                    clearAfterRejection();
                    throw new AuthenticationFailureException(
                            "Persistent credentials were rejected (HTTP " + status + ")");
                }
                throw new AuthenticationFailureException(
                        "Credential exchange failed (HTTP " + status + ")");
            } catch (RejectedException exception) {
                clearAfterRejection();
                throw new AuthenticationFailureException("Persistent credentials were rejected");
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new AuthenticationFailureException("Persistent credential exchange failed");
            }
        }

        private static JsonNode successfulData(JsonNode response) throws Exception {
            if (response == null || !response.path("code").isIntegralNumber()
                    || response.path("code").asInt() != 0) {
                throw new RejectedException();
            }
            JsonNode data = response.get("data");
            if (data == null || !data.isObject()) {
                throw new IOException("Authentication response did not contain data");
            }
            return data;
        }

        private static String requiredText(JsonNode data, String field) throws IOException {
            JsonNode value = data.get(field);
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                throw new IOException("Authentication response was incomplete");
            }
            return value.asText();
        }

        private static String sign(String deviceId, String value) throws NoSuchAlgorithmException {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] signature = digest.digest((deviceId + value).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        }

        private static boolean isDefinitiveRejection(int status) {
            return status == 400 || status == 401 || status == 403 || status == 410;
        }

        private void clearAfterRejection() throws AuthenticationFailureException {
            persistentToken = null;
            accessToken = null;
            try {
                store.clear();
            } catch (Exception exception) {
                throw new AuthenticationFailureException("Unable to clear rejected worker credentials");
            }
        }
    }

    private static final class RefreshOutcome {
        private final long sourceGeneration;
        private final boolean refreshed;
        private final Exception failure;

        private RefreshOutcome(long sourceGeneration, boolean refreshed, Exception failure) {
            this.sourceGeneration = sourceGeneration;
            this.refreshed = refreshed;
            this.failure = failure;
        }
    }

    private static final class RejectedException extends Exception {
    }

    /** An authentication failure whose message never contains credential material. */
    public static final class AuthenticationFailureException extends IOException {
        public AuthenticationFailureException(String message) {
            super(message);
        }
    }
}
