package com.gcll.ticketagent.understanding.gap;

import java.util.List;

public record InfoGapAnalysis(
        List<String> schemaMissing,
        List<String> semanticGaps,
        List<String> suggestedQuestions,
        String blockingReason,
        boolean readyForAnalysis,
        double confidence,
        boolean llmUsed
) {
}
