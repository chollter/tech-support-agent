package com.gcll.ticketagent.understanding.completeness;

import java.util.List;

public record SchemaCompletenessResult(
        boolean complete,
        List<String> missingFields
) {
}
