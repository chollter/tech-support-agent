package com.gcll.ticketagent.execution.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.LlmCallResult;
import com.gcll.ticketagent.llm.LlmGateway;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.tool.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Primary
public class SpringAiToolSelector implements ToolSelector {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "searchSimilarCases", "query_logs", "query_metric"
    );

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final RuleBasedToolSelector fallback;

    public SpringAiToolSelector(
            ObjectProvider<LlmGateway> llmGatewayProvider,
            StructuredOutputParser parser,
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry,
            RuleBasedToolSelector fallback
    ) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.fallback = fallback;
    }

    @Override
    public StepOutcome<ToolSelection> select(String userContent, TicketExtractResult extract, AgentPlan plan) {
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return fallback.select(userContent, extract, plan);
        }
        try {
            String input = buildInput(userContent, extract, plan);
            LlmCallResult result = llmGateway.call("tool-select.txt", input);
            SelectJson json = parser.parse(result.content(), SelectJson.class);
            LinkedHashSet<String> selected = new LinkedHashSet<>();
            if (json.selectedToolNames() != null) {
                for (String toolName : json.selectedToolNames()) {
                    if (ALLOWED_TOOLS.contains(toolName) && toolRegistry.find(toolName).isPresent()) {
                        selected.add(toolName);
                    }
                }
            }
            if (selected.isEmpty()) {
                return fallback.select(userContent, extract, plan);
            }
            Map<String, String> parameters = json.parameters() == null ? Map.of() : json.parameters();
            return StepOutcome.llm(new ToolSelection(
                    List.copyOf(selected),
                    parameters,
                    json.reason() == null ? "LLM tool selection" : json.reason(),
                    true
            ), result.latencyMs());
        } catch (Exception ex) {
            return fallback.select(userContent, extract, plan);
        }
    }

    private String buildInput(String userContent, TicketExtractResult extract, AgentPlan plan) throws Exception {
        return """
                AgentPlan：
                %s

                可用工具白名单：
                %s

                抽取结果 JSON：
                %s

                用户原文：
                %s
                """.formatted(
                plan.auditSummary(),
                toolRegistry.descriptors(),
                objectMapper.writeValueAsString(extract),
                userContent
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SelectJson(List<String> selectedToolNames, Map<String, String> parameters, String reason) {
    }
}
