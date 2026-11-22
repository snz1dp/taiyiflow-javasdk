package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceConfigLicenseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ServiceConfig config(String json) throws Exception {
        return new ServiceConfig(MAPPER.readTree(json));
    }

    @Test
    void exposesLicenseSummaryAndExpiry() throws Exception {
        ServiceConfig config = config("{\"license\":{\"start_time\":\"2026-01-01T00:00:00\","
                + "\"end_time\":\"2026-12-31T23:59:59\",\"user_limit\":10,\"user_count\":3,"
                + "\"terminal_limit\":30,\"expired\":true,\"licensed\":true}}");
        assertTrue(config.licenseExpired());
        assertEquals("2026-12-31T23:59:59", config.licenseEndTime());
        assertEquals(30, config.license().path("terminal_limit").asInt());
        assertTrue(config.license().path("licensed").asBoolean());
    }

    @Test
    void notExpiredWhenFlagFalse() throws Exception {
        assertFalse(config("{\"license\":{\"expired\":false,\"end_time\":null}}").licenseExpired());
    }

    @Test
    void permanentLicenseHasNullEndTime() throws Exception {
        ServiceConfig config = config("{\"license\":{\"expired\":false,\"end_time\":null}}");
        assertNull(config.licenseEndTime());
        assertFalse(config.licenseExpired());
    }

    @Test
    void absentLicenseIsNonExpiringAndNull() throws Exception {
        ServiceConfig config = config("{}");
        assertNull(config.license());
        assertNull(config.licenseEndTime());
        assertFalse(config.licenseExpired());
    }

    @Test
    void licenseAccessorReturnsDefensiveCopy() throws Exception {
        ServiceConfig config = config("{\"license\":{\"expired\":true}}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) config.license()).put("expired", false);
        assertTrue(config.licenseExpired());
    }
}