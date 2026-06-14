package com.gcll.ticketagent.api;

import com.gcll.ticketagent.api.dto.AgentStepAuditDto;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AgentRunRepository agentRunRepository;

    public AuditController(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    @GetMapping("/agent-runs/{runId}")
    public List<AgentStepAuditDto> agentRunAudit(@PathVariable String runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found: " + runId));
        return run.getSteps().stream().map(this::toDto).toList();
    }

    private AgentStepAuditDto toDto(AgentStep step) {
        return new AgentStepAuditDto(
                step.getId(),
                step.getStepName(),
                step.getStatus(),
                step.getInputSnapshot(),
                step.getOutputSnapshot(),
                step.isLlmUsed(),
                step.getToolUsed(),
                step.getCostMs(),
                step.getErrorMessage(),
                step.getCreatedAt()
        );
    }
}
