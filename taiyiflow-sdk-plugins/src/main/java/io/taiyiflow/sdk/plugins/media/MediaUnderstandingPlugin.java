package io.taiyiflow.sdk.plugins.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.fs.WorkspacePaths;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.ConfigClient;
import io.taiyiflow.sdk.transport.rest.FileClient;
import io.taiyiflow.sdk.transport.rest.FlowRunClient;
import io.taiyiflow.sdk.transport.rest.RestClient;
import io.taiyiflow.sdk.transport.rest.ServiceConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.featureTool;

public final class MediaUnderstandingPlugin implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "svg", "webp", "bmp", "tif", "tiff");
    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "wav", "ogg", "flac", "aif", "m4a", "aac");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "webm", "avi", "mov", "flv", "wmv", "rmvb", "mkv");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            "mp3", "wav", "ogg", "flac", "aif", "m4a", "aac",
            "mp4", "webm", "avi", "mov", "flv", "wmv", "rmvb", "mkv",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx");
    private static final String SCREENSHOT_CONTEXT_PREFIX = "截图坐标上下文：";
    private static final String SCREENSHOT_DEFAULT_INPUT =
            "请概述截图中的界面结构、可见文本、可交互元素和关键状态信息。";
    private static final String SCREENSHOT_SYSTEM_TEMPLATE =
            "你是截图理解助手。当前输入图像仅来自桌面 ComputerUse 或浏览器自动化截图，不是文档结构化解析任务。"
            + "请只依据截图中可见的像素内容，优先识别界面结构、可交互元素、状态变化、错误提示、可见文本和与当前问题直接相关的线索；"
            + "不要臆测不可见内容，不要补全被遮挡区域，也不要把截图按文档理解流程处理。"
            + "若问题涉及按钮、输入框、链接或其他目标的位置，请优先使用截图左上角为原点的截图本地像素坐标(x,y)描述，"
            + "不要自行换算成全局桌面坐标；只有在问题明确要求且已提供显示器 origin 等元数据时，才说明如何换算。";
    private static final String SCREENSHOT_USER_TEMPLATE =
            "请仅依据截图中可见内容回答问题，并且只返回纯文本结果；若截图中没有足够证据，请直接说明\"截图中未见相关信息\"。\n问题：{question}";

    private final ConfigClient configClient;
    private final FileClient fileClient;
    private final FlowRunClient flowRunClient;
    private final Duration synchronousTimeout;
    private final FlowRunClient.PollOptions imagePollOptions;
    private final FlowRunClient.PollOptions audioVideoPollOptions;

    public MediaUnderstandingPlugin(RestClient restClient) {
        this(restClient, Duration.ofMinutes(29), FlowRunClient.PollOptions.defaults(Duration.ofMinutes(29)),
                FlowRunClient.PollOptions.defaults(Duration.ofMinutes(59)));
    }

    public MediaUnderstandingPlugin(RestClient restClient, Duration synchronousTimeout,
                                    FlowRunClient.PollOptions imagePollOptions,
                                    FlowRunClient.PollOptions audioVideoPollOptions) {
        this.configClient = new ConfigClient(restClient);
        this.fileClient = new FileClient(restClient);
        this.flowRunClient = new FlowRunClient(restClient);
        this.synchronousTimeout = synchronousTimeout;
        this.imagePollOptions = imagePollOptions;
        this.audioVideoPollOptions = audioVideoPollOptions;
    }

    public static MediaUnderstandingPlugin register(PluginManager pluginManager, RestClient restClient) {
        MediaUnderstandingPlugin plugin = new MediaUnderstandingPlugin(restClient);
        plugin.register(pluginManager);
        return plugin;
    }

    public void register(PluginManager pluginManager) {
        String common = "\"path\":{\"type\":\"string\"},"
                + "\"paths\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                + "\"input\":{\"type\":\"string\"},"
                + "\"input_type\":{\"type\":\"string\"},"
                + "\"working_directory\":{\"type\":\"string\"}";
        Duration documentTimeout = synchronousTimeout;
        Duration imageTimeout = max(synchronousTimeout, imagePollOptions.overallTimeout());
        Duration audioVideoTimeout = max(synchronousTimeout, audioVideoPollOptions.overallTimeout());
        pluginManager.register(featureTool("document_unstructured",
                "上传本地非图片文件到服务端文档结构化流程并返回稳定 JSON 结果；图片请使用 image_understanding。",
                "document_understanding", "{" + common + ",\"output_type\":{\"type\":\"string\"}}"),
                (PluginManager.ContextualToolHandler) this::document, documentTimeout);
        pluginManager.register(featureTool("image_understanding",
                "上传本地图像到服务端图像理解流程并返回纯文本结果；浏览器截图理解也复用此能力。",
                "image_understanding", "{" + common + "}"), (PluginManager.ContextualToolHandler) this::image, imageTimeout);
        pluginManager.register(featureTool("audio_understanding",
                "上传本地音频到服务端音频理解流程，返回语音转写和内容分析结果。",
                "audio_understanding", "{" + common + "}"), (PluginManager.ContextualToolHandler) this::audio, audioVideoTimeout);
        pluginManager.register(featureTool("video_understanding",
                "上传本地视频到服务端视频理解流程，返回画面、语音及整体内容分析结果。",
                "video_understanding", "{" + common + "}"), (PluginManager.ContextualToolHandler) this::video, audioVideoTimeout);
        pluginManager.registerCloseable(this);
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public ToolResult document(ToolExecutionContext context, ToolCall call) throws Exception {
        try {
            ServiceConfig config = configClient.getServiceConfig();
            String understandingFlow = config.documentFlowId();
            List<Path> paths = resolvePaths(context, call.getArguments());
            for (Path path : paths) {
                String extension = extension(path);
                if (IMAGE_EXTENSIONS.contains(extension)) {
                    throw new IllegalArgumentException("Image input must use image_understanding: " + path.getFileName());
                }
                if (!DOCUMENT_EXTENSIONS.contains(extension)) {
                    throw new IllegalArgumentException("Document extension is not enabled: " + extension);
                }
                List<String> serverExtensions = config.documentExtensions();
                if (!serverExtensions.isEmpty() && !serverExtensions.contains(extension)) {
                    throw new IllegalArgumentException("Server does not support document extension: " + extension);
                }
            }
            List<FileClient.UploadedFile> uploaded = upload(context, paths, config);
            ChatRequest request = request(context, call.getArguments(), uploaded);
            request.setOutputType(text(call.getArguments(), "output_type", "any"));
            FlowRunClient.RunResult result;
            try {
                result = flowRunClient.run(understandingFlow, request, synchronousTimeout);
            } catch (Exception exception) {
                throw new MediaOperationException("understanding", paths, uploaded, exception);
            }
            return ToolResult.success(call.getId(), documentResult(context, result, paths, uploaded));
        } catch (MediaOperationException exception) {
            return exception.toolResult(call.getId());
        }
    }

    public ToolResult image(ToolExecutionContext context, ToolCall call) throws Exception {
        return mediaTool(context, call, MediaKind.IMAGE);
    }

    public ToolResult audio(ToolExecutionContext context, ToolCall call) throws Exception {
        return mediaTool(context, call, MediaKind.AUDIO);
    }

    public ToolResult video(ToolExecutionContext context, ToolCall call) throws Exception {
        return mediaTool(context, call, MediaKind.VIDEO);
    }

    private ToolResult mediaTool(ToolExecutionContext context, ToolCall call, MediaKind kind) throws Exception {
        try {
            return ToolResult.success(call.getId(), mediaResult(context, call.getArguments(), kind, false));
        } catch (MediaOperationException exception) {
            return exception.toolResult(call.getId());
        }
    }

    public String understandScreenshot(ToolExecutionContext context, JsonNode arguments) throws Exception {
        ObjectNode result = mediaResult(context, arguments, MediaKind.IMAGE, true);
        return result.path("text").asText();
    }

    private ObjectNode mediaResult(ToolExecutionContext context, JsonNode arguments,
                                   MediaKind kind, boolean screenshot) throws Exception {
        ServiceConfig config = configClient.getServiceConfig();
        String understandingFlow = switch (kind) {
            case IMAGE -> config.imageFlowId();
            case AUDIO -> config.audioFlowId();
            case VIDEO -> config.videoFlowId();
        };
        List<Path> paths = resolvePaths(context, arguments);
        Set<String> supported = switch (kind) {
            case IMAGE -> IMAGE_EXTENSIONS;
            case AUDIO -> AUDIO_EXTENSIONS;
            case VIDEO -> VIDEO_EXTENSIONS;
        };
        for (Path path : paths) {
            String extension = extension(path);
            if (!supported.contains(extension)) {
                throw new IllegalArgumentException("Unsupported " + kind.name().toLowerCase(Locale.ROOT)
                        + " extension: " + extension);
            }
        }
        List<FileClient.UploadedFile> uploaded = upload(context, paths, config);
        ChatRequest request = request(context, arguments, uploaded);
        request.setOutputType("any");
        Map<String, JsonNode> tweaks = new LinkedHashMap<>();
        if (kind == MediaKind.IMAGE) {
            if (screenshot) {
                request.setInputValue(screenshotInput(arguments));
                tweaks.put("Prompt-d2zsV", MAPPER.createObjectNode().put("template", SCREENSHOT_SYSTEM_TEMPLATE));
                tweaks.put("Prompt-GRXIq", MAPPER.createObjectNode().put("template", SCREENSHOT_USER_TEMPLATE));
            }
            tweaks.put("TextOutput-fXIUJ", MAPPER.createObjectNode().put("output_format", "text"));
            String compression = screenshot ? "passthrough" : text(arguments, "image_compression_strategy", "");
            if (!compression.isBlank()) {
                tweaks.put("image_compression_strategy", TextNode.valueOf(compression));
            }
            request.setTweaks(tweaks);
        }
        FlowRunClient.Task task;
        try {
            FlowRunClient.Task submitted = flowRunClient.submitAsync(understandingFlow, request);
            FlowRunClient.PollOptions options = kind == MediaKind.IMAGE ? imagePollOptions : audioVideoPollOptions;
            task = flowRunClient.await(submitted.taskId(), options,
                    () -> context.getCancellation().isCancellationRequested());
            if (!"success".equals(task.status())) {
                throw new IllegalStateException(taskError(task));
            }
        } catch (Exception exception) {
            throw new MediaOperationException("understanding", paths, uploaded, exception);
        }
        FlowRunClient.RunResult result = task.result();
        if (result == null) {
            throw new MediaOperationException("understanding", paths, uploaded,
                    new IllegalStateException("Successful media understanding task returned no result"));
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.put("text", limit(outputText(result), 4 * 1024 * 1024));
        response.put("media_type", result.mediaType() == null ? "text/plain" : result.mediaType());
        response.put("session_id", firstNonBlank(result.sessionId(), task.sessionId(), context.getSessionId()));
        response.set("uploaded_files", uploadedFiles(paths, uploaded));
        return response;
    }

    private ObjectNode documentResult(ToolExecutionContext context, FlowRunClient.RunResult result,
                                      List<Path> paths, List<FileClient.UploadedFile> uploaded) throws Exception {
        String output = limit(outputText(result), 4 * 1024 * 1024).trim();
        JsonNode parsed = null;
        if (!output.isEmpty()) {
            try {
                parsed = MAPPER.readTree(output);
            } catch (Exception ignored) {
                parsed = null;
            }
        }
        ArrayNode items = MAPPER.createArrayNode();
        if (parsed != null) {
            if (parsed.isArray()) {
                parsed.forEach(items::add);
            } else if (parsed.isObject() && parsed.path("items").isArray()) {
                parsed.path("items").forEach(items::add);
            } else if (parsed.isTextual()) {
                if (!parsed.asText().isBlank()) {
                    items.addObject().put("text", parsed.asText().trim());
                }
            } else {
                items.add(parsed);
            }
        } else if (!output.isEmpty()) {
            items.addObject().put("text", output);
        }
        ObjectNode response = MAPPER.createObjectNode();
        response.put("count", items.size());
        response.set("items", items);
        response.put("media_type", result.mediaType() == null ? "text/plain" : result.mediaType());
        response.put("session_id", firstNonBlank(result.sessionId(), result.responseSessionHeader(),
                context.getSessionId()));
        response.set("uploaded_files", uploadedFiles(paths, uploaded));
        if (parsed != null && parsed.isObject()
                && !(parsed.size() == 1 && parsed.has("items"))) {
            response.set("parsed_output", parsed);
        } else if (parsed == null && !output.isEmpty()) {
            response.put("raw_output", output);
        }
        return response;
    }

    private List<Path> resolvePaths(ToolExecutionContext context, JsonNode arguments) throws Exception {
        if (context.getWorkspace() == null) {
            throw new IllegalStateException("Media understanding requires a session workspace");
        }
        List<String> rawPaths = new ArrayList<>();
        addPath(arguments, rawPaths, "path");
        addPath(arguments, rawPaths, "file_path");
        addPath(arguments, rawPaths, "filePath");
        addPaths(arguments, rawPaths, "paths");
        addPaths(arguments, rawPaths, "file_paths");
        addPaths(arguments, rawPaths, "filePaths");
        if (rawPaths.isEmpty()) {
            throw new IllegalArgumentException("At least one path is required");
        }
        Path workspace = context.getWorkspace().toRealPath();
        String workingDirectory = text(arguments, "working_directory", "");
        Path base = workspace;
        if (!workingDirectory.isBlank()) {
            Path candidate = workspace.resolve(workingDirectory).normalize();
            if (!candidate.startsWith(workspace)) {
                throw new SecurityException("Working directory escapes the configured workspace");
            }
            base = candidate.toRealPath();
            if (!base.startsWith(workspace) || !Files.isDirectory(base)) {
                throw new SecurityException("Working directory is outside the workspace or is not a directory");
            }
        }
        List<Path> result = new ArrayList<>();
        for (String rawPath : rawPaths) {
            result.add(WorkspacePaths.resolveExisting(base, rawPath));
        }
        return List.copyOf(result);
    }

    private List<FileClient.UploadedFile> upload(ToolExecutionContext context, List<Path> paths,
                                                  ServiceConfig config) throws Exception {
        if (context.getFlowId() == null || context.getFlowId().isBlank()) {
            throw new IllegalStateException("Media understanding requires the current business flow");
        }
        FileClient.validateFiles(paths, config);
        List<FileClient.UploadedFile> uploaded = new ArrayList<>();
        for (Path path : paths) {
            try {
                uploaded.add(fileClient.upload(context.getFlowId(), context.getSessionId(), path));
            } catch (Exception exception) {
                throw new MediaOperationException("upload", paths, uploaded, exception);
            }
        }
        return List.copyOf(uploaded);
    }

    private static ChatRequest request(ToolExecutionContext context, JsonNode arguments,
                                       List<FileClient.UploadedFile> uploaded) {
        ChatRequest request = new ChatRequest();
        request.setInputValue(firstNonBlank(text(arguments, "input", ""),
                text(arguments, "input_value", ""), text(arguments, "prompt", "")));
        request.setInputType(text(arguments, "input_type", "any"));
        request.setSessionId(context.getSessionId());
        request.setInputFiles(uploaded.stream().map(FileClient.UploadedFile::filePath).toList());
        return request;
    }

    private static ArrayNode uploadedFiles(List<Path> paths, List<FileClient.UploadedFile> uploaded) {
        ArrayNode result = MAPPER.createArrayNode();
        for (int index = 0; index < uploaded.size(); index++) {
            ObjectNode item = result.addObject();
            Path local = paths.get(index);
            if (local != null) {
                item.put("local_path", local.toString());
                item.put("extension", extension(local));
            }
            item.put("remote_path", uploaded.get(index).filePath());
        }
        return result;
    }

    private static String outputText(FlowRunClient.RunResult result) {
        if (result.json() != null && result.json().has("outputs")) {
            return String.join("\n", result.outputs());
        }
        if (!result.outputs().isEmpty()) {
            return String.join("\n", result.outputs());
        }
        return result.rawBody() == null ? "" : result.rawBody();
    }

    private static String taskError(FlowRunClient.Task task) {
        String message = task.error() == null ? "" : task.error().path("message").asText("");
        if (message.isBlank()) {
            message = "cancelled".equals(task.status()) ? "Media understanding task was cancelled"
                    : "Media understanding task failed";
        }
        String code = task.error() == null ? "" : task.error().path("code").asText("");
        return code.isBlank() ? message : message + " (" + code + ")";
    }

    private static String screenshotInput(JsonNode arguments) {
        String input = firstNonBlank(text(arguments, "input_value", ""), text(arguments, "input", ""),
                text(arguments, "prompt", ""), SCREENSHOT_DEFAULT_INPUT);
        if (input.contains(SCREENSHOT_CONTEXT_PREFIX)) {
            return input;
        }
        StringBuilder context = new StringBuilder(SCREENSHOT_CONTEXT_PREFIX)
                .append("若需要描述目标位置，请使用截图本地像素坐标(x,y)，原点在截图左上角；除非问题明确要求，否则不要直接输出全局桌面坐标。");
        int width = arguments.path("screenshot_width_px").asInt(0);
        int height = arguments.path("screenshot_height_px").asInt(0);
        if (width > 0 && height > 0) {
            context.append(" 当前截图尺寸为 ").append(width).append(" x ").append(height).append(" 像素。");
        }
        String displayId = text(arguments, "display_id", "");
        if (!displayId.isBlank()) {
            context.append(" 来源显示器 display_id=").append(displayId).append("。");
        }
        int displayIndex = arguments.path("display_index").asInt(0);
        if (displayIndex > 0) {
            context.append(" 来源显示器 display_index=").append(displayIndex).append("。");
        }
        if (arguments.has("display_origin_x") || arguments.has("display_origin_y")) {
            context.append(" 若后续必须换算为全局桌面坐标，再结合 display_origin_x=")
                    .append(arguments.path("display_origin_x").asInt(0)).append(" 和 display_origin_y=")
                    .append(arguments.path("display_origin_y").asInt(0)).append("。");
        }
        double scale = arguments.path("display_scale_factor").asDouble(0);
        if (scale > 0) {
            context.append(" 显示器 scale_factor=").append(scale).append("。");
        }
        return input + "\n\n" + context;
    }

    private static void addPath(JsonNode arguments, List<String> paths, String name) {
        String value = text(arguments, name, "");
        if (!value.isBlank()) {
            paths.add(value);
        }
    }

    private static void addPaths(JsonNode arguments, List<String> paths, String name) {
        JsonNode values = arguments == null ? null : arguments.get(name);
        if (values != null && values.isArray()) {
            values.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    paths.add(value.asText().trim());
                }
            });
        }
    }

    private static String text(JsonNode arguments, String name, String fallback) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText().trim() : fallback;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String limit(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value;
        }
        return value.substring(0, maximum) + "\n...[truncated]";
    }

    private static final class MediaOperationException extends Exception {
        private final String stage;
        private final List<Path> paths;
        private final List<FileClient.UploadedFile> uploaded;

        private MediaOperationException(String stage, List<Path> paths,
                                        List<FileClient.UploadedFile> uploaded, Exception cause) {
            super(stage + " failed: " + limit(cause.getMessage() == null ? cause.getClass().getSimpleName()
                    : cause.getMessage(), 8192), cause);
            this.stage = stage;
            this.paths = List.copyOf(paths);
            this.uploaded = List.copyOf(uploaded);
        }

        private ToolResult toolResult(String callId) {
            ToolResult result = ToolResult.error(callId, getMessage());
            ObjectNode details = MAPPER.createObjectNode();
            details.put("failed_stage", stage);
            details.set("uploaded_files", uploadedFiles(paths, uploaded));
            if (getCause() instanceof FlowRunClient.FlowRunTimeoutException timeout) {
                details.put("task_id", timeout.taskId());
                details.put("cancellation_requested", timeout.cancellationRequested());
            }
            result.setResult(details);
            return result;
        }
    }

    @Override
    public void close() {
        flowRunClient.close();
    }

    private enum MediaKind { IMAGE, AUDIO, VIDEO }
}
