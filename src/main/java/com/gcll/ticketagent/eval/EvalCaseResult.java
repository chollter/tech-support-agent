package com.gcll.ticketagent.eval;

import java.util.List;

public record EvalCaseResult(
        String caseId,
        String scenarioType,
        boolean passed,
        List<EvalAssertionResult> assertions
) {
}
