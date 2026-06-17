package com.gcll.ticketagent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AgentRunContextPersister {

    private static final Logger log = LoggerFactory.getLogger(AgentRunContextPersister.class);

    private final ObjectMapper objectMapper;

    public AgentRunContextPersister(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void persist(AgentRun run, InfoGapAnalysis gap, AgentPlan plan, ToolSelection selection) {
        try {
            if (gap != null) {
                run.setGapAnalysisJson(objectMapper.writeValueAsString(gap));
            }
            if (plan != null) {
                run.setAgentPlanJson(objectMapper.writeValueAsString(plan));
            }
            if (selection != null) {
                run.setToolSelectionJson(objectMapper.writeValueAsString(selection));
            }
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize agent context, runId={}, error={}", run.getId(), ex.getMessage());
        }
    }
}
