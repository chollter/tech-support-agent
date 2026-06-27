package com.gcll.ticketagent.agent.workflow;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.tool.ToolResult;

import java.util.List;

/**
 * 根因分析策略：抽象"从抽取结果到根因"这一段，支持多种实现可切换。
 *
 * <p>阶段2 引入：现有 {@code AnalysisWorkflowService} 的 plan→knowledge→tool→rootcause 四步，
 * 是"线性取证 + 单次 LLM 推理"。阶段2 新增 ReAct 实现：LLM 自主调工具迭代推理。
 * 两者通过本接口统一，由 {@code opsmind.agent.root-cause-strategy} 配置开关切换。
 *
 * <p>ReAct 失败时回退线性实现（兜底），保证"LLM 不可靠→工程兜底"主线。
 */
public interface RootCauseStrategy {

    /**
     * 执行根因分析（含证据收集）。
     *
     * @param runId           工单运行 ID
     * @param extract         结构化抽取结果
     * @param originalContent 工单原文
     * @return 根因分析结果（含假设、证据、置信度、调用了哪些工具、是否走 LLM、耗时）
     */
    RootCauseOutcome analyze(String runId, TicketExtractResult extract, String originalContent);

    /** 策略标识（"linear" / "react"），用于审计区分走了哪条路径。 */
    String strategyName();

    /** 根因分析产出。 */
    record RootCauseOutcome(
            String hypothesis,
            List<String> evidence,
            double confidence,
            List<String> unknowns,
            List<String> actions,
            List<KnowledgeHit> knowledgeHits,
            List<ToolResult> toolResults,
            boolean llmUsed,
            boolean fallbacked,   // true 表示从 ReAct 回退到线性（审计可追溯）
            long durationMs
    ) {}
}
