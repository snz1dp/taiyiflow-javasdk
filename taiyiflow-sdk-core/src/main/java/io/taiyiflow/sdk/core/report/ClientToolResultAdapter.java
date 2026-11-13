package io.taiyiflow.sdk.core.report;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Consumer;

/** Protocol-specific REST callback delivery and error classification. */
public final class ClientToolResultAdapter {
    public static final String RESULT_PATH = "/api/v1/agents/client/call/result";
    private static final Logger log = LoggerFactory.getLogger(ClientToolResultAdapter.class);

    public enum Outcome {
        REPORTED,
        PENDING,
        DEAD_LETTER
    }

    public record Result(Outcome outcome, ReportOutbox.Entry entry, Exception error) {
        public boolean terminal() {
            return outcome != Outcome.PENDING;
        }
    }

    private final RestClient restClient;
    private final ReportOutbox outbox;
    private final Consumer<ReportAlert> severeAlertHandler;
    private final int maxRetries;
    private final Duration initialRetryDelay;

    public ClientToolResultAdapter(RestClient restClient, ReportOutbox outbox,
                                   Consumer<ReportAlert> severeAlertHandler,
                                   int maxRetries, Duration initialRetryDelay) {
        this.restClient = restClient;
        this.outbox = outbox;
        this.severeAlertHandler = severeAlertHandler;
        this.maxRetries = maxRetries;
        this.initialRetryDelay = initialRetryDelay;
    }

    /** Persist first. If persistence fails, exactly one direct attempt is made. */
    public Result persistAndReport(ObjectNode payload) {
        ReportOutbox.Entry entry;
        try {
            entry = outbox.put(ReportOutbox.client(payload));
        } catch (ReportOutbox.ReportConflictException conflict) {
            ReportOutbox.Entry dead = outbox.find(ReportOutbox.idFor(ReportOutbox.Type.CLIENT_TOOL_RESULT,
                    payload.path("run_id").asText() + "\0" + payload.path("call_id").asText())).orElse(null);
            return new Result(Outcome.DEAD_LETTER, dead, conflict);
        } catch (Exception persistenceFailure) {
            ReportOutbox.Entry transientEntry = ReportOutbox.client(payload);
            Result direct = directOnce(transientEntry);
            if (direct.outcome() != Outcome.REPORTED) {
                severeAlertHandler.accept(ReportAlert.directReportFailed(transientEntry));
            }
            return direct;
        }
        if (entry.getStatus() == ReportOutbox.Status.DEAD_LETTER) {
            return new Result(Outcome.DEAD_LETTER, entry, null);
        }
        return deliver(entry, true);
    }

    /** Flush only this protocol's due records; one bad record never blocks the others. */
    public void flush() {
        flush(false);
    }

    public void flushNow() {
        flush(true);
    }

    private void flush(boolean force) {
        for (ReportOutbox.Entry entry : outbox.list(ReportOutbox.Type.CLIENT_TOOL_RESULT)) {
            if (!force && !entry.isDue()) {
                continue;
            }
            try {
                deliver(entry, true);
            } catch (RuntimeException exception) {
                log.warn("Unable to flush client result {}", entry.getId());
            }
        }
    }

    private Result deliver(ReportOutbox.Entry original, boolean sleepBetweenAttempts) {
        ReportOutbox.Entry entry = original;
        for (int attempt = 0; ; attempt++) {
            Exception failure = null;
            try {
                entry = outbox.markReporting(entry.getId());
                if (entry.getStatus() == ReportOutbox.Status.DEAD_LETTER) {
                    return new Result(Outcome.DEAD_LETTER, entry, null);
                }
                restClient.post(RESULT_PATH, entry.getPayload());
                outbox.delete(entry.getId());
                return new Result(Outcome.REPORTED, entry, null);
            } catch (Exception exception) {
                failure = exception;
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Classification classification = classify(exception);
                try {
                    if (classification == Classification.DELETE) {
                        outbox.delete(entry.getId());
                        return new Result(Outcome.REPORTED, entry, null);
                    }
                    if (classification == Classification.DEAD_LETTER) {
                        entry = outbox.markDeadLetter(entry.getId(), safeCode(exception));
                        return new Result(Outcome.DEAD_LETTER, entry, exception);
                    }
                    entry = outbox.markRetry(entry.getId(), safeCode(exception), backoff(entry));
                    if (entry.getStatus() == ReportOutbox.Status.DEAD_LETTER) {
                        return new Result(Outcome.DEAD_LETTER, entry, exception);
                    }
                } catch (Exception storageFailure) {
                    return new Result(Outcome.PENDING, entry, storageFailure);
                }
            }
            if (attempt >= maxRetries || Thread.currentThread().isInterrupted()) {
                return new Result(Outcome.PENDING, entry, failure);
            }
            if (sleepBetweenAttempts) {
                try {
                    Thread.sleep(initialRetryDelay.multipliedBy(1L << Math.min(attempt, 20)));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return new Result(Outcome.PENDING, entry, exception);
                }
            }
        }
    }

    private Result directOnce(ReportOutbox.Entry transientEntry) {
        try {
            restClient.post(RESULT_PATH, transientEntry.getPayload());
            return new Result(Outcome.REPORTED, transientEntry, null);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (classify(exception) == Classification.DELETE) {
                return new Result(Outcome.REPORTED, transientEntry, null);
            }
            Outcome outcome = classify(exception) == Classification.DEAD_LETTER
                    ? Outcome.DEAD_LETTER : Outcome.PENDING;
            return new Result(outcome, transientEntry, exception);
        }
    }

    private Duration backoff(ReportOutbox.Entry entry) {
        int shift = Math.min(20, entry.getRetryCount());
        long millis;
        try {
            millis = initialRetryDelay.multipliedBy(1L << shift).toMillis();
        } catch (ArithmeticException exception) {
            millis = Duration.ofMinutes(5).toMillis();
        }
        return Duration.ofMillis(Math.min(Duration.ofMinutes(5).toMillis(), Math.max(1, millis)));
    }

    private static Classification classify(Exception exception) {
        if (!(exception instanceof RestClient.RestException rest)) {
            return Classification.RETRY;
        }
        if (rest.getStatusCode() == 400 && "tool_call_paused".equals(rest.getDetailCode())) {
            return Classification.DELETE;
        }
        int status = rest.getStatusCode();
        if (status == 401 || status == 403 || status == 408 || status == 429 || status >= 500) {
            return Classification.RETRY;
        }
        return Classification.DEAD_LETTER;
    }

    private static String safeCode(Exception exception) {
        if (exception instanceof RestClient.RestException rest) {
            return "http-" + rest.getStatusCode();
        }
        if (exception instanceof java.net.http.HttpTimeoutException
                || exception instanceof java.net.SocketTimeoutException) {
            return "timeout";
        }
        return "network-error";
    }

    private enum Classification {
        DELETE,
        RETRY,
        DEAD_LETTER
    }
}
