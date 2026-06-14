package com.gcll.ticketagent.agent.planner;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StepOutcome;

public interface AgentPlanner {

    StepOutcome<AgentPlan> plan(String userContent, TicketExtractResult extract);
}
