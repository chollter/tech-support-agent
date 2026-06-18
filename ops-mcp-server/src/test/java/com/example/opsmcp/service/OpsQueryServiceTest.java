package com.example.opsmcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OpsQueryService} 查询逻辑测试（H2）。
 * <p>验证从主项目迁移后的查询/归一化/格式化逻辑行为不变。
 * 不依赖真实 MCP 通信，只测背后的查询服务。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsQueryServiceTest {

    @Autowired
    private OpsQueryService queryService;

    @Test
    void queryLogsBySystemAndKeywordReturnsFormatted() {
        // normalizeKeyword 把含 "memory"/"OOM" 的 query 归一化为 "memory"
        String result = queryService.queryLogs("payment", "callback", "OOMKilled memory exceeded", 5);

        assertThat(result).contains("memory exceeded");
        assertThat(result).contains("ERROR");
        assertThat(result).contains("payment-service");
    }

    @Test
    void queryLogsWithoutKeywordReturnsNoEvidence() {
        // 无 system 且 query 不命中任何归一化关键词 → 无匹配
        String result = queryService.queryLogs(null, null, "totally-unrelated-content", 5);
        assertThat(result).isEqualTo("no log evidence found");
    }

    @Test
    void queryMetricsBySystemReturnsFormatted() {
        // normalizeKeyword 把含 "memory" 的 query 归一化为 "memory"，命中 jvm.memory.heap 的 metricName
        String result = queryService.queryMetrics("payment", null, "memory heap usage", 8);

        assertThat(result).contains("jvm.memory.heap");
        assertThat(result).contains("CRITICAL");
    }

    @Test
    void queryMetricsRespectsLimit() {
        // 只插了 2 条，limit=1 应只返回 1 条（按 occurred_at 倒序）
        String result = queryService.queryMetrics("payment", null, "payment-service", 1);
        // 两条都命中 system=payment + service 含 payment-service，limit 后只留最新一条
        long lines = result.lines().count();
        assertThat(lines).isLessThanOrEqualTo(1);
    }

    @Test
    void keywordNormalizationMapsConnectionKeyword() {
        // normalizeKeyword: 含 "connection" → "connection"
        String result = queryService.queryLogs("payment", null, "connection pool exhausted", 5);
        assertThat(result).contains("connection pool exhausted");
    }
}
