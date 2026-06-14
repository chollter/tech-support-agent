package com.gcll.ticketagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.llm.LlmCallResult;
import com.gcll.ticketagent.llm.LlmGateway;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
public class LlmRootCauseAnalysisService implements RootCauseAnalysisService {

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final StructuredOutputParser parser;
    private final RuleBasedRootCauseAnalysisService fallback;

    public LlmRootCauseAnalysisService(
            ObjectProvider<LlmGateway> llmGatewayProvider,
            StructuredOutputParser parser,
            RuleBasedRootCauseAnalysisService fallback
    ) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.parser = parser;
        this.fallback = fallback;
    }

    @Override
    public RootCauseResult analyze(TicketExtractResult extract, List<KnowledgeHit> hits, List<ToolResult> toolResults) {
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return fallback.analyze(extract, hits, toolResults);
        }
        try {
            String context = buildContext(extract, hits, toolResults);
            LlmCallResult result = llmGateway.call("root-cause-analysis.txt", context);
            RootCauseJson json = parser.parse(result.content(), RootCauseJson.class);
            return new RootCauseResult(
                    blankToDefault(json.hypothesis(), "根因待确认，需进一步排查"),
                    nullToEmpty(json.evidence()),
                    nullToEmpty(json.unknowns()),
                    clampConfidence(json.confidence()),
                    true
            );
        } catch (Exception ex) {
            return fallback.analyze(extract, hits, toolResults);
        }
    }

    private String buildContext(TicketExtractResult extract, List<KnowledgeHit> hits, List<ToolResult> toolResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("工单结构化信息:\n").append(extract).append("\n\n");

        sb.append("历史案例:\n");
        if (hits == null || hits.isEmpty()) {
            sb.append("无命中\n");
        } else {
            for (KnowledgeHit hit : hits) {
                sb.append("- [").append(hit.sourceType()).append(":").append(hit.sourceId()).append("] ")
                        .append("summary=").append(hit.summary())
                        .append(", resolution=").append(hit.resolution())
                        .append(", score=").append(hit.score())
                        .append("\n");
            }
        }

        sb.append("\nTool 取证结果:\n");
        if (toolResults == null || toolResults.isEmpty()) {
            sb.append("无工具取证结果\n");
        } else {
            for (ToolResult toolResult : toolResults) {
                sb.append("- tool=").append(toolResult.toolName())
                        .append(", type=").append(toolResult.toolType())
                        .append(", success=").append(toolResult.success())
                        .append(", input=").append(toolResult.input())
                        .append(", output=").append(toolResult.output())
                        .append(", error=").append(toolResult.errorMessage())
                        .append("\n");
            }
        }
        return sb.toString();
    }

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private double clampConfidence(double confidence) {
        if (confidence < 0) {
            return 0;
        }
        if (confidence > 1) {
            return 1;
        }
        return confidence;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RootCauseJson(
            String hypothesis,
            List<String> evidence,
            List<String> unknowns,
            double confidence
    ) {
    }
}
