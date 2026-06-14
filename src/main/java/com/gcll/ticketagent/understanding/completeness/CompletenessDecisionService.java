package com.gcll.ticketagent.understanding.completeness;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.understanding.followup.FollowUpQuestionService;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CompletenessDecisionService {

    private final SchemaCompletenessChecker schemaCompletenessChecker;
    private final FollowUpQuestionService followUpQuestionService;

    public CompletenessDecisionService(
            SchemaCompletenessChecker schemaCompletenessChecker,
            FollowUpQuestionService followUpQuestionService
    ) {
        this.schemaCompletenessChecker = schemaCompletenessChecker;
        this.followUpQuestionService = followUpQuestionService;
    }

    public CompletenessDecision decide(String userContent, TicketExtractResult extract, InfoGapAnalysis gap) {
        SchemaCompletenessResult schema = schemaCompletenessChecker.check(extract);
        List<String> missingSchema = schema.complete() ? List.of() : new ArrayList<>(schema.missingFields());

        List<String> schemaMissingFromGap = gap.schemaMissing() == null ? List.of() : gap.schemaMissing();
        for (String field : schemaMissingFromGap) {
            if (!missingSchema.contains(field)) {
                missingSchema.add(field);
            }
        }

        boolean needFollowUp = !schema.complete() || !gap.readyForAnalysis();
        boolean canProceed = !needFollowUp;

        List<String> semanticGaps = gap.semanticGaps() == null ? List.of() : gap.semanticGaps();
        List<String> questions = needFollowUp
                ? followUpQuestionService.generate(
                userContent,
                extract,
                missingSchema,
                semanticGaps,
                gap.suggestedQuestions()
        )
                : List.of();

        if (needFollowUp && questions.isEmpty()) {
            questions = List.of("请补充系统、环境、接口、错误信息和影响范围，方便准确分派。");
        }

        String reason = needFollowUp
                ? (gap.blockingReason() == null ? "信息不足" : gap.blockingReason())
                : "信息足够，进入分析";

        return new CompletenessDecision(
                canProceed,
                needFollowUp,
                List.copyOf(missingSchema),
                List.copyOf(semanticGaps),
                questions,
                reason
        );
    }
}
