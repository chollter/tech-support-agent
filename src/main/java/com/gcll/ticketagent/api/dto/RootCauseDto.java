package com.gcll.ticketagent.api.dto;

import java.util.List;

public record RootCauseDto(
        String hypothesis,
        List<String> evidence,
        List<String> unknowns,
        double confidence
) {
}
