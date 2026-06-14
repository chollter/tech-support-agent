package com.gcll.ticketagent.understanding.gap;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StepOutcome;

public interface InfoGapAnalysisService {

    StepOutcome<InfoGapAnalysis> analyze(String userContent, TicketExtractResult extract);
}
