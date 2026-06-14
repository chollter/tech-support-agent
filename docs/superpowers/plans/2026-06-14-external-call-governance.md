# External Call Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把散落各处的重试/超时/熔断治理收敛到统一的 `ExternalCallGateway`，修复 LLM 调用 P0 硬伤（线程泄漏/异常不分类/token 丢失）。

**Architecture:** 新增 `resilience/` 包，提供 `ExternalCallGateway.execute(callName, supplier)` 统一入口，底层用 Resilience4j（Retry + TimeLimiter + CircuitBreaker）组合装饰器，配置驱动。执行器层（LlmGateway / KnowledgeSearch / ToolGateway）收窄为纯执行，治理委托 Gateway。

**Tech Stack:** Java 21 · Spring Boot 3.4.1 · Spring AI 1.0.0 · Resilience4j 2.2.0 · Micrometer

**Spec:** [docs/superpowers/specs/2026-06-14-external-call-governance-design.md](../specs/2026-06-14-external-call-governance-design.md)

---

## 阅读约定

- **核心类**（Task 2/3/6/7/9）：给完整代码，零上下文工程师照写即可。
- **重复迁移**（Task 10 的 9 个 service）：给 1 个完整代表例 + 差异 checklist，复用同一模式（这些改造结构完全相同，逐个贴完整代码是冗余，不 DRY）。
- **每个 Task 结尾 commit**，commit message 用 `feat:` / `refactor:` 前缀。
- **跑测试命令**统一：`./mvnw test -Dtest=<类名>`（Windows 用 `mvnw.cmd`）。

---

## File Structure

| 文件 | 职责 | 动作 |
|------|------|------|
| `pom.xml` | 加 Resilience4j 依赖 | Modify |
| `resilience/RetryableCallException.java` | 可重试异常 | Create |
| `resilience/NonRetryableCallException.java` | 不可重试异常 | Create |
| `resilience/CallResult.java` | 调用结果 record | Create |
| `resilience/LlmResponse.java` | LLM 响应（content + token） | Create |
| `resilience/CallMetrics.java` | Micrometer 统一埋点 | Create |
| `resilience/CallDecorators.java` | 单个 callName 的装饰器集合 | Create |
| `resilience/CallRegistry.java` | callName → CallDecorators 映射 | Create |
| `resilience/ResilienceConfig.java` | 注册 Registry + executor bean | Create |
| `resilience/ExternalCallGateway.java` | 统一入口（核心） | Create |
| `config/RestClientConfig.java` | DashScope RestClient timeout | Create |
| `llm/LlmGateway.java` | 拆 invoke + token 解析 + 删治理 | Modify |
| `llm/LlmProperties.java` | 废弃 timeout/maxRetries | Modify |
| `extract/SpringAiTicketExtractService.java` + 8 个同类 | 改走 Gateway | Modify |
| `agent/AgentRuntime.java` | searchKnowledgeSafely 改造 + 删 recordLlm | Modify |
| `execution/evidence/EvidenceCollectionService.java` | 工具调用走 Gateway | Modify |
| `application.yml` | Resilience4j 配置 + 废弃 llm.* timeout | Modify |
| `test/.../ExternalCallGatewayTest.java` | Gateway 单测 | Create |
| `test/.../LlmGatewayRetryTest.java` | 适配新 API | Modify |
| `docs/KNOWN-ISSUES.md` | 已知限制 | Create |

---

## Task 1: 引入 Resilience4j 依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 加依赖**

在 `pom.xml` 的 `<dependencies>` 内（建议放在 actuator 依赖之后）追加：

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
```

- [ ] **Step 2: 验证依赖解析**

Run: `./mvnw dependency:resolve -q`
Expected: BUILD SUCCESS，无 "Could not resolve" 错误。

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat: add resilience4j-spring-boot3 dependency"
```

---

## Task 2: 异常类型（Retryable / NonRetryable）

**Files:**
- Create: `src/main/java/com/gcll/ticketagent/resilience/RetryableCallException.java`
- Create: `src/main/java/com/gcll/ticketagent/resilience/NonRetryableCallException.java`

- [ ] **Step 1: 创建 RetryableCallException**

```java
package com.gcll.ticketagent.resilience;

/** 可重试异常：5xx / 429 / 超时 / 网络错误。Resilience4j retry 配置 retryExceptions 包含本类。 */
public class RetryableCallException extends RuntimeException {
    public RetryableCallException(String message) {
        super(message);
    }
    public RetryableCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: 创建 NonRetryableCallException**

```java
package com.gcll.ticketagent.resilience;

