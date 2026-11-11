package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.collaboration.CollaborationPlugin;

public enum WorkerRole {
    COLLABORATION_WORKER(CollaborationPlugin.ROLE_COLLABORATION_WORKER),
    PRIMARY_CLI(CollaborationPlugin.ROLE_PRIMARY_CLI);

    private final String protocolName;

    WorkerRole(String protocolName) {
        this.protocolName = protocolName;
    }

    public String protocolName() {
        return protocolName;
    }
}
