package com.gcll.ticketagent.api.dto;

public record TicketSummaryDto(
        String summary,
        String issueType,
        String priority,
        String affectedSystem,
        String affectedModule,
        String impactScope
) {
}
