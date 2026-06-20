package com.gcll.ticketagent.eval.adversarial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 红队闭环烟雾测试。
 *
 * <p>用 {@code @TempDir} 把对抗 case 输出重定向到临时路径，避免污染工程目录下的
 * {@code data/eval/}。测试 profile 下无真实 LLM（{@code LlmCallExecutor} fail-fast），
 * 走模板生成 + 锚定规则裁判的降级路径，因此不依赖外部 LLM、可稳定重跑。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdversarialRedTeamServiceTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void redirectAdversarialOutput(DynamicPropertyRegistry registry) {
        registry.add("opsmind.eval.adversarial.output-path",
                () -> tempDir.resolve("adversarial-cases.json").toString());
    }

    @Autowired
    private AdversarialRedTeamService redTeamService;

    @Autowired
    private AdversarialCaseStore store;

    @Test
    void generateRunsFullLoopAndPersistsSunkCases() {
        RedTeamReport report = redTeamService.generate(3, null);

        // 降级路径下也会产出 count 条输入
        assertThat(report.generated()).isEqualTo(3);
        // 降级裁判对每条都给出结论（锚定规则 pass-through 或 fail）
        assertThat(report.judged()).isEqualTo(3);
        // 沉淀数绝不超过判定 fail 数
        assertThat(report.sunk()).isLessThanOrEqualTo(report.failed());
        assertThat(report.items()).hasSize(3);
        // 每条 item 都有基础字段
        report.items().forEach(item -> {
            assertThat(item.description()).isNotBlank();
            assertThat(item.attackStrategy()).isNotBlank();
        });

        // 关键不变量：sunk 之后文件可读回，且数量匹配
        if (report.sunk() > 0) {
            assertThat(store.load()).hasSizeGreaterThanOrEqualTo(report.sunk());
        }
    }

    @Test
    void secondRunDedupsAgainstFirstRunOutput() {
        redTeamService.generate(2, null);
        int firstSize = store.load().size();

        // 第二轮用相同降级模板（描述相同）→ 应被去重，文件不再增长
        redTeamService.generate(2, null);
        int secondSize = store.load().size();

        assertThat(secondSize).isEqualTo(firstSize);
    }
}
