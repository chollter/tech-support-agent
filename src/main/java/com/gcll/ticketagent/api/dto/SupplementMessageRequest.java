package com.gcll.ticketagent.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SupplementMessageRequest(
        @NotBlank String content,
        Map<String, String> metadata
) {
}
