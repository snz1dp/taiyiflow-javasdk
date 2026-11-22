package io.taiyiflow.sdk.transport.envelope;

/**
 * Terminal rejection because the platform product license expired or has not started yet.
 * Mirrors the backend license guard: run/chat entries fail with HTTP 403 (a plain Chinese
 * detail string) or a WS {@code error} event carrying {@code data.type=license_expired}
 * followed by close 4003. Not recoverable by retry; renewal is required, after which the
 * exempt {@code /config} and {@code /version} self-rescue endpoints report the restored state.
 */
public class LicenseExpiredException extends RuntimeException {

    /** Machine code on the run WS error event ({@code data.type}) for license expiry. */
    public static final String ERROR_CODE = "license_expired";

    /** Machine code for the license-derived global online-terminal limit (WS only). */
    public static final String TERMINAL_LIMIT_CODE = "license_terminal_limit";

    public static final String EXPIRED_DETAIL = "产品服务授权已到期，请联系厂商购买授权";
    public static final String NOT_STARTED_DETAIL = "产品服务授权尚未生效，请核对授权开始时间";

    private final String code;

    public LicenseExpiredException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static boolean isLicenseCode(String type) {
        return ERROR_CODE.equals(type) || TERMINAL_LIMIT_CODE.equals(type);
    }

    /** The HTTP 403 license detail is a plain string with no stable code, so match the backend constants. */
    public static boolean isLicenseDetail(String detail) {
        return EXPIRED_DETAIL.equals(detail) || NOT_STARTED_DETAIL.equals(detail);
    }

    /** Detect a license rejection from a WS close reason (the detail is appended after the code description). */
    public static LicenseExpiredException fromCloseReason(String reason) {
        if (reason == null) {
            return null;
        }
        if (reason.contains(EXPIRED_DETAIL)) {
            return new LicenseExpiredException(ERROR_CODE, EXPIRED_DETAIL);
        }
        if (reason.contains(NOT_STARTED_DETAIL)) {
            return new LicenseExpiredException(ERROR_CODE, NOT_STARTED_DETAIL);
        }
        return null;
    }
}