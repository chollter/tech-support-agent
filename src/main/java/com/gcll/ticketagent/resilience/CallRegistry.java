package com.gcll.ticketagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * callName → Resilience4j 装饰器实例映射。
 * <p>配置前缀 {@code opsmind.resilience.call-mappings}，每条映射声明该 callName 启用哪些
 * retry / timelimiter / circuitbreaker 实例（实例本身的参数由 {@code resilience4j.*} 配置定义）。
 */
@Component
@ConfigurationProperties(prefix = "opsmind.resilience")
public class CallRegistry {

    /** callName → {retry, timelimiter, circuitbreaker} 实例名映射。 */
    private Map<String, CallMapping> callMappings;

    private final RetryRegistry retryRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CallRegistry(RetryRegistry retryRegistry,
                        TimeLimiterRegistry timeLimiterRegistry,
                        CircuitBreakerRegistry circuitBreakerRegistry) {
        this.retryRegistry = retryRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public Map<String, CallMapping> getCallMappings() {
        return callMappings;
    }

    public void setCallMappings(Map<String, CallMapping> callMappings) {
        this.callMappings = callMappings;
    }

    /**
     * 返回 callName 对应的装饰器集合。未配置的 callName 返回 {@link CallDecorators#empty()}（无治理）。
     */
    public CallDecorators get(String callName) {
        if (callMappings == null || !callMappings.containsKey(callName)) {
            return CallDecorators.empty();
        }
        CallMapping m = callMappings.get(callName);
        return new CallDecorators(
                m.retry != null ? retryRegistry.retry(m.retry) : null,
                m.timelimiter != null ? timeLimiterRegistry.timeLimiter(m.timelimiter) : null,
                m.circuitbreaker != null ? circuitBreakerRegistry.circuitBreaker(m.circuitbreaker) : null
        );
    }

    public static class CallMapping {
        private String retry;
        private String timelimiter;
        private String circuitbreaker;

        public String getRetry() {
            return retry;
        }

        public void setRetry(String retry) {
            this.retry = retry;
        }

        public String getTimelimiter() {
            return timelimiter;
        }

        public void setTimelimiter(String timelimiter) {
            this.timelimiter = timelimiter;
        }

        public String getCircuitbreaker() {
            return circuitbreaker;
        }

        public void setCircuitbreaker(String circuitbreaker) {
            this.circuitbreaker = circuitbreaker;
        }
    }
}
