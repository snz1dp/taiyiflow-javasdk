package io.taiyiflow.sdk.core.security;

/** Write-confirmation policy for file_edit/write_file, mirroring write_confirmation_policy. */
public enum WriteConfirmation {
    CONFIRM, DIRECT;

    /** Parses a configuration value; unknown or missing values fall back to the stricter CONFIRM. */
    public static WriteConfirmation of(String value) {
        String normalized = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).trim();
        return normalized.equals("direct") ? DIRECT : CONFIRM;
    }
}