package com.gcll.ticketagent.eval;

public record EvalAssertionResult(
        String name,
        boolean passed,
        String expected,
        String actual,
        String message
) {
}
