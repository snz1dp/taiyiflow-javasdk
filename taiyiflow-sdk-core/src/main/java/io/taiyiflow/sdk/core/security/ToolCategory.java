package io.taiyiflow.sdk.core.security;

/** Tool categories that can carry an independent permission policy. */
public enum ToolCategory {
    SHELL, FILE, BROWSER, SKILL, SCHEDULE;

    private static final String BROWSER_PREFIX = "taiyiflow-in-chrome__";

    /** Parses a configuration key (shell/file/browser/skill/schedule); unknown keys return null. */
    public static ToolCategory fromKey(String key) {
        if (key == null) {
            return null;
        }
        try {
            return valueOf(key.toUpperCase(java.util.Locale.ROOT).trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static ToolCategory of(String toolName) {
        if (toolName == null) {
            return null;
        }
        if (toolName.startsWith(BROWSER_PREFIX)) {
            return BROWSER;
        }
        if (toolName.startsWith("skill_")) {
            return SKILL;
        }
        if (toolName.startsWith("schedule_")) {
            return SCHEDULE;
        }
        if (toolName.startsWith("process_")
                || toolName.equals("shell") || toolName.equals("bash")
                || toolName.equals("execute_command") || toolName.equals("shell_exec")
                || toolName.equals("task_stop") || toolName.equals("task_delete")
                || toolName.equals("task_cleanup")) {
            return SHELL;
        }
        if (toolName.equals("file_edit") || toolName.equals("write_file")
                || toolName.equals("document_unstructured") || toolName.equals("read_file")
                || toolName.equals("glob_files") || toolName.equals("search_files")) {
            return FILE;
        }
        return null;
    }
}