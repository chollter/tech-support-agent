package com.gcll.ticketagent.agent.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.LlmCallResult;
import com.gcll.ticketagent.llm.LlmGateway;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Primary
public class SpringAiAgentPlanner implements AgentPlanner {

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;
    private final RuleBasedAgentPlanner fallback;

    public SpringAiAgentPlanner(
            ObjectProvider<LlmGateway> llmGatewayProvider,
            StructuredOutputParser parser,
            ObjectMapper objectMapper,
            RuleBasedAgentPlanner fallback
    ) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    @Override
    public StepOutcome<AgentPlan> plan(String userContent, TicketExtractResult extract) {
        if (extract.issueType() == IssueType.CONSULT) {
            return fallback.plan(userContent, extract);
        }
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return fallback.plan(userContent, extract);
        }
        try {
            String input = buildInput(userContent, extract);
            LlmCallResult result = llmGateway.call("agent-plan.txt", input);
            PlanJson json = parser.parse(result.content(), PlanJson.class);
            AgentPlan plan = new AgentPlan(
                    sanitizeActions(json.actions()),
                    sanitizeActions(json.skipped()),
                    json.reason() == null ? "LLM plan" : json.reason(),
                    true
            );
            if (plan.actions().isEmpty()) {
                return fallback.plan(userContent, extract);
            }
            return StepOutcome.llm(plan, result.latencyMs());
        } catch (Exception ex) {
            return fallback.plan(userContent, extract);
        }
    }

    private List<AgentAction> sanitizeActions(List<String> rawActions) {
        List<AgentAction> actions = new ArrayList<>();
        if (rawActions == null) {
            return actions;
        }
        for (String raw : rawActions) {
            AgentAction action = AgentAction.fromName(raw);
            if (action != null && !actions.contains(action)) {
                actions.add(action);
            }
        }
        return List.copyOf(actions);
    }

    private String buildInput(String userContent, TicketExtractResult extract) throws Exception {
        return """
                抽取结果 JSON：
                %s

                用户原文：
                %s
                """.formatted(objectMapper.writeValueAsString(extract), userContent);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PlanJson(List<String> actions, List<String> skipped, String reason) {
    }
}
