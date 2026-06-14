package com.gcll.ticketagent.extract;

import com.gcll.ticketagent.llm.StepOutcome;

public interface TicketExtractService {
    StepOutcome<TicketExtractResult> extract(String content);
}
