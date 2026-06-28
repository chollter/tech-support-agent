package com.gcll.ticketagent.agent.multiagent;

/**
 * 子智能体（Worker）公共接口。每个实现是一个"有独立角色、独立 LLM 会话、独立目标"的子 agent。
 *
 * <p>子智能体 vs 普通工具的区别：工具只返回原始数据（如日志原文），子智能体拿到数据后
 * 用自己的 LLM 会话推理出结论（如"日志显示 OOM，堆在 XX 类"）。这是多 Agent 分而治之的本质。
 *
 * <p>实现：LogInvestigator（日志）/ MetricAnalyst（指标）/ KnowledgeResearcher（历史案例）。
 * 每个 worker 并行执行，产出一个 {@link AgentFinding} 写入共享 {@link AgentRunContext}。
 */
public interface WorkerAgent {

    /** 子智能体角色标识（log/metric/knowledge），用于 finding 来源区分。 */
    String role();

    /**
     * 执行调查：调底层工具/检索拿原始数据 → 用独立 LLM 会话推理结论。
     *
     * @param ctx 共享黑板（取工单上下文/extract）
     * @return 该子智能体的发现；失败时返回低置信度 finding（不抛异常，避免拖垮并行）
     */
    AgentFinding investigate(AgentRunContext ctx);
}
