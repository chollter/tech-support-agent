package com.gcll.ticketagent.agent.multiagent;

import java.util.List;

/**
 * Critic 子智能体的审查结论。
 *
 * <p>Critic 拿多个 Worker 的 {@link AgentFinding} 做交叉验证，判断证据是否一致、
 * 是否充分，给出综合根因与置信度。这是多 Agent 相对单 Agent 的核心增量——
 * 交叉验证防幻觉（呼应项目"LLM 不可靠→工程兜底"主线）。
 *
 * @param consistent       各 worker 结论是否相互印证（true=一致，false=有矛盾）
 * @param consolidatedRootCause 综合后的根因结论
 * @param confidence       综合置信度 0-1（多方一致时上调，矛盾时下调）
 * @param contradictions   发现的矛盾点（consistent=false 时填，供审计/降级决策）
 * @param findings         各 worker 的原始发现（供下游提取证据列表，保留可追溯性）
 */
public record CriticVerdict(
        boolean consistent,
        String consolidatedRootCause,
        double confidence,
        List<String> contradictions,
        List<AgentFinding> findings
) {
    public static CriticVerdict of(boolean consistent, String rootCause, double confidence,
                                   List<String> contradictions, List<AgentFinding> findings) {
        return new CriticVerdict(consistent, rootCause, confidence, contradictions, findings);
    }
}
