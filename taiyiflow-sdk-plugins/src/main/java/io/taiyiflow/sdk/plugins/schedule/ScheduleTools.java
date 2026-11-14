package io.taiyiflow.sdk.plugins.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.RestClient;
import io.taiyiflow.sdk.transport.rest.ScheduleClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.tool;

public final class ScheduleTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> CREATE_FIELDS = Set.of("name", "description", "schedule_type", "run_at",
            "interval_seconds", "cron_expr", "prompt", "request_mode", "working_directory", "session_mode",
            "session_id", "group_id", "timezone", "effective_start_at", "effective_end_at", "timeout_seconds",
            "mcp_server_ids", "skill_names");
    private static final String SCHEDULE_PROPERTIES = "\"name\":{\"type\":\"string\"},"
            + "\"description\":{\"type\":\"string\"},\"schedule_type\":{\"type\":\"string\",\"enum\":[\"once\",\"interval\",\"cron\"]},"
            + "\"run_at\":{\"type\":\"string\"},\"interval_seconds\":{\"type\":\"integer\",\"minimum\":60},"
            + "\"cron_expr\":{\"type\":\"string\"},\"prompt\":{\"type\":\"string\"},"
            + "\"request_mode\":{\"type\":\"string\",\"enum\":[\"agent\",\"ask\",\"plan\",\"command\"]},"
            + "\"working_directory\":{\"type\":\"string\"},\"session_mode\":{\"type\":\"string\",\"enum\":[\"always_new\",\"new_once\",\"existing\"]},"
            + "\"session_id\":{\"type\":\"string\"},\"group_id\":{\"type\":\"string\"},\"timezone\":{\"type\":\"string\"},"
            + "\"effective_start_at\":{\"type\":\"string\"},\"effective_end_at\":{\"type\":\"string\"},"
            + "\"timeout_seconds\":{\"type\":\"integer\",\"minimum\":1},"
            + "\"mcp_server_ids\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
            + "\"skill_names\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}";
    private final ScheduleClient schedules;

    private ScheduleTools(RestClient restClient) {
        this.schedules = new ScheduleClient(restClient);
    }

    public static void register(PluginManager plugins, RestClient restClient) {
        ScheduleTools tools = new ScheduleTools(restClient);
        plugins.register(tool("schedule_list", "List platform schedules visible to the current user.",
                "{\"is_template\":{\"type\":\"boolean\"}}"), handler(tools::list));
        plugins.register(tool("schedule_create", "Create a platform schedule.",
                "{" + SCHEDULE_PROPERTIES + "}", "name", "schedule_type", "prompt"), handler(tools::create));
        plugins.register(tool("schedule_update", "Update selected fields of a platform schedule.",
                "{\"schedule_id\":{\"type\":\"string\"}," + SCHEDULE_PROPERTIES + "}", "schedule_id"),
                handler(tools::update));
        plugins.register(tool("schedule_delete", "Delete a platform schedule.",
                "{\"schedule_id\":{\"type\":\"string\"}}", "schedule_id"), handler(tools::delete));
        plugins.register(tool("schedule_toggle", "Enable or disable a platform schedule.",
                "{\"schedule_id\":{\"type\":\"string\"},\"disabled\":{\"type\":\"boolean\"}}",
                "schedule_id", "disabled"), handler(tools::toggle));
        plugins.register(tool("schedule_runs", "List platform execution records for a schedule.",
                "{\"schedule_id\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}}",
                "schedule_id"), handler(tools::runs));
    }

    private JsonNode list(ToolExecutionContext context, JsonNode arguments) throws Exception {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("offset", 0);
        filters.put("limit", 100);
        filters.put("is_template", arguments.path("is_template").asBoolean(false));
        filters.put("flow_id", flowId(context));
        return schedules.list(filters);
    }

    private JsonNode create(ToolExecutionContext context, JsonNode arguments) throws Exception {
        validateSchedule(arguments, true);
        ObjectNode payload = copyFields(arguments, CREATE_FIELDS);
        payload.put("flow_id", flowId(context));
        return schedules.create(payload);
    }

    private JsonNode update(ToolExecutionContext context, JsonNode arguments) throws Exception {
        String id = requiredText(arguments, "schedule_id");
        ObjectNode payload = copyFields(arguments, CREATE_FIELDS);
        if (payload.has("schedule_type")) validateSchedule(payload, true);
        if (payload.isEmpty()) throw new IllegalArgumentException("No schedule fields were supplied");
        payload.put("flow_id", flowId(context));
        return schedules.update(id, payload);
    }

    private JsonNode delete(ToolExecutionContext context, JsonNode arguments) throws Exception {
        String id = requiredText(arguments, "schedule_id");
        schedules.delete(id);
        return MAPPER.createObjectNode().put("schedule_id", id).put("deleted", true);
    }

    private JsonNode toggle(ToolExecutionContext context, JsonNode arguments) throws Exception {
        String id = requiredText(arguments, "schedule_id");
        if (!arguments.path("disabled").isBoolean()) throw new IllegalArgumentException("disabled must be boolean");
        boolean disabled = arguments.path("disabled").asBoolean();
        JsonNode result = disabled ? schedules.disable(id, Map.of()) : schedules.enable(id, Map.of());
        if (result != null && !result.isNull() && !result.isMissingNode()) return result;
        return MAPPER.createObjectNode().put("schedule_id", id).put("disabled", disabled);
    }

    private JsonNode runs(ToolExecutionContext context, JsonNode arguments) throws Exception {
        String id = requiredText(arguments, "schedule_id");
        int limit = arguments.path("limit").canConvertToInt() ? arguments.path("limit").intValue() : 10;
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return schedules.runs(id, 0, limit);
    }

    private static PluginManager.ContextualToolHandler handler(ScheduleOperation operation) {
        return (context, call) -> {
            try {
                JsonNode arguments = call.getArguments() == null ? MAPPER.createObjectNode() : call.getArguments();
                return ToolResult.success(call.getId(), operation.execute(context, arguments));
            } catch (Exception exception) {
                return ToolResult.error(call.getId(), exception.getMessage());
            }
        };
    }

    private static ObjectNode copyFields(JsonNode arguments, Set<String> allowed) {
        ObjectNode result = MAPPER.createObjectNode();
        allowed.forEach(field -> {
            if (arguments.has(field)) result.set(field, arguments.get(field).deepCopy());
        });
        return result;
    }

    private static void validateSchedule(JsonNode arguments, boolean required) {
        String type = requiredText(arguments, "schedule_type");
        switch (type) {
            case "once" -> requiredText(arguments, "run_at");
            case "interval" -> {
                if (arguments.path("interval_seconds").asInt(0) < 60) {
                    throw new IllegalArgumentException("interval_seconds must be at least 60");
                }
            }
            case "cron" -> {
                String expression = requiredText(arguments, "cron_expr");
                if (expression.trim().split("\\s+").length != 5) {
                    throw new IllegalArgumentException("cron_expr must contain five fields");
                }
            }
            default -> throw new IllegalArgumentException("schedule_type must be once, interval, or cron");
        }
    }

    private static String requiredText(JsonNode arguments, String field) {
        String value = arguments.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String flowId(ToolExecutionContext context) {
        if (context.getFlowId() == null || context.getFlowId().isBlank()) {
            throw new IllegalArgumentException("Schedule tools require a trusted flowId");
        }
        return context.getFlowId();
    }

    @FunctionalInterface
    private interface ScheduleOperation {
        JsonNode execute(ToolExecutionContext context, JsonNode arguments) throws Exception;
    }
}
