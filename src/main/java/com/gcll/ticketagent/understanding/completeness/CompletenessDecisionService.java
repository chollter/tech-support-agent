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

    public CompletenessDecision decide(String userContent, TicketExtractResult extract, InfoGapAnalysis gap, String runId) {
        SchemaCompletenessResult schema = schemaCompletenessChecker.check(extract);
        // 始终用可变 ArrayList：后续要把 gap.schemaMissing 合并进来（add），
        // 用 List.of()（不可变）会在 add 时抛 UnsupportedOperationException。
        // 这对 schema.complete()=true 的场景（如 CONSULT 类型）尤其关键。
        List<String> missingSchema = new ArrayList<>(schema.missingFields());

        List<String> schemaMissingFromGap = gap.schemaMissing() == null ? List.of() : gap.schemaMissing();
        for (String field : schemaMissingFromGap) {
            if (!missingSchema.contains(field)) {
                missingSchema.add(field);
            }
        }

        // 决策：以 LLM gap 判定的 readyForAnalysis 为主导。
        // gap.readyForAnalysis=true 时不阻塞进入分析（schema 缺失只作为补充信息记录，不阻断 RAG/取证/根因流程）；
        // 仅当 gap 明确判定不能分析时才走追问。这样避免 extract 漏抽某个字段就被规则 checker 卡死，
        // 同时保留 gap 对"严重信息不足"的拦截能力。
        boolean needFollowUp = !gap.readyForAnalysis();
        boolean canProceed = !needFollowUp;

        List<String> semanticGaps = gap.semanticGaps() == null ? List.of() : gap.semanticGaps();
        List<String> questions = needFollowUp
                ? followUpQuestionService.generate(
                userContent,
                extract,
                missingSchema,
                semanticGaps,
                gap.suggestedQuestions(),
                runId
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
