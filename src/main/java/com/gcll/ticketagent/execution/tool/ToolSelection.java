package com.gcll.ticketagent.execution.tool;

import java.util.List;
import java.util.Map;

public record ToolSelection(
        List<String> selectedToolNames,
        Map<String, String> parameters,
        String reason,
        boolean llmUsed
) {
    public String auditSummary() {
        return "selected=" + selectedToolNames + ",reason=" + reason;
    }
}
