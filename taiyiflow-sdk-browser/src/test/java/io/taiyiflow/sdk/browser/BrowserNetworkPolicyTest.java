package io.taiyiflow.sdk.browser;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserNetworkPolicyTest {
    private final BrowserNetworkPolicy policy = new BrowserNetworkPolicy(BrowserSettings.builder()
            .mode(BrowserSettings.Mode.HEADLESS).build());

    @Test
    void blocksNonNetworkSchemesCredentialsAndLocalAddresses() {
        for (String url : new String[]{"file:///etc/passwd", "javascript:alert(1)", "data:text/html,hello",
                "http://user:pass@example.com/", "http://127.0.0.1/", "http://localhost/",
                "http://10.0.0.1/", "http://169.254.169.254/", "http://[::1]/", "http://[fc00::1]/"}) {
            assertFalse(policy.isAllowed(url), url);
        }
        assertThrows(SecurityException.class, () -> policy.validate("file:///etc/passwd"));
    }

    @Test
    void supportsAboutBlankAndExplicitLocalAllowlists() {
        assertEquals("about:blank", policy.validate("about:blank").toString());
        BrowserNetworkPolicy local = new BrowserNetworkPolicy(BrowserSettings.builder()
                .mode(BrowserSettings.Mode.HEADLESS)
                .allowedHosts(Set.of("localhost")).build());
        assertTrue(local.isAllowed("http://localhost:8080/"));
        assertFalse(local.isAllowed("http://localhost.evil.example/"));

        BrowserNetworkPolicy cidr = new BrowserNetworkPolicy(BrowserSettings.builder()
                .mode(BrowserSettings.Mode.HEADLESS)
                .allowedCidrs(Set.of("127.0.0.0/8")).build());
        assertTrue(cidr.isAllowed("http://127.0.0.1:8080/"));
        assertFalse(cidr.isAllowed("http://10.0.0.1/"));
    }
}
