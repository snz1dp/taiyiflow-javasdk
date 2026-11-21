package io.taiyiflow.sdk.core.security;

/** Browser tool permission mode, independent of the global policy (browser.permission_mode). */
public enum BrowserPermissionMode {
    ASK, FOLLOW_A_PLAN, SKIP_ALL_PERMISSION_CHECKS;

    /** Parses a configuration value; unknown or missing values fall back to the stricter ASK. */
    public static BrowserPermissionMode of(String value) {
        String normalized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).trim();
        return switch (normalized) {
            case "follow_a_plan" -> FOLLOW_A_PLAN;
            case "skip_all_permission_checks" -> SKIP_ALL_PERMISSION_CHECKS;
            default -> ASK;
        };
    }
}