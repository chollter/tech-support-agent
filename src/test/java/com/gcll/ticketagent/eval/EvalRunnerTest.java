package com.gcll.ticketagent.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden Case 端到端评测。
 *
 * <p><b>仅在真实 LLM_API_KEY 下运行</b>。原因：eval-cases.json 的期望是基于真 LLM 行为
 * 标定的（如"信息足够的口语化工单直接分析"），而 test profile 用假 key 走规则降级，
 * 规则 gap 判断比 LLM 保守，对这些 case 会判 NEED_MORE_INFO，行为与真 LLM 不同。
 * 在假 key 下跑这个测试无意义（测的是降级路径，不是 Agent 真实水平），故按环境变量门控跳过。
 *
 * <p>本地验证真 LLM 效果时：{@code set EVAL_JUDGE_ENABLED=true && mvn test -Dtest=EvalRunnerTest}
 * （需同时配真实 LLM_API_KEY）。
 */
@SpringBootTest
@ActiveProfiles("test")
// 仅当提供了真实 key（非 test-key 占位）时才运行；CI 无 key 时自动跳过
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = "sk-.+")
class EvalRunnerTest {

    @Autowired
    private EvalRunner evalRunner;

    @Test
    void allIncidentCasesPass() {
        EvalReport report = evalRunner.run();
        assertThat(report.failures()).isEmpty();
        assertThat(report.passed()).isEqualTo(report.total());
    }
}
