package com.gcll.ticketagent.async;

import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.ticket.TicketApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "opsmind.async", name = "enabled", havingValue = "true")
public class AgentRunEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgentRunEventConsumer.class);

    private final AgentRunRepository agentRunRepository;
    private final TicketApplicationService ticketApplicationService;

    public AgentRunEventConsumer(
            AgentRunRepository agentRunRepository,
            TicketApplicationService ticketApplicationService
    ) {
        this.agentRunRepository = agentRunRepository;
        this.ticketApplicationService = ticketApplicationService;
    }

    @KafkaListener(topics = "${opsmind.async.topic}", groupId = "opsmind-agent-runner")
    public void consume(AgentRunExecutionEvent event) {
        agentRunRepository.findById(event.runId()).ifPresentOrElse(run -> {
            if (run.getStatus() != AgentRunStatus.RUNNING) {
                log.info("Skip already processed run event, runId={}, status={}, reason={}",
                        run.getId(), run.getStatus(), event.reason());
                return;
            }
            ticketApplicationService.executeExistingRun(event);
        }, () -> log.warn("Skip missing run event, runId={}", event.runId()));
    }
}
