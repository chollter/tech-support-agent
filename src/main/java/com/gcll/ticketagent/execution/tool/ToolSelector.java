package com.gcll.ticketagent.execution.tool;

import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StepOutcome;

public interface ToolSelector {

    StepOutcome<ToolSelection> select(String userContent, TicketExtractResult extract, AgentPlan plan);
}
