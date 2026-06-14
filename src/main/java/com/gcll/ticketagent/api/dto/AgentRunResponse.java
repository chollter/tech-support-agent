package com.gcll.ticketagent.api.dto;

import com.gcll.ticketagent.domain.AgentRunStatus;

import java.util.List;

public record AgentRunResponse(
        String runId,
        AgentRunStatus status,
        ReplyType replyType,
        String message,
        List<String> questions,
        TicketAnalysisDto analysis,
        boolean aiGenerated
) {
}
