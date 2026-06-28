package com.gcll.ticketagent.agent.multiagent;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.knowledge.KnowledgeSearchService;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史案例研究员子智能体：检索相似案例 → 推理可参考的根因模式。
 *
 * <p>角色："你是历史案例研究员"。复用 {@link KnowledgeSearchService}（向量+关键词+RRF 检索）
 * 拿相似案例，再用自己的 LLM 会话推理"历史类似工单的根因能否借鉴"。
 */
@Component
public class KnowledgeResearcher extends AbstractWorkerAgent {

    private static final String ROLE = "knowledge";
    private static final String PROMPT_FILE = "worker-knowledge-researcher.txt";
    private static final String CALL_NAME = "llm.worker-knowledge";

    private final KnowledgeSearchService knowledgeSearchService;

    public KnowledgeResearcher(LlmCallExecutor llmCallExecutor, KnowledgeSearchService knowledgeSearchService) {
        super(llmCallExecutor, PROMPT_FILE, CALL_NAME);
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    protected String gatherRawEvidence(AgentRunContext ctx) {
        TicketExtractResult extract = ctx.extract();
        List<KnowledgeHit> hits = knowledgeSearchService.search(
                ctx.originalContent(),
                extract.affectedSystem(),
                extract.affectedModule(),
                extract.issueType().name()
        );
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeHit hit : hits) {
            sb.append("【案例】").append(hit.title() == null ? "" : hit.title())
              .append("\n摘要：").append(hit.summary() == null ? "" : hit.summary())
              .append("\n解决方案：").append(hit.resolution() == null ? "" : hit.resolution())
              .append("\n\n");
        }
        return sb.toString();
    }
}
