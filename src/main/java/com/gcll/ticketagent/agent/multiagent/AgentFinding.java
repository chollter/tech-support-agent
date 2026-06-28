package com.gcll.ticketagent.agent.multiagent;

/**
 * 子智能体产出的"发现"——一个带角色来源、内容、置信度的结论单元。
 *
 * <p>每个 Worker 子智能体调查后产出一个 {@link AgentFinding}，
 * Critic 拿多个 finding 做交叉验证。{@link #sourceRole()} 区分证据来自哪个专家，
 * 是多 Agent 交叉验证的基础。
 */
public record AgentFinding(
        String sourceRole,    // 产出方角色（log/metric/knowledge）
        String content,       // 结论内容（带推理，不是原始数据）
        double confidence     // 子智能体自评置信度 0-1
) {
    public static AgentFinding of(String role, String content, double confidence) {
        return new AgentFinding(role, content, confidence);
    }
}
