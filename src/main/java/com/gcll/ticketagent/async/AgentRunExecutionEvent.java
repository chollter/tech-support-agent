package com.gcll.ticketagent.async;

public record AgentRunExecutionEvent(
        String runId,
        String traceId,
        String reason,
        String idempotencyKey
) {
    public AgentRunExecutionEvent(String runId, String traceId, String reason) {
        this(runId, traceId, reason, null);
    }
}
