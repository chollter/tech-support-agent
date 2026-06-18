package com.gcll.ticketagent.eval;

import com.gcll.ticketagent.eval.judge.EvalQualityScore;

import java.util.List;

/**
 * LLM-as-judge 质量评测汇总。
 * <p>与流程断言（passed/failed）独立：流程正确性由结构断言决定，质量分由裁判 LLM 决定。
 * judge 不可用时 {@link #skipped()}=true，各分数字段为 0/空。
 */
public record EvalQualitySummary(
        boolean skipped,
        double averageScore,
        int scoredCount,
        List<CaseScore> scores
) {

    /** 单个 case 的质量分（judge 失败的 case 记 score=null，不计入平均分）。 */
    public record CaseScore(String caseId, EvalQualityScore score) {
    }
}
