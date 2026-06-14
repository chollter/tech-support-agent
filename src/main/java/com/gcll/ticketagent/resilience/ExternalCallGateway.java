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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 统一外部调用治理入口。
 * <p>所有外部调用（LLM / 向量检索 / MCP 工具）经此包装，按 {@code callName} 应用
 * 重试（Retry）/ 超时（TimeLimiter）/ 熔断（CircuitBreaker）策略。业务层只关心
 * {@link CallResult}，不再手写 future / 重试 / 超时。
 *
 * <h3>异常分类契约</h3>
 * 执行器（如 {@code LlmGateway}）负责把底层异常翻译成 {@link RetryableCallException}
 * （可重试：5xx/429/超时/网络）或 {@link NonRetryableCallException}（不可重试：4xx/鉴权/prompt加载）。
 * Retry 配置按这两个类型决定是否重试。
 *
 * <h3>异常解包</h3>
 * 实际调用经 {@code CompletableFuture.supplyAsync} 异步执行，业务异常会被
 * {@link CompletionException} 包装。{@link #unwrap(Throwable)} 在落盘前解包，
 * 使 {@link CallResult#error()} 返回业务可识别的异常类型。
 */
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

    /**
     * 执行一个外部调用，按 callName 应用治理策略。
     *
     * @param callName 调用名，对应 {@code opsmind.resilience.call-mappings} 中的策略
     * @param call     实际调用（无参 Supplier）
     * @return 调用结果（含成功标志、尝试次数、耗时、是否熔断打开）
     */
    public <T> CallResult<T> execute(String callName, Supplier<T> call) {
        CallDecorators d = registry.get(callName);
        long start = System.currentTimeMillis();
        if (d.retry() == null && d.timeLimiter() == null && d.circuitBreaker() == null) {
            return executePlain(callName, call, start);
        }
        AtomicInteger attemptCounter = new AtomicInteger(0);
        Supplier<T> countedCall = () -> {
            attemptCounter.incrementAndGet();
            return call.get();
        };
        try {
            T value = executeDecorated(d, countedCall);
            long duration = System.currentTimeMillis() - start;
            int attempts = Math.max(1, attemptCounter.get());
            metrics.recordSuccess(callName, duration, attempts);
            return CallResult.ok(value, attempts, duration);
        } catch (CallNotPermittedException ex) {
            long duration = System.currentTimeMillis() - start;
            metrics.recordCircuitOpen(callName, duration);
            log.warn("external call circuit open, callName={}, durationMs={}", callName, duration);
            return CallResult.circuitOpen(duration);
        } catch (Exception ex) {
            Throwable cause = unwrap(ex);
            long duration = System.currentTimeMillis() - start;
            int attempts = Math.max(1, attemptCounter.get());
            metrics.recordFailure(callName, duration, attempts, cause.getClass().getSimpleName());
            log.warn("external call failed, callName={}, attempts={}, durationMs={}, error={}",
                    callName, attempts, duration, cause.getMessage());
            return CallResult.fail(cause, attempts, duration);
        }
    }

    /**
     * 装饰器链组合（外→内）：CircuitBreaker → TimeLimiter → Retry → 实际调用。
     * <p>stageSupplier 只在末尾 {@code get()} 一次，避免重复执行实际调用。
     * 这是对 Resilience4j 装饰器的正确用法——每层包装把 Supplier 替换为增强版，
     * 最后单次求值；而非中途求值再被外层再次求值。
     */
    private <T> T executeDecorated(CallDecorators d, Supplier<T> call) {
        Supplier<CompletionStage<T>> stageSupplier = () -> CompletableFuture.supplyAsync(call);
        if (d.retry() != null) {
            stageSupplier = Retry.decorateCompletionStage(d.retry(), timeLimiterScheduler, stageSupplier);
        }
        if (d.timeLimiter() != null) {
            TimeLimiter tl = d.timeLimiter();
            final Supplier<CompletionStage<T>> inner = stageSupplier;
            stageSupplier = () -> tl.executeCompletionStage(timeLimiterScheduler, inner);
        }
        if (d.circuitBreaker() != null) {
            CircuitBreaker cb = d.circuitBreaker();
            final Supplier<CompletionStage<T>> inner = stageSupplier;
            // CircuitBreaker 熔断判定不涉及超时调度，executeCompletionStage 无需 scheduler
            stageSupplier = () -> cb.executeCompletionStage(inner);
        }
        return stageSupplier.get().toCompletableFuture().join();
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
                    callName, duration, ex.getMessage());
            return CallResult.fail(ex, 1, duration);
        }
    }

    /**
     * 解包 CompletableFuture 异步执行产生的 CompletionException / ExecutionException，
     * 暴露真实业务异常（RetryableCallException / NonRetryableCallException 等）。
     */
    private Throwable unwrap(Throwable ex) {
        if ((ex instanceof CompletionException || ex instanceof ExecutionException) && ex.getCause() != null) {
            return ex.getCause();
        }
        return ex;
    }
}
