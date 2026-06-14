package com.gcll.ticketagent.extract;

import java.util.List;

public record TicketExtractResult(
        IssueType issueType,
        String affectedSystem,
        String affectedModule,
        String apiName,
        String errorCode,
        String errorMessage,
        String environment,
        String impactScope,
        String timeRange,
        String businessImpact,
        List<String> severitySignals,
        double confidence
) {
}
