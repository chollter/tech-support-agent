package com.gcll.ticketagent.understanding.completeness;

import java.util.List;

public record CompletenessDecision(
        boolean canProceed,
        boolean needFollowUp,
        List<String> missingSchemaFields,
        List<String> semanticGaps,
        List<String> followUpQuestions,
        String decisionReason
) {
}
