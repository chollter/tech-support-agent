package com.gcll.ticketagent.api;

public record ApiErrorResponse(
        boolean success,
        String code,
        String message,
        String traceId
) {
}
