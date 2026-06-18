package com.gcll.ticketagent.eval.judge;

import com.gcll.ticketagent.eval.EvalCase;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * DashScope LLM-as-judge 裁判实现。
 * <p>经 {@link LlmCallExecutor}（统一咽喉，callName {@code llm.eval-judge}，由 {@code llm.*} 前缀兜底
 * 自动治理）调用裁判 LLM，对 Agent 根因输出按 rubric 打 1-5 分。
 * <p>{@code @ConditionalOnProperty(opsmind.eval.judge.enabled=true)} 时激活并覆盖 {@link NoopEvalJudge}。
 * 失败/熔断/解析失败返回 null（该 case 不计入平均分），不抛异常。
 */
@Service
@Primary
@ConditionalOnProperty(prefix = "opsmind.eval.judge", name = "enabled", havingValue = "true")
public class DashScopeEvalJudge implements EvalJudgeService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEvalJudge.class);
    private static final String DEFAULT_RUBRIC = "根因应准确解释工单现象、引用实际证据、不编造工单中不存在的信息";
    private static final String PROMPT_FILE = "eval-judge.txt";
    private static final String CALL_NAME = "llm.eval-judge";

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;

    public DashScopeEvalJudge(LlmCallExecutor llmCallExecutor, StructuredOutputParser parser) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public EvalQualityScore score(EvalCase evalCase, String rootCauseText) {
        String rubric = evalCase.rootCauseRubric() != null && !evalCase.rootCauseRubric().isBlank()
                ? evalCase.rootCauseRubric() : DEFAULT_RUBRIC;
        String userContent = buildUserContent(evalCase, rubric, rootCauseText);
        CallResult<LlmResponse> result = llmCallExecutor.execute(CALL_NAME, PROMPT_FILE, userContent);
        if (!result.success() || result.value() == null) {
            log.warn("Eval judge degraded for case={}, circuitOpen={}, error={}",
                    evalCase.id(), result.circuitOpen(),
                    result.error() == null ? "null" : result.error().getClass().getSimpleName());
            return null;
        }
        try {
            JudgeJson json = parser.parse(result.value().content(), JudgeJson.class);
            int score = clamp(json.score(), 1, 5);
            String reason = json.reason() == null ? "" : json.reason().trim();
            return new EvalQualityScore(score, reason);
        } catch (Exception ex) {
            log.warn("Eval judge parse failed for case={}, error={}", evalCase.id(), ex.getMessage());
            return null;
        }
    }

    private String buildUserContent(EvalCase evalCase, String rubric, String rootCauseText) {
        return "## 工单标题\n" + evalCase.title()
                + "\n\n## 工单描述\n" + evalCase.description()
                + "\n\n## 评分标准（rubric）\n" + rubric
                + "\n\n## Agent 实际输出的根因\n" + (rootCauseText == null ? "(无)" : rootCauseText)
                + "\n\n请按评分标准对根因打 1-5 分（5=完全准确且有证据，1=严重错误或编造），"
                + "并只输出合法 JSON：{\"score\": 分数, \"reason\": \"一句话理由\"}";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record JudgeJson(int score, String reason) {
    }
}
