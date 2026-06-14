package com.gcll.ticketagent.understanding.gap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.LlmCallResult;
import com.gcll.ticketagent.llm.LlmGateway;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
public class SpringAiInfoGapAnalysisService implements InfoGapAnalysisService {

    static final String SCHEMA_POLICY = """
            INCIDENT 类型工单建议具备：systemOrModule、environment、apiOrFeature、errorDetail、timeRange、impactScope。
            除上述字段外，还应识别语义层面缺口（如偶发/必现、批处理 Job 名、结算窗口影响、读写接口类型等）。
            """;

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;
    private final RuleBasedInfoGapAnalysisService fallback;

    public SpringAiInfoGapAnalysisService(
            ObjectProvider<LlmGateway> llmGatewayProvider,
            StructuredOutputParser parser,
            ObjectMapper objectMapper,
            RuleBasedInfoGapAnalysisService fallback
    ) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    @Override
    public StepOutcome<InfoGapAnalysis> analyze(String userContent, TicketExtractResult extract) {
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return fallback.analyze(userContent, extract);
        }
        try {
            String input = buildInput(userContent, extract);
            LlmCallResult result = llmGateway.call("info-gap-analysis.txt", input);
            GapJson json = parser.parse(result.content(), GapJson.class);
            InfoGapAnalysis analysis = new InfoGapAnalysis(
                    json.schemaMissing() == null ? List.of() : json.schemaMissing(),
                    json.semanticGaps() == null ? List.of() : json.semanticGaps(),
                    json.suggestedQuestions() == null ? List.of() : json.suggestedQuestions(),
                    json.blockingReason() == null ? "LLM gap analysis" : json.blockingReason(),
                    json.readyForAnalysis(),
                    json.confidence(),
                    true
            );
            return StepOutcome.llm(analysis, result.latencyMs());
        } catch (Exception ex) {
            return fallback.analyze(userContent, extract);
        }
    }

    private String buildInput(String userContent, TicketExtractResult extract) throws JsonProcessingException {
        return """
                抽取结果 JSON：
                %s

                必填字段策略：
                %s

                用户原文：
                %s
                """.formatted(objectMapper.writeValueAsString(extract), SCHEMA_POLICY, userContent);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GapJson(
            List<String> schemaMissing,
            List<String> semanticGaps,
            List<String> suggestedQuestions,
            String blockingReason,
            boolean readyForAnalysis,
            double confidence
    ) {
    }
}
