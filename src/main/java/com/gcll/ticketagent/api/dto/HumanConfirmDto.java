package com.gcll.ticketagent.api.dto;

public record HumanConfirmDto(
        boolean required,
        String reason
) {
}
