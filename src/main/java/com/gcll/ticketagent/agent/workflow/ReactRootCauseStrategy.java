package com.gcll.ticketagent.agent.workflow;

import com.gcll.ticketagent.agent.react.ReActLoop;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * ReAct 根因分析策略：LLM 自主调工具迭代推理根因。
 *
 * <p>激活条件：{@code opsmind.agent.root-cause-strategy=react} 时启用（默认 linear）。
 * 启用后替代线性策略成为 {@code @Primary}。
 *
 * <p>失败回退：ReAct 循环异常（超步/解析失败/超时）时，本实现<b>不自行回退</b>，
 * 而是抛出异常，由调用方（AnalysisWorkflowService）catch 后切换到线性策略重跑。
 * 这样回退逻辑集中在一处，且保留 ReAct 异常供审计。
 */
@Service
@ConditionalOnProperty(prefix = "opsmind.agent", name = "root-cause-strategy", havingValue = "react")
public class ReactRootCauseStrategy implements RootCauseStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReactRootCauseStrategy.class);

    private final ReActLoop reActLoop;

    public ReactRootCauseStrategy(ReActLoop reActLoop) {
        this.reActLoop = reActLoop;
    }

    @Override
    public RootCauseOutcome analyze(String runId, TicketExtractResult extract, String originalContent) {
        long start = System.currentTimeMillis();
        ReActLoop.ReActResult result = reActLoop.run(runId, extract, originalContent);
        long durationMs = System.currentTimeMillis() - start;

        // ReAct 模式下，knowledge/tool 调用都内嵌在循环里，这里 hits 为空
        // （根因证据已在 evidence 列表里），toolResults 用工具名列表代表。
        // 兼容下游（priority/routing 需要 hits）由 AnalysisWorkflowService 补查知识库。
        List<KnowledgeHit> hits = Collections.emptyList();
        List<ToolResult> toolResults = Collections.emptyList(); // 实际工具结果已进 evidence

        return new RootCauseOutcome(
                result.hypothesis(),
                result.evidence(),
                result.confidence(),
                result.unknowns(),
                result.actions(),
                hits,
                toolResults,
                true,        // ReAct 一定用了 LLM
                false,       // 未回退（回退由调用方处理）
                durationMs
        );
    }

    @Override
    public String strategyName() {
        return "react";
    }
}
