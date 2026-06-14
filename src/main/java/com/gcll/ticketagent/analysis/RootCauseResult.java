package com.gcll.ticketagent.analysis;

import java.util.List;

public record RootCauseResult(
        String hypothesis,
        List<String> evidence,
        List<String> unknowns,
        double confidence,
        boolean llmUsed
) {
}
