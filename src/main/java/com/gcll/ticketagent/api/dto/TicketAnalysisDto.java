package com.gcll.ticketagent.api.dto;

public record TicketAnalysisDto(
        TicketSummaryDto ticket,
        RoutingDto routing,
        RootCauseDto rootCause,
        SuggestionDto suggestion,
        HumanConfirmDto humanConfirm
) {
}
