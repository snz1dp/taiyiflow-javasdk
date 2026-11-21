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
    private String stateDir;
    private WorkerRole role = WorkerRole.COLLABORATION_WORKER;
    private List<String> abilities = List.of();
    private String clientId;
    private String deviceId;
    private int concurrency = 3;
    private Duration connectTimeout = Duration.ofSeconds(15);
    private Duration drainTimeout = Duration.ofSeconds(30);
    private Duration streamIdleTimeout = Duration.ofSeconds(1200);
    private Duration toolExecutionTimeout;
    private Integer maxSessionConcurrency;
    private boolean builtinTools;
    private List<String> allowedShellCommands = List.of();
    private Duration shellCommandTimeout;
    private Integer subtaskMaxTasks;
    private Integer subtaskMaxParallelism;
    private Duration subtaskTimeout;
    private RecoveryPolicy recoveryPolicy = RecoveryPolicy.AUTO;
    private PermissionPolicy permissionPolicy = PermissionPolicy.CONFIRM;
    private ApprovalChannel approvalChannel = ApprovalChannel.PLATFORM;
    private TaiyiFlowProperties.Request request = new TaiyiFlowProperties.Request();
    private TaiyiFlowProperties.Instructions instructions = new TaiyiFlowProperties.Instructions();
    private TaiyiFlowProperties.Permission permission = new TaiyiFlowProperties.Permission();

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
    public String getStateDir() { return stateDir; }
    public void setStateDir(String stateDir) { this.stateDir = stateDir; }
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
    public Duration getStreamIdleTimeout() { return streamIdleTimeout; }
    public void setStreamIdleTimeout(Duration streamIdleTimeout) { this.streamIdleTimeout = streamIdleTimeout; }
    public Duration getToolExecutionTimeout() { return toolExecutionTimeout; }
    public void setToolExecutionTimeout(Duration toolExecutionTimeout) { this.toolExecutionTimeout = toolExecutionTimeout; }
    public Integer getMaxSessionConcurrency() { return maxSessionConcurrency; }
    public void setMaxSessionConcurrency(Integer maxSessionConcurrency) { this.maxSessionConcurrency = maxSessionConcurrency; }
    public boolean isBuiltinTools() { return builtinTools; }
    public void setBuiltinTools(boolean builtinTools) { this.builtinTools = builtinTools; }
    public List<String> getAllowedShellCommands() { return allowedShellCommands; }
    public void setAllowedShellCommands(List<String> allowedShellCommands) { this.allowedShellCommands = allowedShellCommands; }
    public Duration getShellCommandTimeout() { return shellCommandTimeout; }
    public void setShellCommandTimeout(Duration shellCommandTimeout) { this.shellCommandTimeout = shellCommandTimeout; }
    public Integer getSubtaskMaxTasks() { return subtaskMaxTasks; }
    public void setSubtaskMaxTasks(Integer subtaskMaxTasks) { this.subtaskMaxTasks = subtaskMaxTasks; }
    public Integer getSubtaskMaxParallelism() { return subtaskMaxParallelism; }
    public void setSubtaskMaxParallelism(Integer subtaskMaxParallelism) { this.subtaskMaxParallelism = subtaskMaxParallelism; }
    public Duration getSubtaskTimeout() { return subtaskTimeout; }
    public void setSubtaskTimeout(Duration subtaskTimeout) { this.subtaskTimeout = subtaskTimeout; }
    public RecoveryPolicy getRecoveryPolicy() { return recoveryPolicy; }
    public void setRecoveryPolicy(RecoveryPolicy recoveryPolicy) { this.recoveryPolicy = recoveryPolicy; }
    public PermissionPolicy getPermissionPolicy() { return permissionPolicy; }
    public void setPermissionPolicy(PermissionPolicy permissionPolicy) { this.permissionPolicy = permissionPolicy; }
    public ApprovalChannel getApprovalChannel() { return approvalChannel; }
    public void setApprovalChannel(ApprovalChannel approvalChannel) { this.approvalChannel = approvalChannel; }
    public TaiyiFlowProperties.Request getRequest() { return request; }
    public void setRequest(TaiyiFlowProperties.Request request) { this.request = request; }
    public TaiyiFlowProperties.Instructions getInstructions() { return instructions; }
    public void setInstructions(TaiyiFlowProperties.Instructions instructions) { this.instructions = instructions; }
    public TaiyiFlowProperties.Permission getPermission() { return permission; }
    public void setPermission(TaiyiFlowProperties.Permission permission) { this.permission = permission; }
}
