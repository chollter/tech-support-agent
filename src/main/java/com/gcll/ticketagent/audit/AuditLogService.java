package com.gcll.ticketagent.audit;

import com.gcll.ticketagent.agent.AgentStepEventPublisher;
import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.persistence.repository.AgentStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuditLogService {

    private final AgentStepRepository agentStepRepository;
    private final AgentStepEventPublisher stepEventPublisher;

    public AuditLogService(AgentStepRepository agentStepRepository, AgentStepEventPublisher stepEventPublisher) {
        this.agentStepRepository = agentStepRepository;
        this.stepEventPublisher = stepEventPublisher;
    }

    @Transactional
    public void recordStep(
            AgentRun run,
            AgentStepName stepName,
            String inputSnapshot,
            String outputSnapshot,
            boolean llmUsed,
            String toolUsed,
            long costMs,
            String errorMessage
    ) {
        AgentStep step = new AgentStep(
                UUID.randomUUID().toString(),
                run.getId(),
                stepName.name(),
                errorMessage == null ? "SUCCESS" : "FAILED",
                Instant.now()
        );
        step.setInputSnapshot(inputSnapshot);
        step.setOutputSnapshot(outputSnapshot);
        step.setLlmUsed(llmUsed);
        step.setToolUsed(toolUsed);
        step.setCostMs(costMs);
        step.setErrorMessage(errorMessage);
        run.getSteps().add(step);
        agentStepRepository.save(step);
        stepEventPublisher.publish(run.getId(), stepName.name(), step.getStatus(), outputSnapshot);
    }
}
