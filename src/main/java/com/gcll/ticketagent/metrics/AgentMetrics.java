package com.gcll.ticketagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 业务指标。LLM 调用耗时 / token 已迁移到 {@link com.gcll.ticketagent.resilience.CallMetrics}
 * （external_call_duration / ai_token_usage），本类只保留 RAG 命中计数。
 */
@Component
public class AgentMetrics {

    private final Counter ragHitCounter;

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.ragHitCounter = Counter.builder("agent.rag.hit.count").register(meterRegistry);
    }

    public void recordRagHit() {
        ragHitCounter.increment();
    }
}
