package com.gcll.ticketagent.async;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "opsmind.async", name = "enabled", havingValue = "true")
public class AgentRunEventPublisher {

    private final KafkaTemplate<String, AgentRunExecutionEvent> kafkaTemplate;
    private final AsyncAgentRunProperties properties;

    public AgentRunEventPublisher(
            KafkaTemplate<String, AgentRunExecutionEvent> kafkaTemplate,
            AsyncAgentRunProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    public void publish(AgentRunExecutionEvent event) {
        kafkaTemplate.send(properties.getTopic(), event.runId(), event);
    }
}
