package com.gcll.ticketagent.eval;

import java.util.List;

public record EvalReport(
        String suiteName,
        int total,
        int passed,
        int failed,
        java.util.List<EvalGroupReport> groups,
        List<String> failures,
        List<EvalCaseResult> caseResults,
        EvalQualitySummary qualitySummary
) {
}
