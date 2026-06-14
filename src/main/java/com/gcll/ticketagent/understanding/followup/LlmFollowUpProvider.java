package com.gcll.ticketagent.understanding.followup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.LlmCallResult;
import com.gcll.ticketagent.llm.LlmGateway;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class LlmFollowUpProvider {

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;

    public LlmFollowUpProvider(
            ObjectProvider<LlmGateway> llmGatewayProvider,
            StructuredOutputParser parser,
            ObjectMapper objectMapper
    ) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    public List<String> generate(
            String userContent,
            TicketExtractResult extract,
            List<String> templateQuestions,
            List<String> semanticGaps,
            List<String> gapSuggestedQuestions
    ) {
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return List.of();
        }
        try {
            String input = buildInput(userContent, extract, templateQuestions, semanticGaps, gapSuggestedQuestions);
            LlmCallResult result = llmGateway.call("follow-up-generate.txt", input);
            FollowUpJson json = parser.parse(result.content(), FollowUpJson.class);
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
