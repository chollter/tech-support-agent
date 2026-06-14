package com.gcll.ticketagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Agent 业务指标。LLM 调用耗时 / token 已迁移到 {@link com.gcll.ticketagent.resilience.CallMetrics}
 * （external_call_duration / ai_token_usage），本类只保留 RAG 命中与步骤耗时。
 */
@Component
public class AgentMetrics {

    private final Counter ragHitCounter;
    private final Timer stepTimer;

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.ragHitCounter = Counter.builder("agent.rag.hit.count").register(meterRegistry);
        this.stepTimer = Timer.builder("agent.step.duration").register(meterRegistry);
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
}
