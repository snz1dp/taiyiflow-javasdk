package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public class ScheduleClient {
    private static final String BASE = "/api/v1/agents/client/schedules";
    private final RestClient rest;

    public ScheduleClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode list(Map<String, ?> filters) throws Exception {
        return rest.get(RestClient.appendQuery(BASE, filters));
    }

    public JsonNode create(JsonNode schedule) throws Exception {
        return rest.post(BASE, schedule);
    }

    public JsonNode update(String scheduleId, JsonNode schedule) throws Exception {
        return rest.patch(schedulePath(scheduleId), schedule);
    }

    public void delete(String scheduleId) throws Exception {
        rest.delete(schedulePath(scheduleId));
    }

    public JsonNode enable(String scheduleId, Map<String, ?> route) throws Exception {
        return rest.post(RestClient.appendQuery(schedulePath(scheduleId) + "/enable", route), null);
    }

    public JsonNode disable(String scheduleId, Map<String, ?> route) throws Exception {
        return rest.post(RestClient.appendQuery(schedulePath(scheduleId) + "/disable", route), null);
    }

    public JsonNode runs(String scheduleId, int offset, int limit) throws Exception {
        return rest.get(RestClient.appendQuery(schedulePath(scheduleId) + "/runs",
                Map.of("offset", offset, "limit", limit)));
    }

    public JsonNode getRun(String runRecordId) throws Exception {
        return rest.get(BASE + "/runs/" + RestClient.pathSegment(runRecordId));
    }

    public JsonNode report(String runRecordId, JsonNode report) throws Exception {
        return rest.post(BASE + "/runs/" + RestClient.pathSegment(runRecordId) + "/report", report);
    }

    private static String schedulePath(String scheduleId) {
        return BASE + "/" + RestClient.pathSegment(scheduleId);
    }
}
