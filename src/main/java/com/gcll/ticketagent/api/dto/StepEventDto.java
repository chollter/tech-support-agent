package com.gcll.ticketagent.api.dto;

public record StepEventDto(
        String runId,
        String stepName,
        String status,
        String message,
        long timestamp
) {
}
