package com.gcll.ticketagent.agent.workflow;

import com.gcll.ticketagent.agent.multiagent.AgentFinding;
import com.gcll.ticketagent.agent.multiagent.CriticVerdict;
import com.gcll.ticketagent.agent.multiagent.MultiAgentOrchestrator;
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
 * 多智能体根因策略：supervisor-worker-critic 模式。
 *
 * <p>激活条件：{@code opsmind.agent.root-cause-strategy=multi}（默认 linear）。
 * 启用后作为 {@code @Primary} 根因策略，替代线性/ReAct。
 *
 * <p>流程：{@link MultiAgentOrchestrator} 拆任务 → 3 个 worker 并行调查 → Critic 交叉验证。
 * 把 CriticVerdict 转成统一的 {@link RootCauseOutcome}，下游 priority/routing/suggestion 无感知。
 *
 * <p>失败回退：orchestrator 异常时抛出（不自行回退），由 AnalysisWorkflowService catch 后
 * 切换到线性策略重跑——回退逻辑集中在一处（与 ReactRootCauseStrategy 一致）。
 */
@Service
@ConditionalOnProperty(prefix = "opsmind.agent", name = "root-cause-strategy", havingValue = "multi")
public class MultiAgentRootCauseStrategy implements RootCauseStrategy {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentRootCauseStrategy.class);

    private final MultiAgentOrchestrator orchestrator;

    public MultiAgentRootCauseStrategy(MultiAgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public RootCauseOutcome analyze(String runId, TicketExtractResult extract, String originalContent) {
        long start = System.currentTimeMillis();
        // gap 在多 agent 模式下由 worker 内部各自按需用，这里传 null（orchestrator 接受 null gap）
        CriticVerdict verdict = orchestrator.orchestrate(runId, originalContent, extract, null);
        long durationMs = System.currentTimeMillis() - start;

        // 把 CriticVerdict 转成统一 RootCauseOutcome
        List<String> evidence = new java.util.ArrayList<>();
        List<AgentFinding> findings = verdict.findings() == null ? Collections.emptyList() : verdict.findings();
        for (AgentFinding f : findings) {
            evidence.add("[" + f.sourceRole() + "] " + f.content());
        }
        // unknowns 用矛盾点填充（有矛盾 = 还有不确定项）
        List<String> unknowns = verdict.contradictions() == null
                ? Collections.emptyList() : verdict.contradictions();

        log.info("Multi-agent root cause done, runId={}, durationMs={}, consistent={}, confidence={}",
                runId, durationMs, verdict.consistent(), verdict.confidence());

        return new RootCauseOutcome(
                verdict.consolidatedRootCause(),
                evidence,
                verdict.confidence(),
                unknowns,
                Collections.emptyList(),   // actions 由后续 suggestion 生成
                Collections.emptyList(),   // knowledge hits 已内化进 finding
                Collections.emptyList(),   // tool results 已内化进 finding
                true,                      // 多 agent 必用了 LLM
                false,                     // 未回退（回退由调用方处理）
                durationMs
        );
    }

    @Override
    public String strategyName() {
        return "multi";
    }
}
