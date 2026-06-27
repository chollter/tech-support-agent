package com.gcll.ticketagent.tool.react;

import com.gcll.ticketagent.extract.TicketExtractResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具参数合并器（阶段B：真·工具调用）。
 *
 * <p>把 LLM 自主生成的工具参数（system/module 等）合并进 {@link TicketExtractResult}，
 * 合成"修正版 extract"供底层 {@link com.gcll.ticketagent.tool.ToolGateway} 执行。
 *
 * <h3>核心思想：参数→extract 合成，而非重写底层工具</h3>
 * 底层工具签名是 {@code execute(extract, originalContent)}，本质用 extract 的字段查询。
 * LLM 自主传参的价值不是换查询方式，而是<b>修正/补充 extract 没抽准的字段</b>——
 * 例如 extract 抽出 affectedSystem=null，LLM 从上下文推断出 payment-service。
 * 故让 LLM 参数<b>覆盖 extract 对应字段</b>，合成修正版，既复用底层逻辑又实现自主性。
 *
 * <h3>覆盖规则（只覆盖非空）</h3>
 * LLM 传的参数为 null/空白时保留 extract 原值（不破坏 extract 已有的可信抽取）。
 * 这保证 LLM 没传参时退化为现状行为（纯 extract 兜底），不比现在差。
 */
@Component
public class ToolArgMerger {

    private static final Logger log = LoggerFactory.getLogger(ToolArgMerger.class);

    /** LLM 参数 key → 合并时覆盖 extract 哪个字段的映射约定。 */
    public static final String PARAM_SYSTEM = "system";
    public static final String PARAM_MODULE = "module";

    /**
     * 合并 LLM 参数到 extract，返回修正版 extract。
     *
     * @param extract  原抽取结果（可信基线）
     * @param llmArgs  LLM 生成的工具参数（可为 null/空，此时原样返回 extract）
     * @return 合并后的 extract；LLM 参数非空则覆盖对应字段，否则保留原值
     */
    public TicketExtractResult merge(TicketExtractResult extract, Map<String, String> llmArgs) {
        if (extract == null || llmArgs == null || llmArgs.isEmpty()) {
            return extract;
        }

        String mergedSystem = pickOverwrite(extract.affectedSystem(), llmArgs.get(PARAM_SYSTEM));
        String mergedModule = pickOverwrite(extract.affectedModule(), llmArgs.get(PARAM_MODULE));

        // 无变化则直接返回原 extract（避免无谓重建）
        if (java.util.Objects.equals(mergedSystem, extract.affectedSystem())
                && java.util.Objects.equals(mergedModule, extract.affectedModule())) {
            return extract;
        }

        log.info("Tool args merged: system [{}]->[{}], module [{}]->[{}]",
                extract.affectedSystem(), mergedSystem, extract.affectedModule(), mergedModule);

        // record 不可变，重建一个，未覆盖字段保留原值
        return new TicketExtractResult(
                extract.issueType(),
                mergedSystem,
                mergedModule,
                extract.apiName(),
                extract.errorCode(),
                extract.errorMessage(),
                extract.environment(),
                extract.impactScope(),
                extract.timeRange(),
                extract.businessImpact(),
                extract.severitySignals(),
                extract.confidence()
        );
    }

    /** LLM 参数非空则覆盖，否则保留原值。 */
    private String pickOverwrite(String original, String llmValue) {
        if (llmValue == null || llmValue.isBlank()) {
            return original;
        }
        return llmValue.trim();
    }
}
