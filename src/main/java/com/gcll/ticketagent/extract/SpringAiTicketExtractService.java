package com.gcll.ticketagent.extract;

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
public class SpringAiTicketExtractService implements TicketExtractService {

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final StructuredOutputParser parser;
    private final RuleBasedTicketExtractService fallback;

    public SpringAiTicketExtractService(
            ObjectProvider<LlmGateway> llmGatewayProvider,
            StructuredOutputParser parser,
            RuleBasedTicketExtractService fallback
    ) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.parser = parser;
        this.fallback = fallback;
    }

    @Override
    public StepOutcome<TicketExtractResult> extract(String content) {
        StepOutcome<TicketExtractResult> ruleOutcome = fallback.extract(content);
        if (ruleOutcome.value().issueType() == IssueType.CONSULT) {
            return ruleOutcome;
        }
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return fallback.extract(content);
        }
        try {
            LlmCallResult result = llmGateway.call("ticket-extract.txt", content);
            ExtractJson json = parser.parse(result.content(), ExtractJson.class);
            TicketExtractResult extracted = new TicketExtractResult(
                    IssueType.valueOf(json.issueType()),
                    json.affectedSystem(),
                    json.affectedModule(),
                    json.apiName(),
                    json.errorCode(),
                    json.errorMessage(),
                    json.environment(),
                    json.impactScope(),
                    json.timeRange(),
                    json.businessImpact(),
                    json.severitySignals() == null ? List.of() : json.severitySignals(),
                    json.confidence()
            );
            return StepOutcome.llm(extracted, result.latencyMs());
        } catch (Exception ex) {
            return fallback.extract(content);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExtractJson(
            String issueType,
            String affectedSystem,
            String affectedModule,
            String apiName,
            String errorCode,
            String errorMessage,
            String environment,
            String impactScope,
            String timeRange,
            String businessImpact,
            List<String> severitySignals,
            double confidence
    ) {
    }
}
