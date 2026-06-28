package com.gcll.ticketagent.agent.multiagent;

import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.execution.evidence.EvidenceCollectionService;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 日志调查子智能体：查运维日志 → 推理错误位置与堆栈。
 *
 * <p>角色："你是日志分析专家"。复用 {@link EvidenceCollectionService} 调 query_logs 拿原始日志，
 * 再用自己的 LLM 会话推理"错误发生在哪、堆栈指向什么"。
 */
@Component
public class LogInvestigator extends AbstractWorkerAgent {

    private static final String ROLE = "log";
    private static final String PROMPT_FILE = "worker-log-investigator.txt";
    private static final String CALL_NAME = "llm.worker-log";

    private final EvidenceCollectionService evidenceCollectionService;

    public LogInvestigator(LlmCallExecutor llmCallExecutor, EvidenceCollectionService evidenceCollectionService) {
        super(llmCallExecutor, PROMPT_FILE, CALL_NAME);
        this.evidenceCollectionService = evidenceCollectionService;
    }

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    protected String gatherRawEvidence(AgentRunContext ctx) {
        // 复用现有证据收集：调 query_logs
        TicketExtractResult extract = ctx.extract();
        ToolSelection selection = new ToolSelection(List.of("query_logs"), java.util.Map.of(), "log-investigator", false);
        List<ToolResult> results = evidenceCollectionService.collect(ctx.runId(), extract, ctx.originalContent(), selection);
        StringBuilder sb = new StringBuilder();
        for (ToolResult r : results) {
            if (r.success() && r.output() != null) {
                sb.append("[").append(r.toolName()).append("]\n").append(r.output()).append("\n\n");
            }
        }
        return sb.toString();
    }
}
