package com.gcll.ticketagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * callName → Resilience4j 装饰器实例映射。
 * <p>配置前缀 {@code opsmind.resilience.call-mappings}，每条映射声明该 callName 启用哪些
 * retry / timelimiter / circuitbreaker 实例（实例本身的参数由 {@code resilience4j.*} 配置定义）。
 * <p>支持三级匹配，避免新增调用点遗漏配置而裸奔：
 * <ol>
 *   <li>精确匹配：callMappings 中存在以 callName 为 key 的条目（用于给特定调用单独配策略）。</li>
 *   <li>前缀兜底：callMappings 中以 {@code *} 结尾的 key（如 {@code llm.*}）作为前缀规则，
 *       callName 命中其前缀即继承该策略。多条前缀规则时最长前缀优先。</li>
 *   <li>无匹配：返回 {@link CallDecorators#empty()}（plain 路径，无治理）。</li>
 * </ol>
 */
@Component
@ConfigurationProperties(prefix = "opsmind.resilience")
public class CallRegistry {

    /** 精确 key → 映射；不以 * 结尾的 key 视为精确 key。 */
    private Map<String, CallMapping> callMappings;

    /** 前缀规则索引：以 * 结尾的 key 去掉 * 得到前缀，按前缀长度降序（最长优先匹配）。 */
    private List<PrefixEntry> prefixRules = List.of();

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
        this.prefixRules = buildPrefixRules(callMappings);
    }

    /**
     * 返回 callName 对应的装饰器集合。匹配优先级：精确 key > 最长前缀规则 > empty。
     */
    public CallDecorators get(String callName) {
        CallMapping exact = (callMappings == null) ? null : callMappings.get(callName);
        if (exact != null) {
            return decorate(exact);
        }
        for (PrefixEntry rule : prefixRules) {
            if (callName.startsWith(rule.prefix)) {
                return decorate(rule.mapping);
            }
        }
        return CallDecorators.empty();
    }

    private CallDecorators decorate(CallMapping m) {
        return new CallDecorators(
                m.retry != null ? retryRegistry.retry(m.retry) : null,
                m.timelimiter != null ? timeLimiterRegistry.timeLimiter(m.timelimiter) : null,
                m.circuitbreaker != null ? circuitBreakerRegistry.circuitBreaker(m.circuitbreaker) : null
        );
    }

    /** 扫描 callMappings，把以 * 结尾的 key 拆成前缀规则，按前缀长度降序排列。 */
    private static List<PrefixEntry> buildPrefixRules(Map<String, CallMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        List<PrefixEntry> rules = new ArrayList<>();
        for (Map.Entry<String, CallMapping> entry : mappings.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.endsWith("*")) {
                rules.add(new PrefixEntry(key.substring(0, key.length() - 1), entry.getValue()));
            }
        }
        rules.sort(Comparator.comparingInt((PrefixEntry e) -> e.prefix.length()).reversed());
        return List.copyOf(rules);
    }

    private record PrefixEntry(String prefix, CallMapping mapping) {
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
