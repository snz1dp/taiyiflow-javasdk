package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public class SkillClient {
    private static final String BASE = "/api/v1/agents/skills";
    private final RestClient rest;

    public SkillClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode list(Map<String, ?> context) throws Exception {
        return rest.get(RestClient.appendQuery(BASE, context));
    }

    public JsonNode getContent(String skillId, Map<String, ?> context) throws Exception {
        return rest.get(RestClient.appendQuery(BASE + "/" + RestClient.pathSegment(skillId), context));
    }

    public JsonNode upload(JsonNode skill) throws Exception {
        return rest.post(BASE + "/upload", skill);
    }

    public JsonNode uploadArchive(String name, String displayName, String filename, byte[] zipContent) throws Exception {
        return rest.postMultipart(BASE + "/upload", Map.of("name", name, "display_name", displayName),
                new RestClient.MultipartFile("file", filename, "application/zip", zipContent));
    }

    public void delete(String skillId) throws Exception {
        rest.delete(BASE + "/" + RestClient.pathSegment(skillId));
    }

    public JsonNode reportInstallation(JsonNode installation) throws Exception {
        return rest.post(BASE + "/installations", installation);
    }

    public JsonNode installations(Map<String, ?> filters) throws Exception {
        return rest.get(RestClient.appendQuery(BASE + "/installations", filters));
    }

    public JsonNode uninstall(String recordId) throws Exception {
        return rest.post(BASE + "/installations/" + RestClient.pathSegment(recordId) + "/uninstall", null);
    }
}
