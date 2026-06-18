package com.gcll.ticketagent.eval.judge;

import com.gcll.ticketagent.eval.EvalCase;

/**
 * LLM-as-judge 质量评测端口。
 * <p>用裁判 LLM 评 Agent 根因分析的质量（1-5 分 + 理由），作为现有流程断言之外的独立质量维度。
 * <p>默认实现 {@link NoopEvalJudge}（available=false，跳过质量评测），
 * 启用开关时 {@link DashScopeEvalJudge} 接管。
 * <p>失败降级：{@link #score} 在裁判调用失败时返回 null（该 case 不计入平均分），不抛异常。
 */
public interface EvalJudgeService {

    /**
     * 裁判是否可用（开关开启 + LLM bean 存在）。不可用时 EvalRunner 完全跳过质量评测。
     */
    boolean available();

    /**
     * 对一个 case 的实际根因输出打分。
     *
     * @param evalCase        评测用例（含 rubric）
     * @param rootCauseText   Agent 实际产出的根因文本
     * @return 打分结果；裁判调用失败/熔断/解析失败时返回 null（降级，不计入平均分）
     */
    EvalQualityScore score(EvalCase evalCase, String rootCauseText);
}
