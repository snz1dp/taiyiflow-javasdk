package io.taiyiflow.sdk.transport.envelope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseExpiredExceptionTest {

    @Test
    void recognizesLicenseCodesOnly() {
        assertTrue(LicenseExpiredException.isLicenseCode("license_expired"));
        assertTrue(LicenseExpiredException.isLicenseCode("license_terminal_limit"));
        assertFalse(LicenseExpiredException.isLicenseCode("quota_session_exceeded"));
        assertFalse(LicenseExpiredException.isLicenseCode("session_conflict"));
        assertFalse(LicenseExpiredException.isLicenseCode(null));
    }

    @Test
    void recognizesBackendDetailConstantsOnly() {
        assertTrue(LicenseExpiredException.isLicenseDetail(LicenseExpiredException.EXPIRED_DETAIL));
        assertTrue(LicenseExpiredException.isLicenseDetail(LicenseExpiredException.NOT_STARTED_DETAIL));
        assertFalse(LicenseExpiredException.isLicenseDetail("无权访问此智能服务应用"));
        assertFalse(LicenseExpiredException.isLicenseDetail(null));
    }

    @Test
    void extractsLicenseFromCloseReason() {
        String reason = CloseCodes.describe(CloseCodes.PERMISSION_DENIED) + ": " + LicenseExpiredException.EXPIRED_DETAIL;
        LicenseExpiredException license = LicenseExpiredException.fromCloseReason(reason);
        assertEquals(LicenseExpiredException.ERROR_CODE, license.getCode());
        assertEquals(LicenseExpiredException.EXPIRED_DETAIL, license.getMessage());

        assertEquals(LicenseExpiredException.NOT_STARTED_DETAIL,
                LicenseExpiredException.fromCloseReason("Permission, quota, or model key denied: "
                        + LicenseExpiredException.NOT_STARTED_DETAIL).getMessage());
        assertNull(LicenseExpiredException.fromCloseReason("Permission, quota, or model key denied: 配额不足"));
        assertNull(LicenseExpiredException.fromCloseReason(null));
    }
}