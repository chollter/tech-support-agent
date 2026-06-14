package com.gcll.ticketagent.api.dto;

import java.util.List;

public record SuggestionDto(
        List<String> possibleCauses,
        List<String> actions,
        List<String> runbookSteps,
        List<String> sources
) {
}
