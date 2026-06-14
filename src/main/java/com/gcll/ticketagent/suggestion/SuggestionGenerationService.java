package com.gcll.ticketagent.suggestion;

import com.gcll.ticketagent.analysis.RootCauseResult;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.tool.ToolResult;

import java.util.List;

public interface SuggestionGenerationService {
    StepOutcome<TicketSuggestion> generate(TicketExtractResult extract, List<KnowledgeHit> hits,
                                           List<ToolResult> toolResults, RootCauseResult rootCause);
}
