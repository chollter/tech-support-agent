package com.gcll.ticketagent.agent.multiagent;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多智能体共享黑板（阶段C：任务拆解/多 Agent 协作）。
 *
 * <p>多 Agent 协作的本质是"多个独立 agent 往共享黑板读写"，而不是局部变量传参。
 * Supervisor 写任务列表，Worker 并行写发现，Critic 读所有发现写审查结论。
 *
 * <p>线程安全：Worker 并行执行，findings 用同步 List 保护并发写。
 *
 * @param runId           工单运行 ID（审计/记忆隔离用）
 * @param originalContent 工单原文（各 worker 共享）
 * @param extract         结构化抽取结果（各 worker 查询参数来源）
 * @param gap             信息缺口分析（supervisor 拆任务参考）
 * @param tasks           supervisor 拆出的子任务列表
 * @param findings        各 worker 产出的发现（并发写，加锁保护）
 * @param verdict         critic 的审查结论
 */
public class AgentRunContext {

    private final String runId;
    private final String originalContent;
    private final TicketExtractResult extract;
    private final InfoGapAnalysis gap;

    private final List<String> tasks = Collections.synchronizedList(new ArrayList<>());
    private final List<AgentFinding> findings = Collections.synchronizedList(new ArrayList<>());
    private volatile CriticVerdict verdict;

    public AgentRunContext(String runId, String originalContent, TicketExtractResult extract, InfoGapAnalysis gap) {
        this.runId = runId;
        this.originalContent = originalContent;
        this.extract = extract;
        this.gap = gap;
    }

    /** Supervisor 拆出的子任务（如"查日志""查指标""查历史案例"）。 */
    public void addTask(String task) {
        tasks.add(task);
    }

    /** Worker 并行产出发现（线程安全）。 */
    public void addFinding(AgentFinding finding) {
        findings.add(finding);
    }

    public String runId() { return runId; }
    public String originalContent() { return originalContent; }
    public TicketExtractResult extract() { return extract; }
    public InfoGapAnalysis gap() { return gap; }

    public List<String> tasks() { return Collections.unmodifiableList(tasks); }
    public List<AgentFinding> findings() { return Collections.unmodifiableList(findings); }

    public CriticVerdict verdict() { return verdict; }
    public void setVerdict(CriticVerdict verdict) { this.verdict = verdict; }
}
