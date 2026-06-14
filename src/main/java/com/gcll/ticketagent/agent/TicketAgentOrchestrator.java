package com.gcll.ticketagent.agent;

import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.ticket.TicketDraft;
import org.springframework.stereotype.Service;

@Service
public class TicketAgentOrchestrator {

    private final AgentRuntime agentRuntime;

    public TicketAgentOrchestrator(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    public AgentRunResponse execute(AgentRun run, TicketDraft draft) {
        return agentRuntime.execute(run, draft);
    }
}
