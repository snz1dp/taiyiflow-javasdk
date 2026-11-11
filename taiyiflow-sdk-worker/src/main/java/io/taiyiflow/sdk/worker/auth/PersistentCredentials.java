package io.taiyiflow.sdk.worker.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Credentials held by a {@link CredentialStore}. */
public final class PersistentCredentials {
    private final String persistentToken;
    private final String deviceId;
    private final String accessToken;

    @JsonCreator
    public PersistentCredentials(
            @JsonProperty("persistentToken") String persistentToken,
            @JsonProperty("deviceId") String deviceId,
            @JsonProperty("accessToken") String accessToken) {
        this.persistentToken = persistentToken;
        this.deviceId = deviceId;
        this.accessToken = accessToken;
    }

    public String getPersistentToken() {
        return persistentToken;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    @Override
    public String toString() {
        return "PersistentCredentials[<redacted>]";
    }
}
