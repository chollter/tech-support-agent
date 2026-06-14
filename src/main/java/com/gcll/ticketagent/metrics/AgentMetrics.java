package com.gcll.ticketagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class AgentMetrics {

    private final Counter ragHitCounter;
    private final Timer stepTimer;
    private final Timer llmTimer;

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.ragHitCounter = Counter.builder("agent.rag.hit.count").register(meterRegistry);
        this.stepTimer = Timer.builder("agent.step.duration").register(meterRegistry);
        this.llmTimer = Timer.builder("agent.llm.call.duration").register(meterRegistry);
    }

    public void recordRagHit() {
        ragHitCounter.increment();
    }

    public Timer.Sample startStepTimer() {
        return Timer.start();
    }

    public void recordStep(Timer.Sample sample) {
        sample.stop(stepTimer);
    }

    public void recordLlm(long durationMs) {
        llmTimer.record(java.time.Duration.ofMillis(durationMs));
    }
}
