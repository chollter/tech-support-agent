package com.gcll.ticketagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalReportTest {

    @Test
    void reportCarriesPerCaseAssertionResults() {
        EvalAssertionResult assertion = new EvalAssertionResult(
                "replyType", false, "NEED_MORE_INFO", "TICKET_ANALYSIS_RESULT", "mismatch");
        EvalCaseResult caseResult = new EvalCaseResult(
                "insufficient-info", "core-regression", false, List.of(assertion));

        EvalReport report = new EvalReport(
                "eval/eval-cases.json",
                1,
                0,
                1,
                List.of(new EvalGroupReport("core-regression", 1, 0, 1)),
                List.of("insufficient-info failed"),
                List.of(caseResult),
                new EvalQualitySummary(true, 0.0, 0, List.of())
        );

        assertThat(report.caseResults()).singleElement().satisfies(result -> {
            assertThat(result.caseId()).isEqualTo("insufficient-info");
            assertThat(result.assertions()).singleElement()
                    .extracting(EvalAssertionResult::name)
                    .isEqualTo("replyType");
        });
    }
}
