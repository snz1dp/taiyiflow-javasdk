package io.taiyiflow.sdk.worker.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerAuthTest {
    private static final String INITIAL_PERSISTENT_TOKEN = "persistent-/+?=初始";
    private static final String ROTATED_PERSISTENT_TOKEN = "rotated-persistent-token";

    private HttpServer server;
    private String baseUrl;
    private MemoryCredentialStore store;
    private AtomicInteger ssoCalls;
    private AtomicInteger httpStatus;
    private AtomicBoolean rotate;
    private AtomicReference<String> saltQuery;
    private AtomicReference<Map<String, String>> ssoForm;

    @BeforeEach
    void startServer() throws Exception {
        ssoCalls = new AtomicInteger();
        httpStatus = new AtomicInteger(200);
        rotate = new AtomicBoolean(true);
        saltQuery = new AtomicReference<>();
        ssoForm = new AtomicReference<>();
        store = new MemoryCredentialStore();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/xeai/sso/salt", exchange -> {
            saltQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "{\"code\":0,\"message\":\"ok\",\"data\":{"
                    + "\"ticket\":\"salt-ticket\",\"value\":\"salt-value\"}}");
        });
        server.createContext("/xeai/sso", exchange -> {
            int call = ssoCalls.incrementAndGet();
            ssoForm.set(parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int status = httpStatus.get();
            if (status != 200) {
                respond(exchange, status, "{\"message\":\"" + INITIAL_PERSISTENT_TOKEN + "\"}");
                return;
            }
            String persistentField = rotate.getAndSet(false)
                    ? ",\"persistent_token\":\"" + ROTATED_PERSISTENT_TOKEN + "\""
                    : "";
            respond(exchange, 200, "{\"code\":0,\"message\":\"ok\",\"data\":{"
                    + "\"access_token\":\"access-" + call + "\"" + persistentField + "}}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exchangesSaltAndSignatureAndRotatesPersistentToken() throws Exception {
        WorkerAuth auth = WorkerAuth.persistentToken(INITIAL_PERSISTENT_TOKEN, "device-1", store);

        auth.authenticate(baseUrl);

        assertEquals("access-1", auth.accessToken());
        assertEquals("device-1", auth.deviceId());
        assertEquals(INITIAL_PERSISTENT_TOKEN, decodeQuery(saltQuery.get()).get("persistent_token"));
        Map<String, String> form = ssoForm.get();
        assertEquals(INITIAL_PERSISTENT_TOKEN, form.get("persistent_token"));
        assertEquals("salt-ticket", form.get("salt_ticket"));
        assertEquals("SHA-256", form.get("alg"));
        assertEquals(expectedSign("device-1", "salt-value"), form.get("sign"));
        assertEquals(ROTATED_PERSISTENT_TOKEN, store.credentials.getPersistentToken());

        long oldGeneration = auth.generation();
        assertTrue(auth.refreshAfterUnauthorized(oldGeneration));

        assertEquals("access-2", auth.accessToken());
        assertEquals(ROTATED_PERSISTENT_TOKEN, store.credentials.getPersistentToken(),
                "An omitted replacement token retains the rotated token");
        assertEquals(2, ssoCalls.get());
        assertTrue(auth.refreshAfterUnauthorized(oldGeneration));
        assertEquals(2, ssoCalls.get(), "An old generation must not exchange again");
    }

    @Test
    void concurrentUnauthorizedUsesOneExchangeAndAtMostOneRetry() throws Exception {
        WorkerAuth auth = WorkerAuth.persistentToken(INITIAL_PERSISTENT_TOKEN, "device-1", store);
        auth.authenticate(baseUrl);
        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        List<AtomicInteger> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                AtomicInteger attempt = new AtomicInteger();
                attempts.add(attempt);
                futures.add(executor.submit(() -> auth.executeWithRetry(() -> {
                    String token = auth.accessToken();
                    if (attempt.incrementAndGet() == 1) {
                        ready.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    }
                    if ("access-1".equals(token)) {
                        throw new RestClient.RestException(401, "unauthorized");
                    }
                    return token;
                })));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (Future<String> future : futures) {
                assertEquals("access-2", future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2, ssoCalls.get(), "Initial authentication plus one shared refresh");
        for (AtomicInteger attempt : attempts) {
            assertTrue(attempt.get() <= 2);
        }
    }

    @Test
    void serverAndNetworkFailuresPreserveCredentialsButRejectionClearsThem() throws Exception {
        WorkerAuth auth = WorkerAuth.persistentToken(INITIAL_PERSISTENT_TOKEN, "device-1", store);
        auth.authenticate(baseUrl);
        String accessToken = auth.accessToken();

        httpStatus.set(503);
        Exception unavailable = assertThrows(Exception.class,
                () -> auth.refreshAfterUnauthorized(auth.generation()));
        assertEquals(accessToken, auth.accessToken());
        assertNotNull(store.credentials);
        assertSanitized(unavailable);

        httpStatus.set(401);
        Exception rejected = assertThrows(Exception.class,
                () -> auth.refreshAfterUnauthorized(auth.generation()));
        assertNull(auth.accessToken());
        assertNull(store.credentials);
        assertEquals(1, store.clearCalls.get());
        assertSanitized(rejected);

        httpStatus.set(200);
        WorkerAuth networkAuth = WorkerAuth.persistentToken(INITIAL_PERSISTENT_TOKEN, "device-1", store);
        networkAuth.authenticate(baseUrl);
        String networkAccessToken = networkAuth.accessToken();
        server.stop(0);
        Exception network = assertThrows(Exception.class,
                () -> networkAuth.refreshAfterUnauthorized(networkAuth.generation()));
        assertEquals(networkAccessToken, networkAuth.accessToken());
        assertNotNull(store.credentials);
        assertSanitized(network);
    }

    @Test
    void emptyPersistentTokenLoadsStoredCredentials() throws Exception {
        store.credentials = new PersistentCredentials(INITIAL_PERSISTENT_TOKEN, "stored-device", "old-access");
        WorkerAuth auth = WorkerAuth.persistentToken("", "", store);

        assertEquals("old-access", auth.accessToken());
        auth.authenticate(baseUrl);

        assertEquals("stored-device", auth.deviceId());
        assertEquals("access-1", auth.accessToken());
        assertEquals("stored-device", store.credentials.getDeviceId());
    }

    @Test
    void staticAuthenticationNeverRefreshesOrRetries() throws Exception {
        WorkerAuth auth = WorkerAuth.apiKey("static-api-key");
        AtomicInteger attempts = new AtomicInteger();

        assertFalse(auth.refreshAfterUnauthorized(auth.generation()));
        assertNull(auth.deviceId());
        assertThrows(RestClient.RestException.class, () -> auth.executeWithRetry(() -> {
            attempts.incrementAndGet();
            throw new RestClient.RestException(401, "unauthorized");
        }));
        assertEquals(1, attempts.get());
        assertEquals("static-api-key", auth.accessToken());
    }

    private void assertSanitized(Exception exception) {
        String message = exception.getMessage();
        assertNotNull(message);
        assertFalse(message.contains(INITIAL_PERSISTENT_TOKEN));
        assertFalse(message.contains(ROTATED_PERSISTENT_TOKEN));
        assertFalse(message.contains("access-"));
    }

    private static String expectedSign(String deviceId, String value) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest((deviceId + value).getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, String> decodeQuery(String query) {
        return parseForm(query);
    }

    private static Map<String, String> parseForm(String form) {
        Map<String, String> values = new HashMap<>();
        for (String pair : form.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class MemoryCredentialStore implements CredentialStore {
        private PersistentCredentials credentials;
        private final AtomicInteger clearCalls = new AtomicInteger();

        @Override
        public synchronized PersistentCredentials load() {
            return credentials;
        }

        @Override
        public synchronized void save(PersistentCredentials credentials) {
            this.credentials = credentials;
        }

        @Override
        public synchronized void clear() {
            credentials = null;
            clearCalls.incrementAndGet();
        }
    }
}
