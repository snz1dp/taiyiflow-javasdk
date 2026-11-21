package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quota and credit preflight ({@code /quotas/*}, {@code /credits/*}). Check before starting a
 * chat so exhaustion surfaces as a structured result instead of WS close code 4003
 * (quota_session_exceeded / entry rejection); 4003 is a WebSocket close code, not an HTTP status.
 */
public class QuotaCreditClient {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final RestClient rest;

    public QuotaCreditClient(RestClient rest) {
        this.rest = rest;
    }

    /** Current plan/period usage: quota_type, current_plan, current_period (remaining_quota etc.), features. */
    public JsonNode currentUsage() throws Exception {
        return rest.get("/api/v1/quotas/usage/current");
    }

    /** Preflight quota check; response carries passed/remaining_quota/overage/message. */
    public JsonNode checkQuota(int requiredCredits) throws Exception {
        if (requiredCredits < 1) {
            throw new IllegalArgumentException("requiredCredits must be >= 1");
        }
        return rest.post("/api/v1/quotas/check",
                mapper.createObjectNode().put("required_credits", requiredCredits));
    }

    /** Credit balance; ownerAppid may be null for the personal scope. Response: total_balance, accounts[]. */
    public JsonNode balance(String ownerAppid) throws Exception {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("owner_appid", ownerAppid);
        return rest.get(RestClient.appendQuery("/api/v1/credits/balance", query));
    }

    /** Credit preflight with backend defaults (model_feature=chat, 1000 input / 2000 output tokens). */
    public JsonNode checkCredits(String modelKey) throws Exception {
        return checkCredits(modelKey, null, null, null, null);
    }

    /**
     * Credit preflight; response carries pass/required_credits/matched_chain/active_account.
     * Null arguments fall back to backend defaults (model_feature=chat,
     * estimated_input_tokens=1000, estimated_output_tokens=2000).
     */
    public JsonNode checkCredits(String modelKey, String modelFeature, Integer estimatedInputTokens,
                                 Integer estimatedOutputTokens, String ownerAppid) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("model_key", modelKey);
        if (modelFeature != null) {
            body.put("model_feature", modelFeature);
        }
        if (estimatedInputTokens != null) {
            body.put("estimated_input_tokens", estimatedInputTokens);
        }
        if (estimatedOutputTokens != null) {
            body.put("estimated_output_tokens", estimatedOutputTokens);
        }
        if (ownerAppid != null) {
            body.put("owner_appid", ownerAppid);
        }
        return rest.post("/api/v1/credits/check", body);
    }
}