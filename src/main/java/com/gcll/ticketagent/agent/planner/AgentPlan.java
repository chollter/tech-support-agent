package com.gcll.ticketagent.agent.planner;

import java.util.List;

public record AgentPlan(
        List<AgentAction> actions,
        List<AgentAction> skipped,
        String reason,
        boolean llmUsed
) {
    public boolean includes(AgentAction action) {
        return actions != null && actions.contains(action);
    }

    public String auditSummary() {
        return "actions=" + actions + ",skipped=" + skipped + ",reason=" + reason;
    }
}
