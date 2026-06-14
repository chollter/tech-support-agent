package com.gcll.ticketagent.ticket;

import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.api.dto.SubmitAgentRunRequest;
import com.gcll.ticketagent.api.dto.SupplementMessageRequest;
import com.gcll.ticketagent.domain.AgentRun;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets/agent-runs")
public class TicketAgentRunController {

    private final TicketApplicationService ticketApplicationService;

    public TicketAgentRunController(TicketApplicationService ticketApplicationService) {
        this.ticketApplicationService = ticketApplicationService;
    }

    @PostMapping
    public AgentRunResponse submit(@Valid @RequestBody SubmitAgentRunRequest request) {
        return ticketApplicationService.submit(request);
    }

    @PostMapping("/{runId}/messages")
    public AgentRunResponse supplement(@PathVariable String runId, @Valid @RequestBody SupplementMessageRequest request) {
        return ticketApplicationService.supplement(runId, request);
    }

    @GetMapping("/{runId}")
    public AgentRun get(@PathVariable String runId) {
        return ticketApplicationService.getRun(runId);
    }
}
