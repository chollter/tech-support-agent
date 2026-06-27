package com.gcll.ticketagent.understanding.followup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class LlmFollowUpProvider {

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;

    public LlmFollowUpProvider(
            LlmCallExecutor llmCallExecutor,
            StructuredOutputParser parser,
            ObjectMapper objectMapper
    ) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    public List<String> generate(
            String userContent,
            TicketExtractResult extract,
            List<String> templateQuestions,
            List<String> semanticGaps,
            List<String> gapSuggestedQuestions,
            String runId
    ) {
        try {
            String input = buildInput(userContent, extract, templateQuestions, semanticGaps, gapSuggestedQuestions);
            // runId 非 null → 走记忆路径（多轮追问复用上下文）；null → 退回单轮
            CallResult<LlmResponse> result = llmCallExecutor.execute(
                    "llm.follow-up", "follow-up-generate.txt", input, runId);
            if (!result.success()) {
                return List.of();
            }
            FollowUpJson json = parser.parse(result.value().content(), FollowUpJson.class);
            return json.questions() == null ? List.of() : json.questions();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String buildInput(
            String userContent,
            TicketExtractResult extract,
            List<String> templateQuestions,
            List<String> semanticGaps,
            List<String> gapSuggestedQuestions
    ) throws Exception {
        return """
                抽取结果 JSON：
                %s

                模板追问（保底）：
                %s

                语义缺口：
                %s

                已识别建议追问：
                %s

                用户原文：
                %s
                """.formatted(
                objectMapper.writeValueAsString(extract),
                templateQuestions == null ? Collections.emptyList() : templateQuestions,
                semanticGaps == null ? Collections.emptyList() : semanticGaps,
                gapSuggestedQuestions == null ? Collections.emptyList() : gapSuggestedQuestions,
                userContent
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FollowUpJson(List<String> questions) {
    }
}
