package com.gcll.ticketagent.eval.judge;

import com.gcll.ticketagent.eval.EvalCase;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DashScopeEvalJudge} 单测：mock {@link LlmCallExecutor} + 真实 {@link StructuredOutputParser}，
 * 验证打分、prompt 构造、降级（解析失败/调用失败返回 null）。
 */
class DashScopeEvalJudgeTest {

    private final LlmCallExecutor executor = mock(LlmCallExecutor.class);
    private final StructuredOutputParser parser = new StructuredOutputParser(new com.fasterxml.jackson.databind.ObjectMapper());
    private final DashScopeEvalJudge judge = new DashScopeEvalJudge(executor, parser);

    private EvalCase sampleCase(String rubric) {
        return new EvalCase("oom-killed", "core-regression", "OOMKilled",
                "Pod OOMKilled, Java heap space", null, null, null, 0, false,
                false, true, false, true, false, false, false, null, null, null, null, null, null, rubric);
    }

    @Test
    void scoresValidJsonOutput() {
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.ok(LlmResponse.of("{\"score\":4,\"reason\":\"准确识别OOM\"}", 10, 20), 1, 100));

        EvalQualityScore score = judge.score(sampleCase(null), "根因是堆内存泄漏导致OOM");

        assertThat(score).isNotNull();
        assertThat(score.score()).isEqualTo(4);
        assertThat(score.reason()).isEqualTo("准确识别OOM");
        assertThat(judge.available()).isTrue();
    }

    @Test
    void usesCaseSpecificRubricWhenPresent() {
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.ok(LlmResponse.of("{\"score\":5,\"reason\":\"ok\"}", 10, 20), 1, 100));

        judge.score(sampleCase("应识别为内存泄漏"), "内存泄漏");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(executor).execute(anyString(), anyString(), content.capture());
        assertThat(content.getValue()).contains("应识别为内存泄漏");
    }

    @Test
    void usesDefaultRubricWhenCaseRubricNull() {
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.ok(LlmResponse.of("{\"score\":3,\"reason\":\"ok\"}", 10, 20), 1, 100));

        judge.score(sampleCase(null), "some root cause");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(executor).execute(anyString(), anyString(), content.capture());
        assertThat(content.getValue()).contains("根因应准确解释工单现象");
    }

    @Test
    void clampsScoreToValidRange() {
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.ok(LlmResponse.of("{\"score\":9,\"reason\":\"偏高\"}", 10, 20), 1, 100));

        EvalQualityScore score = judge.score(sampleCase(null), "root cause");
        assertThat(score.score()).isEqualTo(5);
    }

    @Test
    void returnsNullWhenCallFails() {
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.fail(new RuntimeException("timeout"), 1, 100));

        EvalQualityScore score = judge.score(sampleCase(null), "root cause");
        assertThat(score).isNull();
    }

    @Test
    void returnsNullWhenCircuitOpen() {
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.circuitOpen(0));

        EvalQualityScore score = judge.score(sampleCase(null), "root cause");
        assertThat(score).isNull();
    }

    @Test
    void returnsNullWhenParseFails() {
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.ok(LlmResponse.of("not json at all", 10, 20), 1, 100));

        EvalQualityScore score = judge.score(sampleCase(null), "root cause");
        assertThat(score).isNull();
    }

    @Test
    void stripsCodeFenceBeforeParsing() {
        when(executor.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.ok(LlmResponse.of("```json\n{\"score\":2,\"reason\":\"fenced\"}\n```", 10, 20), 1, 100));

        EvalQualityScore score = judge.score(sampleCase(null), "root cause");
        assertThat(score).isNotNull();
        assertThat(score.score()).isEqualTo(2);
    }
}
