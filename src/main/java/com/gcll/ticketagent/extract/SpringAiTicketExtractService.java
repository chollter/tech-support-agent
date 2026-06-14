package com.gcll.ticketagent.extract;

import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
public class SpringAiTicketExtractService implements TicketExtractService {

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;
    private final RuleBasedTicketExtractService fallback;

    public SpringAiTicketExtractService(
            LlmCallExecutor llmCallExecutor,
            StructuredOutputParser parser,
            RuleBasedTicketExtractService fallback
    ) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
        this.fallback = fallback;
    }

    @Override
    public StepOutcome<TicketExtractResult> extract(String content) {
        StepOutcome<TicketExtractResult> ruleOutcome = fallback.extract(content);
        if (ruleOutcome.value().issueType() == IssueType.CONSULT) {
            return ruleOutcome;
        }
        try {
            CallResult<LlmResponse> result = llmCallExecutor.execute(
                    "llm.ticket-extract", "ticket-extract.txt", content);
            if (!result.success()) {
                return fallback.extract(content);
            }
            ExtractJson json = parser.parse(result.value().content(), ExtractJson.class);
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
            return StepOutcome.llm(extracted, result.durationMs());
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
