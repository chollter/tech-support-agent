package com.gcll.ticketagent.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SubmitAgentRunRequest(
        @NotBlank String sessionId,
        @NotBlank String userId,
        String title,
        @NotBlank String content,
        String source,
        Map<String, String> metadata,
        String requestId
) {
}
