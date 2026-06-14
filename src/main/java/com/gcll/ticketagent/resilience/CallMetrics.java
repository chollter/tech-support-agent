package com.gcll.ticketagent.resilience;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 外部调用统一埋点（Micrometer）。所有指标以 {@code callName} 作为 tag，便于按调用维度聚合。
 * <ul>
 *   <li>{@code external_call_duration} —— Timer，按 callName + success 维度记录耗时</li>
 *   <li>{@code external_call_attempts} —— DistributionSummary，单次调用的尝试次数</li>
 *   <li>{@code external_call_success_total / failure_total} —— Counter</li>
 *   <li>{@code external_call_circuit_open_total} —— 熔断打开快速失败计数</li>
 *   <li>{@code ai_token_usage} —— LLM token 用量（prompt / completion）</li>
 * </ul>
 */
@Component
public class CallMetrics {

    private final MeterRegistry registry;

    public CallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(String callName, long durationMs, int attempts) {
        Timer.builder("external_call_duration")
                .tag("callName", callName).tag("success", "true")
                .register(registry).record(Duration.ofMillis(durationMs));
        DistributionSummary.builder("external_call_attempts")
                .tag("callName", callName)
                .register(registry).record(attempts);
        registry.counter("external_call_success_total", "callName", callName).increment();
    }

    public void recordFailure(String callName, long durationMs, int attempts, String errorType) {
        Timer.builder("external_call_duration")
                .tag("callName", callName).tag("success", "false")
                .register(registry).record(Duration.ofMillis(durationMs));
        registry.counter("external_call_failure_total",
                "callName", callName, "errorType", errorType).increment();
    }

    public void recordCircuitOpen(String callName, long durationMs) {
        registry.counter("external_call_circuit_open_total", "callName", callName).increment();
    }

    public void recordTokenUsage(String callName, int promptTokens, int completionTokens) {
        DistributionSummary.builder("ai_token_usage")
                .tag("callName", callName).tag("type", "prompt")
                .register(registry).record(promptTokens);
        DistributionSummary.builder("ai_token_usage")
                .tag("callName", callName).tag("type", "completion")
                .register(registry).record(completionTokens);
    }
}
