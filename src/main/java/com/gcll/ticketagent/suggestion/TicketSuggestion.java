package com.gcll.ticketagent.suggestion;

import java.util.List;

public record TicketSuggestion(
        String summary,
        List<String> possibleCauses,
        List<String> actions,
        List<String> runbookSteps,
        List<String> additionalInfoNeeded,
        List<String> risks,
        List<String> sources
) {
}
