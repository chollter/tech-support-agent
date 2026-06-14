package com.gcll.ticketagent.suggestion;

import com.gcll.ticketagent.analysis.RootCauseResult;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import com.gcll.ticketagent.tool.ToolResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
public class SpringAiSuggestionGenerationService implements SuggestionGenerationService {

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;
    private final TemplateSuggestionGenerationService fallback;

    public SpringAiSuggestionGenerationService(
            LlmCallExecutor llmCallExecutor,
            StructuredOutputParser parser,
            TemplateSuggestionGenerationService fallback
    ) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
        this.fallback = fallback;
    }

    @Override
    public StepOutcome<TicketSuggestion> generate(TicketExtractResult extract, List<KnowledgeHit> hits,
                                                  List<ToolResult> toolResults, RootCauseResult rootCause) {
        try {
            String context = buildContext(extract, hits, toolResults, rootCause);
            CallResult<LlmResponse> result = llmCallExecutor.execute(
                    "llm.suggestion", "suggestion-generate.txt", context);
            if (!result.success()) {
                return fallback.generate(extract, hits, toolResults, rootCause);
            }
            SuggestionJson json = parser.parse(result.value().content(), SuggestionJson.class);
            TicketSuggestion suggestion = new TicketSuggestion(
                    json.summary(),
                    nullToEmpty(json.possibleCauses()),
                    nullToEmpty(json.actions()),
                    nullToEmpty(json.runbookSteps()),
                    json.additionalInfoNeeded() == null ? List.of() : json.additionalInfoNeeded(),
                    json.risks() == null ? List.of() : json.risks(),
                    json.sources() == null ? List.of() : json.sources()
            );
            return StepOutcome.llm(suggestion, result.durationMs());
        } catch (Exception ex) {
            return fallback.generate(extract, hits, toolResults, rootCause);
        }
    }

    private String buildContext(TicketExtractResult extract, List<KnowledgeHit> hits,
                                List<ToolResult> toolResults, RootCauseResult rootCause) {
        StringBuilder sb = new StringBuilder();
        sb.append("工单：").append(extract).append("\n");

        if (hits != null && !hits.isEmpty()) {
            sb.append("知识库：\n");
            for (KnowledgeHit hit : hits) {
                sb.append(hit.sourceType()).append(":").append(hit.sourceId())
                        .append(" ").append(hit.resolution()).append("\n");
            }
        }

        if (toolResults != null && !toolResults.isEmpty()) {
            sb.append("Tool 取证结果：\n");
            for (ToolResult tr : toolResults) {
                sb.append("[").append(tr.toolName()).append("] ").append(tr.output()).append("\n");
            }
        }

        if (rootCause != null) {
            sb.append("根因分析：").append(rootCause.hypothesis())
                    .append(" (置信度: ").append(rootCause.confidence()).append(")\n");
        }

        return sb.toString();
    }

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SuggestionJson(
            String summary,
            List<String> possibleCauses,
            List<String> actions,
            List<String> runbookSteps,
            List<String> additionalInfoNeeded,
            List<String> risks,
            List<String> sources
    ) {
    }
}
