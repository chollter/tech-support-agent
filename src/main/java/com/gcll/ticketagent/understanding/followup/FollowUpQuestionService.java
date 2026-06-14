package com.gcll.ticketagent.understanding.followup;

import com.gcll.ticketagent.extract.TicketExtractResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class FollowUpQuestionService {

    private static final int MAX_QUESTIONS = 6;

    private final TemplateFollowUpProvider templateFollowUpProvider;
    private final LlmFollowUpProvider llmFollowUpProvider;

    public FollowUpQuestionService(
            TemplateFollowUpProvider templateFollowUpProvider,
            LlmFollowUpProvider llmFollowUpProvider
    ) {
        this.templateFollowUpProvider = templateFollowUpProvider;
        this.llmFollowUpProvider = llmFollowUpProvider;
    }

    public List<String> generate(
            String userContent,
            TicketExtractResult extract,
            List<String> missingSchemaFields,
            List<String> semanticGaps,
            List<String> gapSuggestedQuestions
    ) {
        List<String> templateQuestions = templateFollowUpProvider.questionsForMissingFields(missingSchemaFields);
        List<String> llmQuestions = llmFollowUpProvider.generate(
                userContent,
                extract,
                templateQuestions,
                semanticGaps,
                gapSuggestedQuestions
        );

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addAll(merged, gapSuggestedQuestions);
        addAll(merged, llmQuestions);
        addAll(merged, templateQuestions);

        List<String> result = new ArrayList<>();
        for (String question : merged) {
            if (question == null || question.isBlank()) {
                continue;
            }
            result.add(question.trim());
            if (result.size() >= MAX_QUESTIONS) {
                break;
            }
        }
        return result;
    }

    private void addAll(LinkedHashSet<String> target, List<String> questions) {
        if (questions == null) {
            return;
        }
        for (String question : questions) {
            if (question == null || question.isBlank()) {
                continue;
            }
            String normalized = question.trim();
            if (target.stream().noneMatch(existing -> existing.equalsIgnoreCase(normalized))) {
                target.add(normalized);
            }
        }
    }
}
