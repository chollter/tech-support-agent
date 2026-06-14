package com.gcll.ticketagent.eval;

import java.util.List;

public record EvalReport(
        int total,
        int passed,
        int failed,
        List<String> failures
) {
}
