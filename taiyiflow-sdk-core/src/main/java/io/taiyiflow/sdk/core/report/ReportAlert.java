package io.taiyiflow.sdk.core.report;

/**
 * Identifier-only severe alert emitted when neither durable compensation nor a direct report
 * succeeded. Deliberately excludes payloads and exception bodies.
 */
public record ReportAlert(
        ReportOutbox.Type reportType,
        String flowId,
        String sessionId,
        String runId,
        String callId,
        String checkpointId,
        String reason) {

    public static ReportAlert directReportFailed(ReportOutbox.Entry entry) {
        return new ReportAlert(entry.getType(), entry.getFlowId(), entry.getSessionId(),
                entry.getRunId(), entry.getCallId(), entry.getCheckpointId(),
                "persistence-and-direct-report-failed");
    }

    public static ReportAlert directResumeFailed(ReportOutbox.Type type, String flowId,
                                                  String sessionId, String runId,
                                                  String checkpointId) {
        return new ReportAlert(type, flowId, sessionId, runId, null, checkpointId,
                "persistence-and-direct-report-failed");
    }
}
