package io.taiyiflow.sdk.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SelectOption;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.fs.WorkspacePaths;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.plugins.media.ImageUnderstandingService;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.RestClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.tool;

public final class BrowserRuntime implements SessionHook, AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "taiyiflow-in-chrome__";
    private static final String DOM_GENERATION_SCRIPT = "(()=>{if(!window.__taiyiflowDomGeneration){"
            + "window.__taiyiflowDomGeneration=1;const root=document.documentElement||document;"
            + "new MutationObserver(()=>window.__taiyiflowDomGeneration++).observe(root,"
            + "{subtree:true,childList:true,attributes:true,characterData:true});}"
            + "return window.__taiyiflowDomGeneration;})()";

    private final BrowserSettings settings;
    private final BrowserNetworkPolicy networkPolicy;
    private final ImageUnderstandingService imageUnderstanding;
    private final ExecutorService driver = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "taiyiflow-browser-driver");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, SessionPages> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Playwright playwright;
    private Browser browser;
    private boolean remoteDisconnected;
    private long nextConnectionAttempt;

    BrowserRuntime(BrowserSettings settings, RestClient restClient) {
        this.settings = settings;
        this.networkPolicy = new BrowserNetworkPolicy(settings);
        this.imageUnderstanding = new ImageUnderstandingService(restClient);
    }

    void register(PluginManager pluginManager) {
        register(pluginManager, "tabs_context", "List tabs owned by the current SDK session.", "", this::tabsContext);
        register(pluginManager, "tabs_create", "Create a tab owned by the current SDK session.",
                property("url", "string") + "," + property("activate", "boolean"), this::tabsCreate);
        register(pluginManager, "navigate", "Navigate an SDK-owned tab to an allowed HTTP(S) URL.",
                property("url", "string") + "," + tabProperty(), this::navigate, "url");
        register(pluginManager, "set_view_size", "Set the current tab viewport size.",
                property("mode", "string") + "," + property("width", "integer") + ","
                        + property("height", "integer") + "," + tabProperty(), this::setViewSize);
        register(pluginManager, "wait_for", "Wait for an element, page text, or network idle.",
                property("selector", "string") + "," + property("text", "string") + ","
                        + property("network_idle", "boolean") + "," + property("timeout_ms", "integer")
                        + "," + tabProperty(), this::waitFor);
        register(pluginManager, "click", "Click an element in an SDK-owned tab.", selectorProperties(),
                this::click);
        register(pluginManager, "type", "Type text into an element in an SDK-owned tab.",
                selectorProperties() + "," + property("text", "string") + "," + property("clear", "boolean")
                        + "," + property("submit", "boolean") + "," + property("append", "boolean")
                        + "," + property("press_enter", "boolean"), this::type, "text");
        register(pluginManager, "hover", "Hover over an element.", selectorProperties(), this::hover);
        register(pluginManager, "scroll", "Scroll the page or an element.",
                optionalSelectorProperties() + "," + property("delta_x", "integer") + ","
                        + property("delta_y", "integer"), this::scroll);
        register(pluginManager, "select_option", "Select an option by value or label.",
                selectorProperties() + "," + property("value", "string") + "," + property("label", "string")
                        + "," + property("text", "string"),
                this::selectOption);
        register(pluginManager, "press_key", "Press a key or supported modifier combination.",
                property("key", "string") + ",\"modifiers\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
                        + tabProperty(), this::pressKey, "key");
        register(pluginManager, "get_element_info", "Read structured element information.",
                selectorProperties(), this::elementInfo);
        register(pluginManager, "get_element_ref", "Create a navigation and DOM-generation-bound element reference.",
                property("selector", "string") + "," + tabProperty(), this::elementRef, "selector");
        pluginManager.register(tool(PREFIX + "screenshot",
                        "Save a bounded PNG screenshot in the session workspace.",
                        "{" + property("full_page", "boolean") + "," + property("understand", "boolean") + ","
                                + property("input", "string") + "," + property("input_value", "string") + ","
                                + tabProperty() + "}"),
                (PluginManager.ContextualToolHandler) this::screenshotTool);
        register(pluginManager, "read_text", "Read bounded page or element text.",
                optionalSelectorProperties() + "," + property("max_characters", "integer")
                        + "," + property("max_chars", "integer"), this::readText);
        register(pluginManager, "read_console", "Read bounded console messages from an SDK-owned tab.",
                property("limit", "integer") + "," + tabProperty(), this::readConsole);
        register(pluginManager, "execute_js", "Execute JavaScript only when the independent script switch is enabled.",
                property("code", "string") + "," + tabProperty(), this::executeJs, "code");
        // Explicit unsupported result instead of the generic "No handler for tool", so the model
        // stops retrying a capability the Chromium-only runtime cannot provide.
        pluginManager.register(tool(PREFIX + "set_browser_type",
                        "Not supported: the SDK browser runtime is Chromium-only.",
                        "{" + property("os", "string") + "," + property("browser", "string") + ","
                                + tabProperty() + "}", "os", "browser"),
                (PluginManager.ContextualToolHandler) (context, call) -> ToolResult.error(call.getId(),
                        "set_browser_type is not supported (Chromium-only): the Java SDK browser runtime cannot"
                                + " switch browser type or UserAgent presets. Do not retry this tool;"
                                + " continue with the default Chromium browser."));
        pluginManager.registerCloseable(this);
        pluginManager.registerSessionHook(this);
    }

    private void register(PluginManager plugins, String action, String description, String properties,
                          BrowserHandler handler, String... required) {
        String schema = properties.isBlank() ? "{}" : "{" + properties + "}";
        plugins.register(tool(PREFIX + action, description, schema, required),
                (PluginManager.ContextualToolHandler) (context, call) -> invoke(context, call,
                        () -> handler.execute(context, call.getArguments())));
    }

    private ToolResult invoke(ToolExecutionContext context, ToolCall call, JsonOperation operation) {
        try {
            return ToolResult.success(call.getId(), run(context, operation));
        } catch (Exception exception) {
            return ToolResult.error(call.getId(), safeError(exception));
        }
    }

    private JsonNode run(ToolExecutionContext context, JsonOperation operation) throws Exception {
        if (closed.get()) throw new IllegalStateException("Browser runtime is closed");
        String key = key(context);
        Future<JsonNode> future = driver.submit(() -> {
            if (context.getCancellation().isCancellationRequested()) {
                throw new InterruptedException("Browser operation cancelled before execution");
            }
            return operation.execute();
        });
        long deadline = System.nanoTime() + settings.getActionTimeout().toNanos();
        try {
            while (true) {
                if (context.getCancellation().isCancellationRequested()) {
                    future.cancel(true);
                    invalidate(key);
                    throw new InterruptedException("Browser operation cancelled");
                }
                try {
                    return future.get(Math.min(50, Math.max(1,
                            (deadline - System.nanoTime()) / 1_000_000)), TimeUnit.MILLISECONDS);
                } catch (TimeoutException exception) {
                    if (System.nanoTime() >= deadline) {
                        future.cancel(true);
                        invalidate(key);
                        throw new TimeoutException("Browser operation timed out");
                    }
                }
            }
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) throw checked;
            throw exception;
        }
    }

    private JsonNode tabsContext(ToolExecutionContext context, JsonNode arguments) {
        return tabs(context);
    }

    private JsonNode tabsCreate(ToolExecutionContext context, JsonNode arguments) {
        SessionPages session = session(context, true);
        if (session.tabs.size() >= settings.getMaxPagesPerSession()) {
            throw new IllegalStateException("Maximum pages per session reached");
        }
        Tab tab = createTab(session);
        String url = text(arguments, "url", "about:blank");
        navigate(tab, url);
        if (booleanValue(arguments, "activate", true)) session.activeTab = tab.id;
        return tabJson(tab, session.activeTab);
    }

    private JsonNode navigate(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, true);
        navigate(tab, text(arguments, "url", ""));
        return tabJson(tab, tab.session.activeTab);
    }

    private JsonNode setViewSize(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, true);
        String mode = text(arguments, "mode", "desktop");
        int width = switch (mode) {
            case "desktop" -> 1280;
            case "mobile" -> 375;
            case "custom" -> intValue(arguments, "width", 0);
            default -> throw new IllegalArgumentException("Unknown viewport mode");
        };
        int height = switch (mode) {
            case "desktop" -> 720;
            case "mobile" -> 812;
            case "custom" -> intValue(arguments, "height", 0);
            default -> throw new IllegalArgumentException("Unknown viewport mode");
        };
        if (width < 200 || width > 4096 || height < 200 || height > 4096) {
            throw new IllegalArgumentException("Viewport dimensions must be between 200 and 4096");
        }
        tab.page.setViewportSize(width, height);
        invalidateRefs(tab);
        ObjectNode result = tabJson(tab, tab.session.activeTab);
        result.put("width", width);
        result.put("height", height);
        return result;
    }

    private JsonNode waitFor(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        double timeout = Math.min(30_000, Math.max(1, intValue(arguments, "timeout_ms", 15_000)));
        String selector = text(arguments, "selector", "");
        String text = text(arguments, "text", "");
        if (!selector.isBlank()) {
            tab.page.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeout));
        } else if (!text.isBlank()) {
            tab.page.waitForFunction("text => document.body && document.body.innerText.includes(text)", text,
                    new Page.WaitForFunctionOptions().setTimeout(timeout));
        }
        if (booleanValue(arguments, "network_idle", false)) {
            tab.page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(timeout));
        }
        if (selector.isBlank() && text.isBlank() && !booleanValue(arguments, "network_idle", false)) {
            throw new IllegalArgumentException("wait_for requires selector, text, or network_idle");
        }
        return MAPPER.createObjectNode().put("waited", true);
    }

    private JsonNode click(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        locator(tab, arguments).click(new Locator.ClickOptions().setTimeout(timeoutMillis()));
        return MAPPER.createObjectNode().put("clicked", true);
    }

    private JsonNode type(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        Locator locator = locator(tab, arguments);
        String value = text(arguments, "text", null);
        if (value == null) throw new IllegalArgumentException("type requires text");
        // Electron aliases: append=true means do not clear; press_enter means submit.
        boolean clear = has(arguments, "clear") ? booleanValue(arguments, "clear", true)
                : !booleanValue(arguments, "append", false);
        boolean submit = has(arguments, "submit") ? booleanValue(arguments, "submit", false)
                : booleanValue(arguments, "press_enter", false);
        if (clear) {
            locator.fill(value, new Locator.FillOptions().setTimeout(timeoutMillis()));
        } else {
            locator.pressSequentially(value, new Locator.PressSequentiallyOptions().setTimeout(timeoutMillis()));
        }
        if (submit) locator.press("Enter");
        return MAPPER.createObjectNode().put("typed", true);
    }

    private JsonNode hover(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        locator(tab, arguments).hover(new Locator.HoverOptions().setTimeout(timeoutMillis()));
        return MAPPER.createObjectNode().put("hovered", true);
    }

    private JsonNode scroll(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        int deltaX = bounded(intValue(arguments, "delta_x", 0), -100_000, 100_000);
        int deltaY = bounded(intValue(arguments, "delta_y", 300), -100_000, 100_000);
        String selector = selector(tab, arguments, false);
        int[] delta = {deltaX, deltaY};
        if (selector.isBlank()) {
            tab.page.evaluate("delta => window.scrollBy(delta[0], delta[1])", delta);
        } else {
            tab.page.locator(selector).evaluate("(el, delta) => el.scrollBy(delta[0], delta[1])", delta);
        }
        return MAPPER.createObjectNode().put("delta_x", deltaX).put("delta_y", deltaY);
    }

    private JsonNode selectOption(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        Locator locator = locator(tab, arguments);
        String value = text(arguments, "value", "");
        String label = has(arguments, "label") ? text(arguments, "label", "") : text(arguments, "text", "");
        List<String> selected;
        if (!value.isBlank() == !label.isBlank()) {
            throw new IllegalArgumentException("select_option requires exactly one of value or label");
        }
        if (!value.isBlank()) {
            selected = locator.selectOption(value, new Locator.SelectOptionOptions().setTimeout(timeoutMillis()));
        } else {
            selected = locator.selectOption(new SelectOption().setLabel(label),
                    new Locator.SelectOptionOptions().setTimeout(timeoutMillis()));
        }
        return MAPPER.valueToTree(selected);
    }

    private JsonNode pressKey(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        String key = text(arguments, "key", "");
        if (key.isBlank() || key.length() > 64) throw new IllegalArgumentException("A bounded key is required");
        List<String> parts = new ArrayList<>();
        if (arguments != null && arguments.path("modifiers").isArray()) {
            arguments.path("modifiers").forEach(modifier -> {
                String normalized = switch (modifier.asText().toLowerCase(java.util.Locale.ROOT)) {
                    case "alt" -> "Alt";
                    case "ctrl" -> "Control";
                    case "shift" -> "Shift";
                    case "meta" -> "Meta";
                    default -> throw new IllegalArgumentException("Unsupported key modifier");
                };
                parts.add(normalized);
            });
        }
        parts.add(key);
        tab.page.keyboard().press(String.join("+", parts));
        return MAPPER.createObjectNode().put("pressed", true);
    }

    private JsonNode elementInfo(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        String selector = selector(tab, arguments, true);
        Locator locator = tab.page.locator(selector);
        ObjectNode result = MAPPER.createObjectNode();
        result.put("selector", selector);
        result.put("visible", locator.isVisible());
        result.put("text", limit(locator.textContent(), settings.getMaxReadTextCharacters()));
        result.put("value", limit(String.valueOf(locator.evaluate("el => el.value == null ? '' : el.value")), 4000));
        result.put("outer_html", limit(String.valueOf(locator.evaluate("el => el.outerHTML")), 8000));
        result.set("attributes", MAPPER.valueToTree(locator.evaluate(
                "el => Object.fromEntries(Array.from(el.attributes).map(a => [a.name, a.value]))")));
        var box = locator.boundingBox();
        if (box != null) {
            ObjectNode bounds = result.putObject("bounds");
            bounds.put("x", box.x);
            bounds.put("y", box.y);
            bounds.put("width", box.width);
            bounds.put("height", box.height);
        }
        return result;
    }

    private JsonNode elementRef(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        String selector = text(arguments, "selector", "");
        if (selector.isBlank()) throw new IllegalArgumentException("get_element_ref requires selector");
        Locator locator = tab.page.locator(selector);
        long generation = domGeneration(tab);
        String id = "el-" + UUID.randomUUID();
        tab.refs.put(id, new ElementRef(selector, generation));
        ObjectNode result = MAPPER.createObjectNode();
        result.put("element_ref_id", id);
        result.put("selector", selector);
        result.put("tab_id", tab.id);
        result.put("url", tab.page.url());
        result.put("text", limit(locator.textContent(), settings.getMaxReadTextCharacters()));
        result.put("outer_html", limit(String.valueOf(locator.evaluate("el => el.outerHTML")), 8000));
        return result;
    }

    private ToolResult screenshotTool(ToolExecutionContext context, ToolCall call) {
        JsonNode arguments = call.getArguments();
        if (has(arguments, "path") && !text(arguments, "path", "").isBlank()) {
            return ToolResult.error(call.getId(), "screenshot does not accept a custom path: screenshots are"
                    + " always saved to the session workspace (.snz1dp/browser/). Remove the path argument"
                    + " and use the returned path instead.");
        }
        try {
            ObjectNode result = (ObjectNode) run(context, () -> screenshot(context, arguments));
            if (booleanValue(arguments, "understand", false)) {
                addImageUnderstanding(context, result, arguments);
            }
            return ToolResult.success(call.getId(), result);
        } catch (Exception exception) {
            return ToolResult.error(call.getId(), safeError(exception));
        }
    }

    private JsonNode screenshot(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        if (context.getWorkspace() == null) throw new IllegalStateException("Screenshot requires a session workspace");
        boolean fullPage = booleanValue(arguments, "full_page", false);
        if (fullPage) {
            Number pixels = (Number) tab.page.evaluate(
                    "() => Math.max(document.documentElement.scrollWidth, innerWidth)"
                            + " * Math.max(document.documentElement.scrollHeight, innerHeight)");
            if (pixels.longValue() > 16_777_216L) {
                throw new IllegalArgumentException("Full-page screenshot exceeds the pixel limit");
            }
        }
        byte[] bytes = tab.page.screenshot(new Page.ScreenshotOptions().setFullPage(fullPage)
                .setTimeout(timeoutMillis()));
        if (bytes.length > settings.getMaxScreenshotBytes()) {
            throw new IllegalArgumentException("Screenshot exceeds the configured byte limit");
        }
        String relative = ".snz1dp/browser/" + UUID.randomUUID() + ".png";
        try {
            Path output = WorkspacePaths.resolveForWrite(context.getWorkspace(), relative);
            Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            ObjectNode result = tabJson(tab, tab.session.activeTab);
            result.put("path", relative);
            result.put("bytes", bytes.length);
            result.put("format", "png");
            result.put("viewport_width", tab.page.viewportSize().width);
            result.put("viewport_height", tab.page.viewportSize().height);
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to persist screenshot in the session workspace", exception);
        }
    }

    void addImageUnderstanding(ToolExecutionContext context, ObjectNode result, JsonNode arguments) {
        ObjectNode understandingArguments = MAPPER.createObjectNode();
        understandingArguments.put("path", result.path("path").asText());
        understandingArguments.put("input", has(arguments, "input")
                ? text(arguments, "input", "") : text(arguments, "input_value", ""));
        understandingArguments.put("screenshot_width_px", result.path("viewport_width").asInt());
        understandingArguments.put("screenshot_height_px", result.path("viewport_height").asInt());
        try {
            result.put("image_understanding", imageUnderstanding.understandScreenshot(context,
                    understandingArguments));
        } catch (Exception exception) {
            result.put("image_understanding_error", safeError(exception));
        }
    }

    private JsonNode readText(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        int requested = has(arguments, "max_characters")
                ? intValue(arguments, "max_characters", settings.getMaxReadTextCharacters())
                : intValue(arguments, "max_chars", settings.getMaxReadTextCharacters());
        int maximum = bounded(requested, 1, settings.getMaxReadTextCharacters());
        String selector = selector(tab, arguments, false);
        String text = selector.isBlank() ? tab.page.locator("body").innerText()
                : tab.page.locator(selector).innerText();
        ObjectNode result = MAPPER.createObjectNode();
        result.put("text", limit(text, maximum));
        result.put("truncated", text != null && text.length() > maximum);
        return result;
    }

    private JsonNode readConsole(ToolExecutionContext context, JsonNode arguments) {
        Tab tab = tab(context, arguments, false);
        int limit = bounded(intValue(arguments, "limit", settings.getMaxConsoleMessages()),
                1, settings.getMaxConsoleMessages());
        ArrayNode messages = MAPPER.createArrayNode();
        int characters = 0;
        boolean truncated = false;
        List<ConsoleEntry> entries = new ArrayList<>(tab.console);
        for (int index = Math.max(0, entries.size() - limit); index < entries.size(); index++) {
            ConsoleEntry entry = entries.get(index);
            if (characters + entry.text.length() > 64_000) {
                truncated = true;
                break;
            }
            ObjectNode item = messages.addObject();
            item.put("type", entry.type);
            item.put("text", entry.text);
            characters += entry.text.length();
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.set("messages", messages);
        result.put("truncated", truncated || tab.consoleOverflow);
        return result;
    }

    private JsonNode executeJs(ToolExecutionContext context, JsonNode arguments) {
        if (!settings.isExecuteJsEnabled()) {
            throw new SecurityException("execute_js requires BrowserSettings.executeJsEnabled(true)");
        }
        Tab tab = tab(context, arguments, false);
        String code = text(arguments, "code", "");
        if (code.isBlank() || code.length() > 100_000) {
            throw new IllegalArgumentException("A non-empty, bounded JavaScript expression is required");
        }
        Object value = tab.page.evaluate(code);
        JsonNode converted = MAPPER.valueToTree(value);
        if (converted.toString().length() > 100_000) {
            throw new IllegalArgumentException("JavaScript result exceeds the size limit");
        }
        return converted;
    }

    private ObjectNode tabs(ToolExecutionContext context) {
        SessionPages session = session(context, false);
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tabs = result.putArray("tabs");
        if (session != null) {
            session.tabs.values().forEach(tab -> tabs.add(tabJson(tab, session.activeTab)));
            if (session.activeTab != null) result.put("active_tab_id", session.activeTab);
        }
        result.put("browser_type", "chromium");
        result.put("mode", settings.getMode().name());
        return result;
    }

    private SessionPages session(ToolExecutionContext context, boolean create) {
        String key = key(context);
        SessionPages existing = sessions.get(key);
        if (existing != null) {
            if (!existing.valid || browser == null || !browser.isConnected()) {
                sessions.remove(key);
                closeQuietly(existing.context);
                if (settings.getMode() == BrowserSettings.Mode.REMOTE_CDP) {
                    remoteDisconnected = true;
                }
            } else {
                return existing;
            }
        }
        if (!create) return null;
        ensureBrowser();
        remoteDisconnected = false;
        BrowserContext browserContext = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 720));
        browserContext.setDefaultTimeout(timeoutMillis());
        browserContext.route("**/*", route -> {
            if (networkPolicy.isAllowed(route.request().url())) route.resume();
            else route.abort("blockedbyclient");
        });
        SessionPages created = new SessionPages(browserContext);
        sessions.put(key, created);
        return created;
    }

    private void ensureBrowser() {
        if (browser != null && browser.isConnected()) return;
        long now = System.nanoTime();
        if (now < nextConnectionAttempt) {
            throw new IllegalStateException("Browser connection backoff is active");
        }
        try {
            if (playwright == null) {
                playwright = Playwright.create(new Playwright.CreateOptions()
                        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            }
            BrowserType chromium = playwright.chromium();
            if (settings.getMode() == BrowserSettings.Mode.HEADLESS) {
                BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true)
                        .setArgs(settings.getLaunchArguments());
                if (settings.getExecutablePath() != null) options.setExecutablePath(settings.getExecutablePath());
                browser = chromium.launch(options);
            } else {
                BrowserType.ConnectOverCDPOptions options = new BrowserType.ConnectOverCDPOptions()
                        .setHeaders(settings.remoteHeaders()).setTimeout(timeoutMillis());
                browser = chromium.connectOverCDP(settings.remoteEndpoint(), options);
            }
            Browser connectedBrowser = browser;
            browser.onDisconnected(ignored -> {
                if (browser != connectedBrowser) return;
                remoteDisconnected = settings.getMode() == BrowserSettings.Mode.REMOTE_CDP;
                nextConnectionAttempt = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                sessions.values().forEach(session -> session.valid = false);
            });
            nextConnectionAttempt = 0;
        } catch (Exception exception) {
            nextConnectionAttempt = now + Duration.ofSeconds(30).toNanos();
            throw new IllegalStateException(settings.getMode() == BrowserSettings.Mode.REMOTE_CDP
                    ? "Unable to connect to remote Chromium at " + settings.redactedRemoteHost()
                    : "Unable to start fixed local Chromium", null);
        }
    }

    private Tab createTab(SessionPages session) {
        Page page = session.context.newPage();
        page.setDefaultTimeout(timeoutMillis());
        Tab tab = new Tab(session, UUID.randomUUID().toString(), page);
        session.tabs.put(tab.id, tab);
        session.activeTab = tab.id;
        page.onConsoleMessage(message -> {
            if (tab.console.size() >= settings.getMaxConsoleMessages()) {
                tab.console.removeFirst();
                tab.consoleOverflow = true;
            }
            tab.console.addLast(new ConsoleEntry(message.type(), limit(message.text(), 4000)));
        });
        page.onClose(ignored -> invalidate(tab));
        page.onCrash(ignored -> invalidate(tab));
        page.onDOMContentLoaded(ignored -> invalidateRefs(tab));
        navigate(tab, "about:blank");
        return tab;
    }

    private Tab tab(ToolExecutionContext context, JsonNode arguments, boolean create) {
        SessionPages session = session(context, create);
        if (session == null) throw new IllegalStateException("No browser tab exists; call tabs_create first");
        String requested = text(arguments, "tab_id", "");
        String id = requested.isBlank() ? session.activeTab : requested;
        Tab tab = session.tabs.get(id);
        if (tab == null || !session.valid || tab.page.isClosed()) {
            throw new IllegalArgumentException("Tab is not owned by this session or is no longer valid");
        }
        return tab;
    }

    private Locator locator(Tab tab, JsonNode arguments) {
        return tab.page.locator(selector(tab, arguments, true));
    }

    private String selector(Tab tab, JsonNode arguments, boolean required) {
        String reference = text(arguments, "element_ref_id", "");
        if (!reference.isBlank()) {
            ElementRef element = tab.refs.get(reference);
            if (element == null || element.generation != domGeneration(tab)) {
                throw new IllegalArgumentException("Element reference is stale or belongs to another tab/session");
            }
            return element.selector;
        }
        String selector = text(arguments, "selector", "");
        if (selector.length() > 2000 || (required && selector.isBlank())) {
            throw new IllegalArgumentException("A valid selector or element_ref_id is required");
        }
        return selector;
    }

    private long domGeneration(Tab tab) {
        return ((Number) tab.page.evaluate(DOM_GENERATION_SCRIPT)).longValue();
    }

    private void navigate(Tab tab, String url) {
        URI validated = networkPolicy.validate(url);
        if (!"about:blank".equals(url) || !"about:blank".equals(tab.page.url())) {
            tab.page.navigate(validated.toString(), new Page.NavigateOptions().setTimeout(timeoutMillis()));
        }
        networkPolicy.validate(tab.page.url());
        invalidateRefs(tab);
    }

    private void invalidate(Tab tab) {
        invalidateRefs(tab);
        tab.session.tabs.remove(tab.id);
        if (tab.id.equals(tab.session.activeTab)) {
            tab.session.activeTab = tab.session.tabs.keySet().stream().findFirst().orElse(null);
        }
    }

    private static void invalidateRefs(Tab tab) {
        tab.refs.clear();
    }

    private void invalidate(String key) {
        SessionPages session = sessions.get(key);
        if (session != null) session.valid = false;
    }

    private ObjectNode tabJson(Tab tab, String active) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("tab_id", tab.id);
        result.put("title", limit(tab.page.title(), 1000));
        result.put("url", tab.page.url());
        result.put("active", tab.id.equals(active));
        return result;
    }

    @Override
    public void onClose(String sessionId) {
        if (closed.get()) return;
        try {
            driver.submit(() -> {
                for (String key : List.copyOf(sessions.keySet())) {
                    if (!key.endsWith("\0" + sessionId)) continue;
                    SessionPages removed = sessions.remove(key);
                    if (removed != null) closeQuietly(removed.context);
                }
                return null;
            }).get(Math.round(timeoutMillis()), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            invalidateSessions(sessionId);
        }
    }

    private void invalidateSessions(String sessionId) {
        sessions.forEach((key, session) -> {
            if (key.endsWith("\0" + sessionId)) session.valid = false;
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            driver.submit(() -> {
                sessions.values().forEach(session -> closeQuietly(session.context));
                sessions.clear();
                if (browser != null && settings.getMode() == BrowserSettings.Mode.HEADLESS) {
                    try { browser.close(); } catch (Exception ignored) { }
                }
                browser = null;
                if (playwright != null) {
                    try { playwright.close(); } catch (Exception ignored) { }
                }
                playwright = null;
                return null;
            }).get(Math.round(timeoutMillis()), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            sessions.values().forEach(session -> session.valid = false);
        } finally {
            driver.shutdownNow();
            imageUnderstanding.close();
        }
    }

    private String key(ToolExecutionContext context) {
        if (context.getFlowId() == null || context.getFlowId().isBlank()
                || context.getSessionId() == null || context.getSessionId().isBlank()) {
            throw new IllegalArgumentException("Browser tools require trusted flowId and sessionId");
        }
        return context.getFlowId() + "\0" + context.getSessionId();
    }

    private String safeError(Throwable throwable) {
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        if (settings.remoteEndpoint() != null) message = message.replace(settings.remoteEndpoint(), "[redacted-endpoint]");
        for (String secret : settings.remoteHeaders().values()) {
            if (secret != null && !secret.isBlank()) message = message.replace(secret, "[redacted-header]");
        }
        return limit(message, 8192);
    }

    private double timeoutMillis() {
        return settings.getActionTimeout().toMillis();
    }

    private static void closeQuietly(BrowserContext context) {
        if (context == null) return;
        try { context.close(); } catch (Exception ignored) { }
    }

    private static String property(String name, String type) {
        return "\"" + name + "\":{\"type\":\"" + type + "\"}";
    }

    private static String tabProperty() {
        return property("tab_id", "string");
    }

    private static String selectorProperties() {
        return property("selector", "string") + "," + property("element_ref_id", "string") + "," + tabProperty();
    }

    private static String optionalSelectorProperties() {
        return property("selector", "string") + "," + tabProperty();
    }

    private static boolean has(JsonNode arguments, String name) {
        return arguments != null && arguments.has(name);
    }

    private static String text(JsonNode arguments, String name, String fallback) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static int intValue(JsonNode arguments, String name, int fallback) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value != null && value.isNumber() ? value.intValue() : fallback;
    }

    private static boolean booleanValue(JsonNode arguments, String name, boolean fallback) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String limit(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum) + "...";
    }

    @FunctionalInterface
    private interface BrowserHandler {
        JsonNode execute(ToolExecutionContext context, JsonNode arguments) throws Exception;
    }

    @FunctionalInterface
    private interface JsonOperation {
        JsonNode execute() throws Exception;
    }

    private static final class SessionPages {
        private final BrowserContext context;
        private final Map<String, Tab> tabs = new LinkedHashMap<>();
        private String activeTab;
        private volatile boolean valid = true;

        private SessionPages(BrowserContext context) {
            this.context = context;
        }
    }

    private static final class Tab {
        private final SessionPages session;
        private final String id;
        private final Page page;
        private final Map<String, ElementRef> refs = new LinkedHashMap<>();
        private final Deque<ConsoleEntry> console = new ArrayDeque<>();
        private boolean consoleOverflow;

        private Tab(SessionPages session, String id, Page page) {
            this.session = session;
            this.id = id;
            this.page = page;
        }
    }

    private record ElementRef(String selector, long generation) {
    }

    private record ConsoleEntry(String type, String text) {
    }
}
