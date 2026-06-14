package com.gcll.ticketagent.async;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "opsmind.async", name = "enabled", havingValue = "true")
public class StuckRunRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(StuckRunRecoveryScheduler.class);

    private final AgentRunRepository agentRunRepository;
    private final AgentRunEventPublisher eventPublisher;
    private final AsyncAgentRunProperties properties;

    public StuckRunRecoveryScheduler(
            AgentRunRepository agentRunRepository,
            AgentRunEventPublisher eventPublisher,
            AsyncAgentRunProperties properties
    ) {
        this.agentRunRepository = agentRunRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${opsmind.async.stuck-scan-interval-ms:300000}")
    public void recoverStuckRuns() {
        Instant cutoff = Instant.now().minus(properties.getStuckRunningTimeoutMinutes(), ChronoUnit.MINUTES);
        List<AgentRun> stuckRuns = agentRunRepository.findStuckRunningRuns(cutoff);
        for (AgentRun run : stuckRuns) {
            log.warn("Re-publishing stuck RUNNING run, runId={}, updatedAt={}", run.getId(), run.getUpdatedAt());
            eventPublisher.publish(new AgentRunExecutionEvent(run.getId(), run.getTraceId(), "recovery"));
        }
    }
}
