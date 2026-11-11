package io.taiyiflow.sdk.starter;

import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.worker.ApprovalChannel;
import io.taiyiflow.sdk.worker.WorkerRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "taiyiflow.worker")
public class DigitalWorkerProperties {
    private boolean enabled;
    private boolean autoStart = true;
    private String workerId;
    private String displayName;
    private String workspace = "/var/lib/taiyiflow/worker";
    private WorkerRole role = WorkerRole.COLLABORATION_WORKER;
    private List<String> abilities = List.of();
    private String clientId;
    private String deviceId;
    private int concurrency = 3;
    private Duration connectTimeout = Duration.ofSeconds(15);
    private Duration drainTimeout = Duration.ofSeconds(30);
    private RecoveryPolicy recoveryPolicy = RecoveryPolicy.AUTO;
    private PermissionPolicy permissionPolicy = PermissionPolicy.CONFIRM;
    private ApprovalChannel approvalChannel = ApprovalChannel.PLATFORM;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean autoStart) { this.autoStart = autoStart; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }
    public WorkerRole getRole() { return role; }
    public void setRole(WorkerRole role) { this.role = role; }
    public List<String> getAbilities() { return abilities; }
    public void setAbilities(List<String> abilities) { this.abilities = abilities; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getDrainTimeout() { return drainTimeout; }
    public void setDrainTimeout(Duration drainTimeout) { this.drainTimeout = drainTimeout; }
    public RecoveryPolicy getRecoveryPolicy() { return recoveryPolicy; }
    public void setRecoveryPolicy(RecoveryPolicy recoveryPolicy) { this.recoveryPolicy = recoveryPolicy; }
    public PermissionPolicy getPermissionPolicy() { return permissionPolicy; }
    public void setPermissionPolicy(PermissionPolicy permissionPolicy) { this.permissionPolicy = permissionPolicy; }
    public ApprovalChannel getApprovalChannel() { return approvalChannel; }
    public void setApprovalChannel(ApprovalChannel approvalChannel) { this.approvalChannel = approvalChannel; }
}
