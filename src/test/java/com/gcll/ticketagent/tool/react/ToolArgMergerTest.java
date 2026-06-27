package com.gcll.ticketagent.tool.react;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolArgMerger 单测（阶段B：真·工具调用）。
 * 覆盖：LLM 参数覆盖 extract / 保留原值 / 兜底降级 / 未覆盖字段不变。
 */
class ToolArgMergerTest {

    private final ToolArgMerger merger = new ToolArgMerger();

    /** 基线 extract：system/module 都有值。 */
    private TicketExtractResult base() {
        return new TicketExtractResult(
                IssueType.INCIDENT, "old-system", "old-module", "payCallback",
                "OOM", "heap space", "prod", "200 orders", "14:30",
                "支付受影响", List.of("CORE_PAYMENT"), 0.8
        );
    }

    /** 场景1：LLM 传了 system，覆盖 extract 的；module 不传，保留原值。 */
    @Test
    void llmSystemOverwritesExtractModuleKept() {
        TicketExtractResult extract = base();
        Map<String, String> args = Map.of("system", "payment-service");

        TicketExtractResult merged = merger.merge(extract, args);

        assertThat(merged.affectedSystem()).isEqualTo("payment-service");
        assertThat(merged.affectedModule()).isEqualTo("old-module"); // 未传，保留
    }

    /** 场景2：LLM 同时传 system+module，两个都覆盖。 */
    @Test
    void bothArgsOverwrite() {
        TicketExtractResult merged = merger.merge(base(),
                Map.of("system", "svc-a", "module", "/api/x"));

        assertThat(merged.affectedSystem()).isEqualTo("svc-a");
        assertThat(merged.affectedModule()).isEqualTo("/api/x");
    }

    /** 场景3：LLM 传空白参数，保留 extract 原值（兜底降级）。 */
    @Test
    void blankArgsKeptOriginal() {
        TicketExtractResult merged = merger.merge(base(),
                Map.of("system", "  ", "module", ""));

        assertThat(merged.affectedSystem()).isEqualTo("old-system");
        assertThat(merged.affectedModule()).isEqualTo("old-module");
    }

    /** 场景4：LLM 参数为 null/空 map，原样返回 extract（不重建）。 */
    @Test
    void nullOrEmptyArgsReturnOriginalInstance() {
        TicketExtractResult extract = base();

        assertThat(merger.merge(extract, null)).isSameAs(extract);
        assertThat(merger.merge(extract, Map.of())).isSameAs(extract);
    }

    /** 场景5：LLM 参数覆盖了，但其他字段（errorCode/confidence 等）必须不变。 */
    @Test
    void nonOverwrittenFieldsPreserved() {
        TicketExtractResult extract = base();

        TicketExtractResult merged = merger.merge(extract, Map.of("system", "new"));

        assertThat(merged.issueType()).isEqualTo(IssueType.INCIDENT);
        assertThat(merged.errorCode()).isEqualTo("OOM");
        assertThat(merged.confidence()).isEqualTo(0.8);
        assertThat(merged.severitySignals()).containsExactly("CORE_PAYMENT");
    }

    /** 场景6：LLM 传了未知 key（如 query），不报错、不影响 system/module。 */
    @Test
    void unknownKeysIgnored() {
        TicketExtractResult merged = merger.merge(base(),
                Map.of("query", "some keyword", "system", "svc-b"));

        assertThat(merged.affectedSystem()).isEqualTo("svc-b");
    }

    /** 场景7：LLM 参数值带空格，trim 后覆盖。 */
    @Test
    void argsTrimmed() {
        TicketExtractResult merged = merger.merge(base(), Map.of("system", "  payment  "));

        assertThat(merged.affectedSystem()).isEqualTo("payment");
    }
}
