package com.gcll.ticketagent.tool.function;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.knowledge.KnowledgeSearchService;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SimilarCaseFunctionTool implements ToolGateway {

    private final KnowledgeSearchService knowledgeSearchService;

    public SimilarCaseFunctionTool(KnowledgeSearchService knowledgeSearchService) {
        this.knowledgeSearchService = knowledgeSearchService;
    }

    @Override
    public ToolType toolType() {
        return ToolType.FUNCTION;
    }

    @Override
    public String toolName() {
        return "searchSimilarCases";
    }

    @Override
    public ToolResult execute(TicketExtractResult extract, String originalContent) {
        long start = System.currentTimeMillis();
        String input = "system=" + extract.affectedSystem()
                + ",module=" + extract.affectedModule()
                + ",issueType=" + extract.issueType()
                + ",query=" + originalContent;
        List<KnowledgeHit> hits = knowledgeSearchService.search(
                originalContent,
                extract.affectedSystem(),
                extract.affectedModule(),
                extract.issueType().name()
        );
        String output = hits.isEmpty()
                ? "no similar case found"
                : formatHits(hits);
        return ToolResult.success(ToolType.FUNCTION, toolName(), input, output, System.currentTimeMillis() - start);
    }

    private String formatHits(List<KnowledgeHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeHit hit : hits.stream().limit(3).toList()) {
            sb.append(hit.sourceType()).append(':')
                    .append(hit.sourceId()).append(' ')
                    .append(hit.title()).append(" score=")
                    .append(hit.score()).append(" reason=")
                    .append(hit.matchedReason()).append('\n');
        }
        return sb.toString().trim();
    }
}
