package com.gcll.ticketagent.agent.multiagent;

import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子智能体抽象基类：封装"拿原始数据 → 用独立 LLM 会话推理结论"的公共流程。
 *
 * <p>模板方法：{@link #gatherRawEvidence} 由子类实现（调不同工具/检索拿数据），
 * {@link #reasonAbout} 由本类统一（调 LLM 用角色 prompt 推理）。
 *
 * <p>降级：LLM 推理失败时返回低置信度 finding（"原始数据 + 标注未推理"），不抛异常——
 * 避免单个 worker 失败拖垮并行调度（呼应项目"LLM 不可靠→兜底"主线）。
 */
public abstract class AbstractWorkerAgent implements WorkerAgent {

    private static final Logger log = LoggerFactory.getLogger(AbstractWorkerAgent.class);

    protected final LlmCallExecutor llmCallExecutor;

    /** 角色 prompt 文件名（prompts/ 下）。 */
    private final String rolePromptFile;
    /** LLM 调用点标识（治理映射用，如 llm.worker-log）。 */
    private final String callName;

    protected AbstractWorkerAgent(LlmCallExecutor llmCallExecutor, String rolePromptFile, String callName) {
        this.llmCallExecutor = llmCallExecutor;
        this.rolePromptFile = rolePromptFile;
        this.callName = callName;
    }

    @Override
    public final AgentFinding investigate(AgentRunContext ctx) {
        try {
            // 1. 子类实现：调底层工具/检索拿原始数据
            String rawEvidence = gatherRawEvidence(ctx);
            if (rawEvidence == null || rawEvidence.isBlank()) {
                log.info("Worker [{}] found no raw evidence, runId={}", role(), ctx.runId());
                return AgentFinding.of(role(), "未获取到原始证据", 0.1);
            }
            // 2. 本类统一：用独立 LLM 会话（角色 prompt）推理结论
            return reasonAbout(ctx, rawEvidence);
        } catch (Exception ex) {
            log.warn("Worker [{}] investigate failed, runId={}, error={}", role(), ctx.runId(), ex.getMessage());
            // 降级：失败不抛异常，返回低置信度 finding，不拖垮并行
            return AgentFinding.of(role(), "调查失败：" + ex.getMessage(), 0.0);
        }
    }

    /**
     * 子类实现：调底层工具/检索拿原始数据（日志/指标/案例），返回文本化的原始证据。
     */
    protected abstract String gatherRawEvidence(AgentRunContext ctx);

    /**
     * 用独立 LLM 会话推理：把原始证据 + 工单上下文喂给角色 prompt，产出带置信度的结论。
     * 失败降级为"原始证据 + 低置信度"。
     */
    private AgentFinding reasonAbout(AgentRunContext ctx, String rawEvidence) {
        String input = "【工单内容】\n" + ctx.originalContent()
                + "\n\n【抽取字段】\n" + summarizeExtract(ctx.extract())
                + "\n\n【原始证据】\n" + rawEvidence;
        CallResult<LlmResponse> result = llmCallExecutor.execute(callName, rolePromptFile, input);
        if (!result.success() || result.value() == null) {
            // 降级：LLM 不可用，直接返回原始证据 + 低置信度
            log.warn("Worker [{}] LLM reasoning failed, return raw evidence, runId={}", role(), ctx.runId());
            return AgentFinding.of(role(), "（未推理，原始证据）" + rawEvidence, 0.3);
        }
        return AgentFinding.of(role(), result.value().content(), 0.7);
    }

    private String summarizeExtract(com.gcll.ticketagent.extract.TicketExtractResult e) {
        return "issueType=" + e.issueType()
                + ", system=" + e.affectedSystem()
                + ", module=" + e.affectedModule()
                + ", errorCode=" + e.errorCode()
                + ", errorMessage=" + e.errorMessage();
    }
}
