package com.gcll.ticketagent.api.dto;

public record PendingActionResponse(
        String id,
        String runId,
        String actionType,
        String status,
        String reason
) {
}
