package com.gcll.ticketagent.agent;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentAuditSummaryFormatter {

    public String summarizeGap(InfoGapAnalysis gap) {
        return "ready=" + gap.readyForAnalysis()
                + ",schemaMissing=" + gap.schemaMissing()
                + ",semanticGaps=" + gap.semanticGaps()
                + ",confidence=" + gap.confidence();
    }

    public String summarizeExtract(TicketExtractResult extract) {
        return "issueType=" + extract.issueType()
                + ",system=" + extract.affectedSystem()
                + ",env=" + extract.environment()
                + ",api=" + extract.apiName();
    }

    public String summarizeToolResults(List<ToolResult> results) {
        if (results.isEmpty()) {
            return "no_tools_executed";
        }
        long successCount = results.stream().filter(ToolResult::success).count();
        return results.size() + " tools executed, " + successCount + " succeeded";
    }
}
