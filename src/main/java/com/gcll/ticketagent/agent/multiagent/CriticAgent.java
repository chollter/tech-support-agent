package com.gcll.ticketagent.agent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审查员子智能体（Critic）：交叉验证各 Worker 的发现，综合根因。
 *
 * <p>这是多 Agent 相对单 Agent 的核心增量价值——交叉验证防幻觉。
 * 单 Agent 的根因只来自一次推理；多 Agent 下，Critic 拿多个专家的结论做一致性判断：
 * <ul>
 *   <li>各 worker 结论一致 → 置信度上调（多方印证）</li>
 *   <li>结论有矛盾 → 置信度下调 + 标注矛盾点（供审计/降级决策）</li>
 * </ul>
 *
 * <p>降级：Critic 的 LLM 推理失败时，用规则简单汇总（取所有 finding 拼接 + 平均置信度），
 * 不抛异常——审查失败不应阻断主流程。
 */
@Component
public class CriticAgent {

    private static final Logger log = LoggerFactory.getLogger(CriticAgent.class);
    private static final String PROMPT_FILE = "critic-review.txt";
    private static final String CALL_NAME = "llm.critic";

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;

    public CriticAgent(LlmCallExecutor llmCallExecutor, StructuredOutputParser parser, ObjectMapper objectMapper) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    /**
     * 审查：汇总各 worker 发现 → 交叉验证 → 综合根因。
     */
    public CriticVerdict review(AgentRunContext ctx) {
        List<AgentFinding> findings = ctx.findings();
        if (findings == null || findings.isEmpty()) {
            return new CriticVerdict(false, "无可用发现", 0.0, List.of("所有 worker 都未产出结论"), findings);
        }

        String input = buildInput(ctx, findings);
        CallResult<LlmResponse> result = llmCallExecutor.execute(CALL_NAME, PROMPT_FILE, input);
        if (!result.success() || result.value() == null) {
            log.warn("Critic LLM failed, degrade to rule-based merge, runId={}", ctx.runId());
            return ruleBasedMerge(findings);
        }
        try {
            CriticJson json = parser.parse(result.value().content(), CriticJson.class);
            return new CriticVerdict(
                    json.consistent(),
                    json.consolidatedRootCause(),
                    json.confidence(),
                    json.contradictions() == null ? List.of() : json.contradictions(),
                    findings
            );
        } catch (Exception ex) {
            log.warn("Critic parse failed, degrade to rule-based merge, runId={}, error={}", ctx.runId(), ex.getMessage());
            return ruleBasedMerge(findings);
        }
    }

    /** 规则兜底：LLM 审查失败时，拼接所有发现 + 平均置信度。 */
    private CriticVerdict ruleBasedMerge(List<AgentFinding> findings) {
        StringBuilder sb = new StringBuilder();
        double sum = 0;
        for (AgentFinding f : findings) {
            sb.append("[").append(f.sourceRole()).append("] ").append(f.content()).append("\n");
            sum += f.confidence();
        }
        double avg = findings.isEmpty() ? 0 : sum / findings.size();
        return new CriticVerdict(true, "（规则汇总，未经交叉验证）\n" + sb, avg, List.of(), findings);
    }

    private String buildInput(AgentRunContext ctx, List<AgentFinding> findings) {
        StringBuilder findingsText = new StringBuilder();
        for (AgentFinding f : findings) {
            findingsText.append("【").append(f.sourceRole()).append("】置信度")
                    .append(String.format("%.2f", f.confidence())).append("\n")
                    .append(f.content()).append("\n\n");
        }
        // 优化2：用 StructuredOutputParser 自动生成 JSON schema 提示（替代手写），
        // LLM 输出更规范，且 schema 跟随 CriticJson 类变化自动更新。
        String formatHint = parser.formatInstructions(CriticJson.class);
        return "【工单内容】\n" + ctx.originalContent()
                + "\n\n【各专家调查结论】\n" + findingsText
                + "\n" + formatHint;
    }

    /** Critic LLM 输出的 JSON 结构。 */
    public record CriticJson(
            boolean consistent,
            String consolidatedRootCause,
            double confidence,
            List<String> contradictions
    ) {}
}