/** 不可重试异常：4xx / 鉴权失败 / prompt 加载失败 / 确定性错误。Resilience4j retry 配置 ignoreExceptions 包含本类。 */
public class NonRetryableCallException extends RuntimeException {
    public NonRetryableCallException(String message) {
        super(message);
    }
    public NonRetryableCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/resilience/
git commit -m "feat: add retryable/non-retryable call exceptions"
```

---

## Task 3: CallResult 与 LlmResponse record

**Files:**
- Create: `src/main/java/com/gcll/ticketagent/resilience/CallResult.java`
- Create: `src/main/java/com/gcll/ticketagent/resilience/LlmResponse.java`

- [ ] **Step 1: 创建 CallResult**

```java
package com.gcll.ticketagent.resilience;

public record CallResult<T>(
        boolean success,
        T value,
        Throwable error,
        int attempts,
        long durationMs,
        boolean circuitOpen
) {
    public static <T> CallResult<T> ok(T value, int attempts, long durationMs) {
        return new CallResult<>(true, value, null, attempts, durationMs, false);
    }

    public static <T> CallResult<T> fail(Throwable error, int attempts, long durationMs) {
        return new CallResult<>(false, null, error, attempts, durationMs, false);
    }

    public static <T> CallResult<T> circuitOpen(long durationMs) {
        return new CallResult<>(false, null, null, 0, durationMs, true);
    }
}
```

- [ ] **Step 2: 创建 LlmResponse**

```java
package com.gcll.ticketagent.resilience;

/** LLM 执行结果：内容 + token 用量。token 缺失时记 0。 */
public record LlmResponse(
        String content,
        int promptTokens,
        int completionTokens
) {
    public static LlmResponse of(String content, int promptTokens, int completionTokens) {
        return new LlmResponse(content, promptTokens, completionTokens);
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/resilience/CallResult.java src/main/java/com/gcll/ticketagent/resilience/LlmResponse.java
git commit -m "feat: add CallResult and LlmResponse records"
```

---

## Task 4: CallMetrics（统一埋点）

**Files:**
- Create: `src/main/java/com/gcll/ticketagent/resilience/CallMetrics.java`

- [ ] **Step 1: 创建 CallMetrics**

```java
package com.gcll.ticketagent.resilience;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class CallMetrics {

    private final MeterRegistry registry;

    public CallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(String callName, long durationMs, int attempts) {
        Timer.builder("external_call_duration").tag("callName", callName).tag("success", "true")
                .register(registry).record(java.time.Duration.ofMillis(durationMs));
        DistributionSummary.builder("external_call_attempts").tag("callName", callName)
                .register(registry).record(attempts);
        registry.counter("external_call_success_total", "callName", callName).increment();
    }

    public void recordFailure(String callName, long durationMs, int attempts, String errorType) {
        Timer.builder("external_call_duration").tag("callName", callName).tag("success", "false")
                .register(registry).record(java.time.Duration.ofMillis(durationMs));
        registry.counter("external_call_failure_total", "callName", callName, "errorType", errorType).increment();
    }

    public void recordCircuitOpen(String callName, long durationMs) {
        registry.counter("external_call_circuit_open_total", "callName", callName).increment();
    }

    public void recordTokenUsage(String callName, int promptTokens, int completionTokens) {
        DistributionSummary.builder("ai_token_usage").tag("callName", callName).tag("type", "prompt")
                .register(registry).record(promptTokens);
        DistributionSummary.builder("ai_token_usage").tag("callName", callName).tag("type", "completion")
                .register(registry).record(completionTokens);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/resilience/CallMetrics.java
git commit -m "feat: add CallMetrics for unified external call observability"
```

---

## Task 5: CallDecorators 与 CallRegistry

**Files:**
- Create: `src/main/java/com/gcll/ticketagent/resilience/CallDecorators.java`
- Create: `src/main/java/com/gcll/ticketagent/resilience/CallRegistry.java`
- Create: `src/main/java/com/gcll/ticketagent/resilience/ResilienceConfig.java`

- [ ] **Step 1: 创建 CallDecorators**

```java
package com.gcll.ticketagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;

/** 单个 callName 关联的 Resilience4j 装饰器集合（任一可为 null = 不启用该治理）。 */
public record CallDecorators(
        Retry retry,
        TimeLimiter timeLimiter,
        CircuitBreaker circuitBreaker
) {
    public static CallDecorators empty() {
        return new CallDecorators(null, null, null);
    }
}
```

- [ ] **Step 2: 创建 CallRegistry**

```java
package com.gcll.ticketagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

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

    public Map<String, CallMapping> getCallMappings() { return callMappings; }
    public void setCallMappings(Map<String, CallMapping> callMappings) { this.callMappings = callMappings; }

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
        public String getRetry() { return retry; }
        public void setRetry(String retry) { this.retry = retry; }
        public String getTimelimiter() { return timelimiter; }
        public void setTimelimiter(String timelimiter) { this.timelimiter = timelimiter; }
        public String getCircuitbreaker() { return circuitbreaker; }
        public void setCircuitbreaker(String circuitbreaker) { this.circuitbreaker = circuitbreaker; }
    }
}
```

- [ ] **Step 3: 创建 ResilienceConfig（executor bean）**

```java
package com.gcll.ticketagent.resilience;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ResilienceConfig {

    /**
     * TimeLimiter 调度超时检查用的 scheduler。复用现有 llmCallExecutor 做实际阻塞调用，
     * 这个 scheduler 只负责 TimeLimiter 的超时定时（轻量）。
     */
    @Bean(name = "timeLimiterScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService timeLimiterScheduler() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setThreadNamePrefix("tl-sched-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor.getScheduledExecutor();
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/resilience/CallDecorators.java \
        src/main/java/com/gcll/ticketagent/resilience/CallRegistry.java \
        src/main/java/com/gcll/ticketagent/resilience/ResilienceConfig.java
git commit -m "feat: add CallRegistry mapping callName to resilience4j decorators"
```

---

## Task 6: ExternalCallGateway（核心，TDD）

**Files:**
- Create: `src/main/java/com/gcll/ticketagent/resilience/ExternalCallGateway.java`
- Test: `src/test/java/com/gcll/ticketagent/resilience/ExternalCallGatewayTest.java`

- [ ] **Step 1: 写失败测试——正常调用**

```java
package com.gcll.ticketagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExternalCallGatewayTest {

    private ExternalCallGateway gateway;
    private CallMetrics callMetrics;

    @BeforeEach
    void setUp() {
        RetryRegistry retryRegistry = RetryRegistry.ofDefaults();
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.ofDefaults();
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.ofDefaults();
        CallRegistry callRegistry = new CallRegistry(retryRegistry, tlRegistry, cbRegistry);
        // 无 callMappings → 所有调用走 plain 路径
        callMetrics = mock(CallMetrics.class);
        gateway = new ExternalCallGateway(callRegistry, callMetrics, Executors.newScheduledThreadPool(2));
    }

    @Test
    void executeReturnsOkOnSuccess() {
        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = gateway.execute("test.no-strategy", () -> {
            counter.incrementAndGet();
            return "ok";
        });
        assertThat(result.success()).isTrue();
        assertThat(result.value()).isEqualTo("ok");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行测试确认失败（类不存在）**

Run: `./mvnw test -Dtest=ExternalCallGatewayTest -q`
Expected: 编译错误，`ExternalCallGateway` 不存在。

- [ ] **Step 3: 实现 ExternalCallGateway**

```java
package com.gcll.ticketagent.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

@Service
public class ExternalCallGateway {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallGateway.class);

    private final CallRegistry registry;
    private final CallMetrics metrics;
    private final ScheduledExecutorService timeLimiterScheduler;

    public ExternalCallGateway(CallRegistry registry,
                               CallMetrics metrics,
                               @Qualifier("timeLimiterScheduler") ScheduledExecutorService timeLimiterScheduler) {
        this.registry = registry;
        this.metrics = metrics;
        this.timeLimiterScheduler = timeLimiterScheduler;
    }

    public <T> CallResult<T> execute(String callName, Supplier<T> call) {
        CallDecorators d = registry.get(callName);
        long start = System.currentTimeMillis();
        if (d.retry() == null && d.timeLimiter() == null && d.circuitBreaker() == null) {
            return executePlain(callName, call, start);
        }
        try {
            T value = executeDecorated(d, call);
            long duration = System.currentTimeMillis() - start;
            metrics.recordSuccess(callName, duration, attemptsOf(d));
            return CallResult.ok(value, attemptsOf(d), duration);
        } catch (CallNotPermittedException ex) {
            long duration = System.currentTimeMillis() - start;
            metrics.recordCircuitOpen(callName, duration);
            log.warn("external call circuit open, callName={}, durationMs={}", callName, duration);
            return CallResult.circuitOpen(duration);
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - start;
            metrics.recordFailure(callName, duration, attemptsOf(d), ex.getClass().getSimpleName());
            log.warn("external call failed, callName={}, attempts={}, durationMs={}, error={}",
                    callName, attemptsOf(d), duration, rootMessage(ex));
            return CallResult.fail(ex, attemptsOf(d), duration);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T executeDecorated(CallDecorators d, Supplier<T> call) {
        // 顺序（外→内）：CircuitBreaker → Retry → TimeLimiter → 实际调用
        // 用 CompletionStage 组合 Retry + TimeLimiter
        Supplier<CompletionStage<T>> stageSupplier = () -> CompletableFuture.supplyAsync(call);
        if (d.retry() != null) {
            stageSupplier = Retry.decorateCompletionStage(d.retry(), timeLimiterScheduler, stageSupplier);
        }
        if (d.timeLimiter() != null) {
            TimeLimiter tl = d.timeLimiter();
            final Supplier<CompletionStage<T>> inner = stageSupplier;
            stageSupplier = () -> tl.executeCompletionStage(timeLimiterScheduler, inner);
        }
        CompletionStage<T> stage = stageSupplier.get();
        if (d.circuitBreaker() != null) {
            stage = d.circuitBreaker().executeCompletionStage(timeLimiterScheduler, stageSupplier);
        }
        return stage.toCompletableFuture().join();
    }

    private <T> CallResult<T> executePlain(String callName, Supplier<T> call, long start) {
        try {
            T value = call.get();
            long duration = System.currentTimeMillis() - start;
            metrics.recordSuccess(callName, duration, 1);
            return CallResult.ok(value, 1, duration);
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - start;
            metrics.recordFailure(callName, duration, 1, ex.getClass().getSimpleName());
            log.warn("external call failed (plain), callName={}, durationMs={}, error={}",
                    callName, duration, rootMessage(ex));
            return CallResult.fail(ex, 1, duration);
        }
    }

    private int attemptsOf(CallDecorators d) {
        return d.retry() != null ? d.retry().getMetrics().getNumberOfFailedCallsWithRetryAttempt() + 1 : 1;
    }

    private String rootMessage(Throwable ex) {
        Throwable cause = ex.getCause();
        return cause == null ? ex.getMessage() : cause.getMessage();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw test -Dtest=ExternalCallGatewayTest -q`
Expected: PASS。

- [ ] **Step 5: 补充测试——不可重试异常立即失败**

追加到 `ExternalCallGatewayTest`：

```java
@Test
void executeReturnsFailOnException() {
    CallResult<String> result = gateway.execute("test.no-strategy", () -> {
        throw new NonRetryableCallException("bad request");
    });
    assertThat(result.success()).isFalse();
    assertThat(result.error()).isInstanceOf(NonRetryableCallException.class);
    assertThat(result.attempts()).isEqualTo(1);
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./mvnw test -Dtest=ExternalCallGatewayTest -q`
Expected: 两个测试都 PASS。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/resilience/ExternalCallGateway.java \
        src/test/java/com/gcll/ticketagent/resilience/ExternalCallGatewayTest.java
git commit -m "feat: add ExternalCallGateway as unified external call entry with retry/timeout/circuit-breaker"
```

> **执行注意**：`attemptsOf` 的精确实现依赖 Resilience4j Retry.Metrics 语义。Step 7 后如 attempts 计数不准，调整为在 `executeDecorated` 前后用 `Retry.Context` 手动计数。这是已知实现细节，执行时验证。

---

## Task 7: application.yml 配置

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 追加 Resilience4j 配置**

在 `application.yml` 末尾追加（保留现有内容）：

```yaml
resilience4j:
  retry:
    instances:
      llm-default:
        maxAttempts: 3
        waitDuration: 2s
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
        exponentialMaxWaitDuration: 8s
        retryExceptions:
          - com.gcll.ticketagent.resilience.RetryableCallException
        ignoreExceptions:
          - com.gcll.ticketagent.resilience.NonRetryableCallException
      vector-default:
        maxAttempts: 2
        waitDuration: 500ms
        retryExceptions:
          - com.gcll.ticketagent.resilience.RetryableCallException
        ignoreExceptions:
          - com.gcll.ticketagent.resilience.NonRetryableCallException
      tool-default:
        maxAttempts: 1
  timelimiter:
    instances:
      llm-default: { timeoutDuration: 60s }
      vector-default: { timeoutDuration: 5s }
      tool-default: { timeoutDuration: 10s }
  circuitbreaker:
    instances:
      llm-default:
        failureRateThreshold: 50
        slowCallRateThreshold: 80
        slowCallDurationThreshold: 45s
        waitDurationInOpenState: 30s
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
      vector-default:
        failureRateThreshold: 60
        waitDurationInOpenState: 15s
        slidingWindowSize: 10

opsmind:
  resilience:
    call-mappings:
      llm.ticket-extract:      { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.info-gap:            { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.agent-plan:          { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.tool-select:         { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.root-cause:          { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.routing-suggest:     { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.suggestion:          { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.follow-up:           { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      vector.knowledge-search: { retry: vector-default, timelimiter: vector-default, circuitbreaker: vector-default }
      tool.query-logs:         { retry: tool-default, timelimiter: tool-default }
      tool.query-metric:       { retry: tool-default, timelimiter: tool-default }
      tool.similar-cases:      { retry: tool-default, timelimiter: tool-default }
```

- [ ] **Step 2: 废弃旧 llm.* timeout（保留键但标注废弃，避免启动报错）**

将 `application.yml` 中：

```yaml
llm:
  connect-timeout-ms: 10000
  read-timeout-ms: 60000
  max-retries: 2
```

改为：

```yaml
llm:
  # connect-timeout-ms / read-timeout-ms / max-retries 已废弃
  # 治理改由 resilience4j + RestClient（见 docs/superpowers/specs/2026-06-14-external-call-governance-design.md）
```

- [ ] **Step 3: 启动验证（配置解析无误）**

Run: `./mvnw test -Dtest=ExternalCallGatewayTest -q`
Expected: PASS（配置加载不报错）。

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat: add resilience4j configuration and deprecate legacy llm timeout config"
```

---

## Task 8: HTTP 层 Timeout 配置

**Files:**
- Create: `src/main/java/com/gcll/ticketagent/config/RestClientConfig.java`

- [ ] **Step 1: 创建 RestClientConfig**

```java
package com.gcll.ticketagent.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 给 Spring AI / DashScope 使用的 RestClient 设置连接与读取超时。
 * 这是治线程泄漏之本——socket 层超时，比应用层 future.cancel 可靠。
 * connectTimeout 10s（建连），readTimeout 60s（等响应，等于业务层 LLM 上限）。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return restClientBuilder -> restClientBuilder
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    {
                        setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
                        setReadTimeout((int) Duration.ofSeconds(60).toMillis());
                    }
                });
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 启动验证（Bean 装配无误）**

Run: `./mvnw test -Dtest=ExternalCallGatewayTest -q`
Expected: PASS（Spring 上下文加载无 RestClientCustomizer 冲突）。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/config/RestClientConfig.java
git commit -m "feat: configure RestClient connect/read timeout to fix thread leak at socket level"
```

> **执行注意**：spring-ai-alibaba 1.0.0-M6.1 的 DashScope autoconfig 是否消费 `RestClientCustomizer` 需启动时验证。若 DashScope 未走 customize 的 builder（启动后 LLM 调用仍无 timeout），改用 `@Bean RestClient.Builder` 直接覆盖，或升级 spring-ai-alibaba 版本。这是已知风险点，写入 KNOWN-ISSUES。

---

## Task 9: LlmGateway 改造（拆 invoke + token 解析 + 删治理）

**Files:**
- Modify: `src/main/java/com/gcll/ticketagent/llm/LlmGateway.java`
- Modify: `src/main/java/com/gcll/ticketagent/llm/LlmProperties.java`

- [ ] **Step 1: 改造 LlmGateway——删除手写治理，拆出 invoke，加 token 解析**

将 `LlmGateway.java` 完整替换为：

```java
package com.gcll.ticketagent.llm;

import com.gcll.ticketagent.resilience.LlmResponse;
import com.gcll.ticketagent.resilience.NonRetryableCallException;
import com.gcll.ticketagent.resilience.RetryableCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
@ConditionalOnBean(ChatClient.Builder.class)
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final ChatClient chatClient;
    private final String systemBasePrompt;

    public LlmGateway(ChatClient.Builder chatClientBuilder) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.systemBasePrompt = new ClassPathResource("prompts/system-base.txt")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 纯执行器：调一次 LLM，返回内容 + token。不含任何治理逻辑（重试/超时/熔断）。
     * 治理由调用方经 ExternalCallGateway 包装。
     * 底层异常翻译为 RetryableCallException / NonRetryableCallException。
     */
    public LlmResponse invoke(String promptFile, String userContent) {
        try {
            String promptTemplate = loadPrompt(promptFile);
            ChatResponse chatResponse = chatClient.prompt()
                    .system(systemBasePrompt)
                    .user(promptTemplate + "\n\n工单内容：\n" + userContent)
                    .call()
                    .chatResponse();
            String content = chatResponse.getResult().getOutput().getText();
            Usage usage = chatResponse.getMetadata().getUsage();
            int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : 0;
            int completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens().intValue() : 0;
            return LlmResponse.of(content, promptTokens, completionTokens);
        } catch (IOException ex) {
            // prompt 文件加载失败 = 确定性错误，不可重试
            throw new NonRetryableCallException("Failed to load prompt: " + promptFile, ex);
        } catch (NonRetryableCallException | RetryableCallException ex) {
            throw ex; // 已分类，透传
        } catch (Exception ex) {
            // 其他异常默认按可重试处理（网络抖动、5xx、超时等）
            // 执行时如有更精确的 4xx 判断（如 HttpClientErrorException），在此细分
            log.debug("LLM invoke failed, will be classified by gateway retry policy, error={}", ex.getMessage());
            throw new RetryableCallException("LLM call failed", ex);
        }
    }

    private String loadPrompt(String promptFile) throws IOException {
        return new ClassPathResource("prompts/" + promptFile).getContentAsString(StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: 简化 LlmProperties（废弃 timeout/maxRetries 字段）**

将 `LlmProperties.java` 替换为：

```java
package com.gcll.ticketagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 相关配置。
 * 注：connectTimeoutMs / readTimeoutMs / maxRetries 已废弃，治理改由
 * resilience4j + RestClient 负责。保留空类仅为兼容已有引用，执行时若无引用可直接删除。
 */
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
}
```

> **执行注意**：若编译报 `LlmProperties` 的 getter 调用点（如 `getReadTimeoutMs`），搜索并删除那些调用（它们在旧 LlmGateway 里，已被 Step 1 删除；若其他类引用则一并清理）。

- [ ] **Step 3: 编译验证并修复引用**

Run: `./mvnw compile -q`
Expected: 若有 `LlmProperties.getXxx` / `llmGateway.call(` 的残留引用，逐一删除/替换。直到 BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/llm/
git commit -m "refactor: narrow LlmGateway to pure executor with token parsing; remove hand-written retry/timeout"
```

---

## Task 10: 迁移 9 个 SpringAi Service 走 Gateway

**Files（同模式改造）:**
1. `extract/SpringAiTicketExtractService.java`
2. `understanding/gap/SpringAiInfoGapAnalysisService.java`
3. `agent/planner/SpringAiAgentPlanner.java`
4. `execution/tool/SpringAiToolSelector.java`
5. `governance/routing/SpringAiRoutingSuggestionService.java`
6. `suggestion/SpringAiSuggestionGenerationService.java`
7. `analysis/LlmRootCauseAnalysisService.java`
8. `understanding/followup/LlmFollowUpProvider.java`

**代表例：`SpringAiTicketExtractService` 完整改造**（其余 7 个同模式）

- [ ] **Step 1: 改造 SpringAiTicketExtractService**

替换构造器注入 + `extract` 方法中的 LLM 调用段。完整新文件：

```java
package com.gcll.ticketagent.extract;

import com.gcll.ticketagent.llm.LlmGateway;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.resilience.LlmResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
public class SpringAiTicketExtractService implements TicketExtractService {

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final ObjectProvider<ExternalCallGateway> gatewayProvider;
    private final StructuredOutputParser parser;
    private final RuleBasedTicketExtractService fallback;

    public SpringAiTicketExtractService(
            ObjectProvider<LlmGateway> llmGatewayProvider,
            ObjectProvider<ExternalCallGateway> gatewayProvider,
            StructuredOutputParser parser,
            RuleBasedTicketExtractService fallback
    ) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.gatewayProvider = gatewayProvider;
        this.parser = parser;
        this.fallback = fallback;
    }

    @Override
    public StepOutcome<TicketExtractResult> extract(String content) {
        StepOutcome<TicketExtractResult> ruleOutcome = fallback.extract(content);
        if (ruleOutcome.value().issueType() == IssueType.CONSULT) {
            return ruleOutcome;
        }
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null || gatewayProvider.getIfAvailable() == null) {
            return fallback.extract(content);
        }
        ExternalCallGateway gateway = gatewayProvider.getObject();
        CallResult<LlmResponse> result = gateway.execute(
                "llm.ticket-extract",
                () -> llmGateway.invoke("ticket-extract.txt", content)
        );
        if (!result.success()) {
            return fallback.extract(content);
        }
        try {
            ExtractJson json = parser.parse(result.value().content(), ExtractJson.class);
            TicketExtractResult extracted = new TicketExtractResult(
                    IssueType.valueOf(json.issueType()),
                    json.affectedSystem(), json.affectedModule(), json.apiName(),
                    json.errorCode(), json.errorMessage(), json.environment(),
                    json.impactScope(), json.timeRange(), json.businessImpact(),
                    json.severitySignals() == null ? List.of() : json.severitySignals(),
                    json.confidence()
            );
            return StepOutcome.llm(extracted, result.durationMs());
        } catch (Exception ex) {
            return fallback.extract(content);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExtractJson(
            String issueType, String affectedSystem, String affectedModule, String apiName,
            String errorCode, String errorMessage, String environment, String impactScope,
            String timeRange, String businessImpact, List<String> severitySignals, double confidence
    ) {}
}
```

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 对其余 7 个 Service 重复同一模式**

每个 Service 改造的**差异点**（其余结构完全相同）：

| Service | callName | promptFile | 返回类型 |
|---------|----------|------------|----------|
| SpringAiInfoGapAnalysisService | `llm.info-gap` | `info-gap-analysis.txt` | InfoGapAnalysis |
| SpringAiAgentPlanner | `llm.agent-plan` | `agent-plan.txt` | AgentPlan |
| SpringAiToolSelector | `llm.tool-select` | `tool-select.txt` | ToolSelection |
| SpringAiRoutingSuggestionService | `llm.routing-suggest` | `routing-suggest.txt` | RoutingSuggestion |
| SpringAiSuggestionGenerationService | `llm.suggestion` | `suggestion-generate.txt` | TicketSuggestion |
| LlmRootCauseAnalysisService | `llm.root-cause` | `root-cause-analysis.txt` | RootCauseResult |
| LlmFollowUpProvider | `llm.follow-up` | `follow-up-generate.txt` | List<String> |

改造模式（每个都这么做）：
1. 构造器加 `ObjectProvider<ExternalCallGateway> gatewayProvider`。
2. 把原 `llmGateway.call(promptFile, content)` 替换为：
   ```java
   CallResult<LlmResponse> result = gatewayProvider.getObject().execute(
       "<callName>", () -> llmGateway.invoke("<promptFile>", content));
   if (!result.success()) { /* 走原降级逻辑 */ }
   String content0 = result.value().content();
   // 后续用 content0 解析，costMs 用 result.durationMs()
   ```
3. 保留原有 `ObjectProvider<LlmGateway>` 和降级 fallback 逻辑不变。

- [ ] **Step 4: 编译验证全部 8 个**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/extract/ \
        src/main/java/com/gcll/ticketagent/understanding/ \
        src/main/java/com/gcll/ticketagent/agent/planner/ \
        src/main/java/com/gcll/ticketagent/execution/tool/ \
        src/main/java/com/gcll/ticketagent/governance/routing/ \
        src/main/java/com/gcll/ticketagent/suggestion/ \
        src/main/java/com/gcll/ticketagent/analysis/
git commit -m "refactor: route all LLM calls through ExternalCallGateway"
```

---

## Task 11: AgentRuntime 改造（向量检索走 Gateway + 删 recordLlm）

**Files:**
- Modify: `src/main/java/com/gcll/ticketagent/agent/AgentRuntime.java`

- [ ] **Step 1: 改造 searchKnowledgeSafely 走 Gateway**

在 `AgentRuntime` 构造器加 `ExternalCallGateway` 依赖（注入字段），然后把 `searchKnowledgeSafely` 方法替换为：

```java
private KnowledgeSearchOutcome searchKnowledgeSafely(AgentRun run, TicketDraft draft, TicketExtractResult extract) {
    CallResult<List<KnowledgeHit>> result = externalCallGateway.execute(
            "vector.knowledge-search",
            () -> knowledgeSearchService.search(
                    draft.fullContent(),
                    extract.affectedSystem(),
                    extract.affectedModule(),
                    extract.issueType().name()
            )
    );
    if (!result.success()) {
        return new KnowledgeSearchOutcome(
                Collections.emptyList(),
                "degraded: " + rootMessage(result.error()),
                "Knowledge search failed"
        );
    }
    List<KnowledgeHit> hits = result.value();
    return new KnowledgeSearchOutcome(hits, hits.toString(), null);
}
```

并在构造器新增参数 `ExternalCallGateway externalCallGateway` + 字段赋值。

- [ ] **Step 2: 删除 AgentRuntime 中 6 处 `agentMetrics.recordLlm(...)` 调用**

位置（行号近似）：`AgentRuntime.java:151, 162, 215, 241, 277, 290`。全部删除（duration 指标由 Gateway 自动埋）。

- [ ] **Step 3: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。若有 `agentMetrics` 未使用警告，可保留字段（recordRagHit 仍在用）或一并清理。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/agent/AgentRuntime.java
git commit -m "refactor: route knowledge search through Gateway; remove redundant recordLlm calls"
```

---

## Task 12: 工具调用走 Gateway

**Files:**
- Modify: `src/main/java/com/gcll/ticketagent/execution/evidence/EvidenceCollectionService.java`

- [ ] **Step 1: 工具调用包装进 Gateway**

在 `EvidenceCollectionService` 构造器注入 `ExternalCallGateway`，把每个 Tool 执行点改为：

```java
CallResult<ToolResult> result = externalCallGateway.execute(
    tool.toolName().equals("query_logs") ? "tool.query-logs"
        : tool.toolName().equals("query_metric") ? "tool.query-metric"
        : "tool.similar-cases",
    () -> tool.execute(params)
);
ToolResult tr = result.success() ? result.value() : ToolResult.failure(tool.toolName(), rootMessage(result.error()));
```

> **执行注意**：`ToolResult.failure(...)` 的确切构造方式按现有 `ToolResult` API 调整（执行时 Read 该文件确认签名）。

- [ ] **Step 2: 编译验证**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/gcll/ticketagent/execution/evidence/EvidenceCollectionService.java
git commit -m "refactor: route tool execution through ExternalCallGateway"
```

---

## Task 13: 测试适配

**Files:**
- Modify: `src/test/java/com/gcll/ticketagent/llm/LlmGatewayRetryTest.java`
- Create: `src/test/java/com/gcll/ticketagent/resilience/ExternalCallGatewayRetryTest.java`（如 Task 6 已建可合并）

- [ ] **Step 1: 重写 LlmGatewayRetryTest 适配新 API**

旧测试测的是 `LlmGateway.call()` 的重试，该方法已删除。重写为测 `LlmGateway.invoke()` 的纯执行 + 异常分类：

```java
package com.gcll.ticketagent.llm;

import com.gcll.ticketagent.resilience.LlmResponse;
import com.gcll.ticketagent.resilience.NonRetryableCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmGatewayRetryTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private LlmGateway llmGateway;

    @BeforeEach
    void setUp() throws Exception {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        llmGateway = new LlmGateway(chatClientBuilder);
    }

    @Test
    void invokeThrowsNonRetryableWhenCallFails() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("temporary"));

        // invoke 把 RuntimeException 翻译为 RetryableCallException（默认可重试）
        assertThatThrownBy(() -> llmGateway.invoke("ticket-extract.txt", "test"))
                .isInstanceOf(com.gcll.ticketagent.resilience.RetryableCallException.class);
    }
}
```

> **执行注意**：`LlmGateway` 构造器现在抛 `IOException`（读 system-base.txt），测试需处理。`@BeforeEach` 已 `throws Exception`。若 system-base.txt 在测试 classpath 不存在，建测试资源或 mock 掉加载。

- [ ] **Step 2: 跑全部测试**

Run: `./mvnw test -q`
Expected: 全部 PASS（含 ExternalCallGatewayTest、LlmGatewayRetryTest、既有 IncidentAnalysisFlowTest 等）。若有失败，按报错修复。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/gcll/ticketagent/llm/LlmGatewayRetryTest.java
git commit -m "test: adapt LlmGatewayRetryTest to new invoke() API and exception classification"
```

---

## Task 14: KNOWN-ISSUES.md + 文档同步 + 端到端验证

**Files:**
- Create: `docs/KNOWN-ISSUES.md`
- Modify: `docs/PROJECT-OVERVIEW.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: 创建 KNOWN-ISSUES.md**

```markdown
# Known Issues & Trade-offs

> 项目已知限制与设计权衡。这是"工程师水准"的体现：问题已知、可控、有兜底，而非假装没问题。

## 外部调用治理（ExternalCallGateway）

- **Resilience4j TimeLimiter 的 cancel 对阻塞 IO 无效**
  - 已知限制：TimeLimiter 内部用 CompletableFuture 机制，cancel 无法中断阻塞 socket read。
  - 兜底：HTTP 层 RestClient readTimeout=60s 是治本（socket 自身超时），TimeLimiter 仅作应用级补充。
  - 影响：单次 LLM 调用最坏 60s 释放线程（由 HTTP 层保证），不会无限泄漏。

- **RestClientCustomizer 对 spring-ai-alibaba 1.0.0-M6.1 的生效性待验证**
  - 已知风险：DashScope autoconfig 是否消费 customizer 未最终确认。
  - 兜底：若不生效，改用 `@Bean RestClient.Builder` 直接覆盖。
  - 验证方法：启动后调一次 LLM，模拟 >60s 响应，观察是否在 60s 抛 SocketTimeoutException。

- **工具调用默认不重试（maxAttempts=1）**
  - 权衡：工具多有副作用（写日志、改状态），重试可能导致重复执行。
  - 兜底：显式为某工具配置 `tool-default` 之外的 retry 实例才开启。

- **熔断打开期间所有调用快速失败**
  - 权衡：牺牲部分可用性换系统稳定（防雪崩）。
  - 兜底：业务层 RuleBased 降级（每个 SpringAi service 都有 fallback）。

- **Token 解析依赖模型返回 usage**
  - 已知限制：部分模型 / 流式响应可能不返回 usage。
  - 兜底：缺失时记 0，CallMetrics 正常累计。

- **异常分类的精细度**
  - 现状：IOException/prompt加载 → NonRetryable；其他默认 → Retryable。
  - 待改进：4xx vs 5xx 的精确判断（依赖 Spring AI 抛出的异常类型细化）。
  - 兜底：错误分类在日志可观测，可据指标调整 retryExceptions 配置。

## 状态机

- **AgentRuntime 是方法调用链，非 status 驱动状态机**
  - 影响：中断后无法从断点恢复，需重跑。
  - Roadmap：状态机可恢复化（见 PROJECT-OVERVIEW §8 P1）。

## 业务闭环

- **路B分发（confirm 后通知责任人）未实现**
  - 现状：HumanConfirmController.confirm 只更新状态。
  - Roadmap：NotificationService 抽象（防腐层 + 日志实现）。
```

- [ ] **Step 2: 更新 PROJECT-OVERVIEW.md §9 简历对标（LLM治理/Token 改为 ✅）**

将 §9 表格中：
- `LLM 治理（超时/重试/降级）` ⚠️ → ✅
- `Token / Context Window` ❌ → ✅

并在 §8 Roadmap 把"LLM 调用工程化治理"从 P0 移到"已完成"。

- [ ] **Step 3: 更新 AGENTS.md §7.2 可靠性红线**

在 §7.2 补一条：

```
【必须】所有外部调用（LLM / 向量检索 / MCP 工具）经 `ExternalCallGateway` 统一治理；禁止在业务层手写重试/超时/熔断。
```

- [ ] **Step 4: 端到端验证**

Run: `./mvnw test -q`
Expected: 全部 PASS。

Run: `./mvnw spring-boot:run -Dspring-boot.run.profiles=milvus`（需中间件）
另开终端：`curl.exe -X POST http://localhost:8020/api/evals/run`
Expected: 14 条 golden case 全过。

- [ ] **Step 5: Commit**

```bash
git add docs/KNOWN-ISSUES.md docs/PROJECT-OVERVIEW.md AGENTS.md
git commit -m "docs: add KNOWN-ISSUES; sync PROJECT-OVERVIEW and AGENTS with governance refactor"
```

---

## 验收 Checklist（全部完成后核对）

- [ ] Eval 14 条 golden case 100% 通过
- [ ] `grep -r "future.get\|future.cancel" src/main/java` 无残留（治理不再用 future）
- [ ] `grep -r "agentMetrics.recordLlm" src/main/java` 无残留
- [ ] `grep -r "llmGateway.call(" src/main/java` 无残留（全部改为 invoke + Gateway）
- [ ] `external_call_duration` / `ai_token_usage` 指标在 `/actuator/prometheus` 可见
- [ ] KNOWN-ISSUES.md 已建立
- [ ] PROJECT-OVERVIEW §9 / AGENTS §7.2 已同步
- [ ] 演示验证：模拟慢响应 → 60s 超时；模拟持续 5xx → 熔断打开

---

## Self-Review 记录

**Spec 覆盖检查**：
- §1 现状 8 问题 → Task 6/8（cancel失效+HTTP timeout）/ Task 9（异常分类+token）/ Task 11（向量）/ Task 4/6（指标）/ Task 6（熔断）。✅
- §3 架构（Gateway + 执行器收窄）→ Task 6/9/10/11/12。✅
- §3.3 异常分类 → Task 2 + Task 9 Step 1。✅
- §4 超时分层 → Task 8（HTTP）+ Task 7（TimeLimiter 配置）。✅
- §5 配置 → Task 7。✅
- §6 可观测 → Task 4 + Task 11（删 recordLlm）。✅
- §7 迁移 → Task 9-12。✅
- §8 测试 → Task 6 + Task 13。✅
- §9 已知限制 → Task 14 KNOWN-ISSUES。✅

**类型一致性**：`CallResult.ok/fail/circuitOpen`、`LlmResponse.of`、`invoke` 返回 `LlmResponse`、`execute` 签名 `<T> CallResult<T> execute(String, Supplier<T>)` 全程一致。✅

**已知执行期需验证点**（已标注在对应 Task）：
1. RestClientCustomizer 对 alibaba starter 生效性（Task 8）
2. attemptsOf 精确计数（Task 6）
3. ToolResult.failure 签名（Task 12）
4. LlmGateway 构造器 IOException 在测试中的处理（Task 13）
