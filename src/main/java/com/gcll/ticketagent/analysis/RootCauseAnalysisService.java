package com.gcll.ticketagent.analysis;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.tool.ToolResult;

import java.util.List;

public interface RootCauseAnalysisService {
    RootCauseResult analyze(TicketExtractResult extract, List<KnowledgeHit> hits, List<ToolResult> toolResults);
}
