package com.gcll.ticketagent.eval;

public record EvalGroupReport(
        String name,
        int total,
        int passed,
        int failed
) {
}
