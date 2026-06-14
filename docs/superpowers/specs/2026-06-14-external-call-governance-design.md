# 统一外部调用治理层设计（External Call Governance）

> 日期：2026-06-14
> 状态：Draft（待用户 review）
> 关联：[PROJECT-OVERVIEW.md §8 Roadmap](../../PROJECT-OVERVIEW.md) · [AGENTS.md §5.4 分发边界](../../../AGENTS.md)
> 关联代码：`llm/LlmGateway.java` · `agent/AgentRuntime.java`（searchKnowledgeSafely）· `config/LlmExecutorConfig.java`

---

## 1. 背景与目标

### 1.1 现状问题

当前外部调用治理（重试 / 超时 / 熔断）散落在各处，且有多个 P0 级硬伤：

| 问题 | 位置 | 后果 |
|------|------|------|
| `future.cancel(true)` 对阻塞 IO 无效 | `LlmGateway.invokeWithTimeout:77` | 线程泄漏，`llmCallExecutor`（maxPool=4）被慢调用耗尽，LLM 能力静默宕机 |
| 底层 HTTP client 无 readTimeout | DashScope RestClient 未配置 | 线程可能无限阻塞，无底线 |
| 重试不区分异常（`catch(Exception)` 一律重试） | `LlmGateway.call:53` | 4xx / prompt 加载失败也重试 3 次，浪费配额与 ~186s |
| Token 用量全丢（写死 0,0） | `LlmGateway.invokeWithTimeout:75` | 无 token 成本核算，无 `ai_token_usage` 指标 |
| 指标埋点位置错（散在 AgentRuntime） | `AgentRuntime:151/162/215/...` | 绕过 AgentRuntime 的调用无指标 |
| 向量检索用同样的 future 模式 | `AgentRuntime.searchKnowledgeSafely:331` | 同类线程泄漏隐患 |
| 工具调用无治理 | `execution/evidence/` | MCP 工具失败无重试/熔断 |
| 无熔断器 | 全局 | LLM 持续故障时每请求熬满 3×60s 才降级，雪崩风险 |

### 1.2 设计目标

1. **治理收敛**：所有外部调用（LLM / 向量检索 / MCP工具）治理逻辑集中在一处（`ExternalCallGateway`）。
2. **硬伤修复**：超时分层生效（HTTP 层 + 业务层）、重试按异常分类、token 解析与埋点。
3. **零重复**：新增外部调用不重写治理逻辑，只加配置。
4. **可观测**：治理动作（duration / attempts / success / token）统一埋点。
5. **可降级**：治理失败时业务层有 RuleBased 兜底（已有，保留）。

### 1.3 非目标

- 不治理内部调用（DB / Kafka / Redis）。
- 不引入分布式追踪（TraceId 跨线程传递另议）。
- 不重写业务逻辑，只重构"如何调用外部"。

---

## 2. 技术选型

| 维度 | 选择 | 理由 |
|------|------|------|
| 治理框架 | **Resilience4j** | 重试 + 超时（TimeLimiter）+ 熔断 + 限流一站式；Spring Boot 3 事实标准；函数式 API 契合现有风格 |
| HTTP 客户端超时 | **底层 RestClient 配置** | DashScope 自动配置注入 RestClient.Builder，配 connect/read timeout，治线程泄漏之本 |
| 重试异常分类 | Resilience4j `retryExceptions` / `ignoreExceptions` | 精确控制可重试 vs 不可重试 |
| 配置方式 | **application.yml 配置驱动** + 编程式包装 | 策略集中、可热更新、易演示 |

> 关于 CLAUDE.md「必须实现重试（Spring Retry）」：Resilience4j 的 Retry 模块满足"声明式/可配置重试"的要求，且多带熔断/限流。如需严格字面遵守 Spring Retry，可在 Gateway 内部以 Spring Retry `RetryTemplate` 实现重试、Resilience4j 实现熔断/超时——但混用增加复杂度，不推荐。

---

## 3. 架构设计

### 3.1 分层

```text
┌──────────────────────────────────────────────────────────┐
│  业务层（extract / gap / planner / rootcause / 评测）       │
│  只管业务，完全不写重试/超时/熔断                            │
└────────────────────────┬─────────────────────────────────┘
                         ↓ 调用具体执行器
┌──────────────────────────────────────────────────────────┐
│  执行器层（保留各自专长）                                   │
│  · LlmGateway         → prompt加载/系统提示/结构化输出/token │
│  · KnowledgeSearch    → 向量检索                            │
│  · ToolGateway        → MCP/Function 工具执行               │
└────────────────────────┬─────────────────────────────────┘
                         ↓ 包装在 Gateway 里调用
┌──────────────────────────────────────────────────────────┐
│  ExternalCallGateway（新增 · 治理/防腐层）                  │
│  <T> CallResult<T> execute(callName, Supplier<T>)          │
│  · 按 callName 装配 Resilience4j decorators                 │
│  · 统一埋点（duration/attempts/success/token）             │
│  · 异常分类（RetryableException vs NonRetryableException） │
└──────────────────────────────────────────────────────────┘
```

### 3.2 核心组件

#### 3.2.1 `ExternalCallGateway`（新增）

```java
package com.gcll.ticketagent.resilience;

@Service
public class ExternalCallGateway {

    private final CallRegistry registry;       // callName → Resilience 装饰器
    private final CallMetrics metrics;         // 统一埋点

    /**
     * 执行一个外部调用，按 callName 应用治理策略。
     * @param callName 调用名，对应 application.yml 中的策略配置
     * @param call    实际调用（无参 Supplier）
     * @return        调用结果（含成功标志、耗时、尝试次数等）
     */
    public <T> CallResult<T> execute(String callName, Supplier<T> call);
}
```

#### 3.2.2 `CallResult`（新增 record）

```java
public record CallResult<T>(
    boolean success,
    T value,
    Throwable error,
    int attempts,           // 实际尝试次数（含首次）
    long durationMs,
    boolean circuitOpen,    // 是否因熔断开放而快速失败
    boolean fallbackUsed    // 是否走了降级（业务层设置）
) {
    public static <T> CallResult<T> ok(T value, int attempts, long durationMs) { ... }
    public static <T> CallResult<T> fail(Throwable error, int attempts, long durationMs) { ... }
}
```

#### 3.2.3 异常分类（新增）

```java
package com.gcll.ticketagent.resilience;

/** 可重试异常：5xx / 429 / 超时 / 网络错误 */
public class RetryableCallException extends RuntimeException { ... }

/** 不可重试异常：4xx / 鉴权失败 / prompt 超长 / 内容拒绝 / 确定性错误 */
public class NonRetryableCallException extends RuntimeException { ... }
```

执行器层负责把底层异常翻译成这两类（见 §3.3）。

#### 3.2.4 `LlmGateway` 改造（收窄职责）

| 职责 | 改造前 | 改造后 |
|------|--------|--------|
| prompt 加载 / 系统提示 | ✅ | ✅ 保留 |
| 结构化输出解析 | ✅ | ✅ 保留 |
| token 解析 | ❌（写死0） | ✅ 新增（从 ChatResponse.metadata().usage() 解析） |
| 重试 / 超时 / 熔断 | ❌ 手写 for + future | ❌ **删除**，委托 Gateway |
| 调用入口 | `call(promptFile, content)` 内部包治理 | 提供 `invoke(promptFile, content)`（纯执行，不治理），由 Gateway 包装 |

业务层调用方式：
```java
// 业务 Service
CallResult<LlmResponse> result = externalCallGateway.execute(
    "llm.ticket-extract",
    () -> llmGateway.invoke("ticket-extract.txt", content)   // 纯执行器，返回含 token
);
if (!result.success()) { return fallback.extract(content); }
LlmResponse response = result.value();
// response.content() / response.promptTokens() / response.completionTokens()
```

**`LlmGateway.invoke()` 返回新增的 `LlmResponse(content, promptTokens, completionTokens)` record**，token 从 `ChatResponse.metadata().usage()` 解析。token 埋点由 `LlmGateway` 解析后调 `CallMetrics.recordTokenUsage()` 完成（见 §6.1）——Gateway 是通用的，不感知 token 这种 LLM 特有概念。

#### 3.2.5 `AgentRuntime.searchKnowledgeSafely` 改造

删除内部的 `CompletableFuture + future.get(timeout)`，改为：

```java
CallResult<List<KnowledgeHit>> result = externalCallGateway.execute(
    "vector.knowledge-search",
    () -> knowledgeSearchService.search(...)
);
List<KnowledgeHit> hits = result.success() ? result.value() : List.of();
// 超时/失败由 Gateway 统一处理，AgentRuntime 只关心业务
```

#### 3.2.6 `ToolGateway` 工具调用改造

MCP / Function 工具调用统一走 Gateway（callName 前缀 `tool.`），默认不重试（工具多有副作用）。

### 3.3 异常翻译规则（执行器职责）

执行器负责把底层异常翻译成 `RetryableCallException` / `NonRetryableCallException`：

| 底层异常 | 翻译为 | 是否重试 |
|----------|--------|----------|
| HTTP 4xx（400/401/403/413 等） | NonRetryableCallException | ❌ |
| HTTP 429 Too Many Requests | RetryableCallException | ✅ |
| HTTP 5xx | RetryableCallException | ✅ |
| SocketTimeoutException / 超时 | RetryableCallException | ✅ |
| ConnectException / 网络中断 | RetryableCallException | ✅ |
| prompt 文件加载失败（IOException） | NonRetryableCallException | ❌ |
| 内容策略拒绝（模型返回 refusal） | NonRetryableCallException | ❌ |

Resilience4j retry 配置：
```yaml
retryExceptions:
  - com.gcll.ticketagent.resilience.RetryableCallException
ignoreExceptions:
  - com.gcll.ticketagent.resilience.NonRetryableCallException
```

---

## 4. 超时分层

```text
HTTP 层（DashScope RestClient）         业务层（Resilience4j TimeLimiter）
  connectTimeout = 10s                    llm-default   = 60s
  readTimeout    = 60s  ← 治线程泄漏之本   vector-default = 5s
                                          tool-default   = 10s
```

**实现方式**：

1. **HTTP 层**：自定义 `RestClientCustomizer` / `RestClient.Builder` Bean，设置 connect/read timeout。注入 DashScope 自动配置。
2. **业务层**：Resilience4j `TimeLimiter`（`@Configuration` 注册 `TimeLimiterRegistry`）。

两层关系：HTTP 层是底线（保证单个调用线程不泄漏），业务层是兜底（保证总耗时受控）。HTTP 层超时必须 **≤** 业务层超时。

> 注意：Resilience4j `TimeLimiter` 内部用 `CompletableFuture.orTimeout` 机制，其 cancel 对阻塞 IO 同样无效——所以 **HTTP 层超时是必须的，不能只靠 TimeLimiter**。这是本设计的关键认知。

---

## 5. 配置 Schema

### 5.1 application.yml 新增

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
        retryExceptions: [com.gcll.ticketagent.resilience.RetryableCallException]
        ignoreExceptions: [com.gcll.ticketagent.resilience.NonRetryableCallException]
      vector-default:
        maxAttempts: 2
        waitDuration: 500ms
      tool-default:
        maxAttempts: 1                # 工具多有副作用，默认不重试
  timelimiter:
    instances:
      llm-default:    { timeoutDuration: 60s }
      vector-default: { timeoutDuration: 5s }
      tool-default:   { timeoutDuration: 10s }
  circuitbreaker:
    instances:
      llm-default:
        failureRateThreshold: 50%
        slowCallRateThreshold: 80%
        slowCallDurationThreshold: 45s
        waitDurationInOpenState: 30s
        slidingWindowSize: 10
      vector-default:
        failureRateThreshold: 60%
        waitDurationInOpenState: 15s

# callName → 策略实例映射
opsmind:
  resilience:
    call-mappings:
      llm.ticket-extract:    { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.info-gap:          { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.agent-plan:        { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.tool-select:       { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.root-cause:        { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.routing-suggest:   { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.suggestion:        { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      llm.follow-up:         { retry: llm-default, timelimiter: llm-default, circuitbreaker: llm-default }
      vector.knowledge-search: { retry: vector-default, timelimiter: vector-default, circuitbreaker: vector-default }
      tool.query-logs:       { retry: tool-default, timelimiter: tool-default }
      tool.query-metric:     { retry: tool-default, timelimiter: tool-default }
      tool.similar-cases:    { retry: tool-default, timelimiter: tool-default }
```

### 5.2 废弃配置

```yaml
llm:
  connect-timeout-ms: 10000    # 废弃（原本就是死配置），改由 HTTP 层 RestClient 配置
  read-timeout-ms: 60000       # 废弃，改由 TimeLimiter + RestClient readTimeout
  max-retries: 2               # 废弃，改由 resilience4j.retry.maxAttempts
```

---

## 6. 可观测性

### 6.1 指标（替代散落的 recordLlm）

`CallMetrics` 在 Gateway 层统一埋点（Micrometer），按 callName tag：

| 指标 | 类型 | tag |
|------|------|-----|
| `external_call_duration` | Timer | callName, success |
| `external_call_attempts` | DistributionSummary | callName |
| `external_call_success_total` | Counter | callName |
| `external_call_failure_total` | Counter | callName, errorType |
| `external_call_circuit_open_total` | Counter | callName |
| `ai_token_usage` | DistributionSummary | callName, type(prompt/completion) |

> **埋点职责分工**：
> - **Gateway 自动埋通用指标**（duration / attempts / success / circuit）——业务零侵入。
> - **token 埋点由 `LlmGateway` 负责**——解析 `ChatResponse.metadata().usage()` 后调 `CallMetrics.recordTokenUsage(callName, promptTokens, completionTokens)`。Gateway 通用、不感知 token。
> - 替换 `AgentRuntime` 中 6 处 `agentMetrics.recordLlm(...)`——通用 duration 指标由 Gateway 自动埋，不再需要业务层手动调。

### 6.2 日志

Gateway 层结构化日志（占位符，禁拼接）：
```java
log.info("external call done, callName={}, success={}, attempts={}, durationMs={}, circuitOpen={}",
         callName, result.success(), result.attempts(), result.durationMs(), result.circuitOpen());
log.warn("external call failed, callName={}, attempts={}, error={}",
         callName, result.attempts(), rootMessage(result.error()));
```

失败日志在 debug 级别补打异常栈。

---

## 7. 迁移计划（一次性交付，内部嵌验证节奏）

### 7.1 内部验证顺序（非分阶段交付，仅为降低一次性改动风险）

```
Step 1：基础设施
  · 引入 resilience4j-spring-boot3 依赖
  · 新建 resilience/ 包：ExternalCallGateway / CallResult / 异常类 / CallRegistry / CallMetrics
  · 配置 RestClient connect/read timeout（HTTP 层）
  · 单元测试 Gateway（mock Supplier，验证重试/超时/熔断/异常分类）

Step 2：迁移 LLM 调用
  · LlmGateway 拆出 invoke()（纯执行），删除 call()/invokeWithTimeout() 的手写治理
  · LlmGateway 加 token 解析（ChatResponse.metadata().usage()）
  · 9 个 SpringAi*Service 改为经 Gateway 调用
  · 删除 AgentRuntime 中 6 处 recordLlm（Gateway 自动埋点）
  · 跑 Eval（14 条 golden case）验证 LLM 路径无回归

Step 3：迁移向量检索 + 工具调用
  · AgentRuntime.searchKnowledgeSafely 改走 Gateway（删 future.get）
  · EvidenceCollectionService / ToolGateway 改走 Gateway
  · 再跑 Eval 验证

Step 4：收尾
  · 补 docs/KNOWN-ISSUES.md（已知限制与权衡）
  · 更新 LlmGatewayRetryTest（适配新 API）
  · 更新 AGENTS.md §7.2 可靠性红线、PROJECT-OVERVIEW §7/§8
```

### 7.2 受影响文件清单

| 文件 | 动作 |
|------|------|
| `pom.xml` | 新增 resilience4j 依赖 |
| `resilience/ExternalCallGateway.java` | 🆕 |
| `resilience/CallResult.java` | 🆕 |
| `resilience/RetryableCallException.java` | 🆕 |
| `resilience/NonRetryableCallException.java` | 🆕 |
| `resilience/CallRegistry.java` | 🆕（callName → 装饰器映射） |
| `resilience/CallMetrics.java` | 🆕 |
| `resilience/ResilienceConfig.java` | 🆕（注册 Registry / 装配装饰器） |
| `config/RestClientConfig.java` | 🆕 或改现有（配 HTTP timeout） |
| `llm/LlmGateway.java` | ✏️ 删治理逻辑、拆 invoke()、加 token 解析 |
| `llm/LlmProperties.java` | ✏️ 废弃 connect/read timeout/maxRetries |
| `extract/SpringAiTicketExtractService.java` | ✏️ 改走 Gateway |
| `understanding/gap/SpringAiInfoGapAnalysisService.java` | ✏️ |
| `agent/planner/SpringAiAgentPlanner.java` | ✏️ |
| `execution/tool/SpringAiToolSelector.java` | ✏️ |
| `governance/routing/SpringAiRoutingSuggestionService.java` | ✏️ |
| `suggestion/SpringAiSuggestionGenerationService.java` | ✏️ |
| `analysis/LlmRootCauseAnalysisService.java` | ✏️ |
| `understanding/followup/LlmFollowUpProvider.java` | ✏️ |
| `agent/AgentRuntime.java` | ✏️ searchKnowledgeSafely 改走 Gateway；删 6 处 recordLlm |
| `execution/evidence/EvidenceCollectionService.java` | ✏️ 改走 Gateway |
| `application.yml` | ✏️ 新增 resilience4j 配置、废弃 llm.* timeout |
| `metrics/AgentMetrics.java` | ✏️ recordLlm 可保留或废弃（由 CallMetrics 替代） |
| `test/.../LlmGatewayRetryTest.java` | ✏️ 适配新 API |
| `test/.../ExternalCallGatewayTest.java` | 🆕 |
| `docs/KNOWN-ISSUES.md` | 🆕 |
| `AGENTS.md` / `PROJECT-OVERVIEW.md` | ✏️ 同步 |

---

## 8. 测试计划

### 8.1 单元测试（新增 `ExternalCallGatewayTest`）

| 用例 | 验证点 |
|------|--------|
| 正常调用 | 返回 ok，attempts=1 |
| 可重试异常 + 二次成功 | attempts=2，最终 ok |
| 不可重试异常 | 立即失败，attempts=1，不重试 |
| 超时（TimeLimiter） | 抛超时，CallResult.fail |
| 熔断打开 | 连续失败后快速失败，circuitOpen=true |
| 异常分类正确 | 4xx→NonRetryable，5xx→Retryable |

### 8.2 回归测试

- `LlmGatewayRetryTest`：适配新 API（retriesOnFailureThenThrows / succeedsOnSecondAttempt 改为测 Gateway）。
- `IncidentAnalysisFlowTest`：端到端流程无回归。
- Eval 14 条 golden case 100% 通过。

### 8.3 演示验证（面试可讲）

- 模拟 LLM 慢响应（>60s）→ HTTP readTimeout 触发，线程释放（对比改造前线程泄漏）。
- 模拟 LLM 持续 5xx → 熔断打开，后续请求快速失败走 RuleBased 降级。
- 模拟 4xx → 不重试，立即失败（对比改造前重试 3 次）。

---

## 9. 已知权衡与限制（写入 KNOWN-ISSUES.md）

| 项 | 权衡 | 兜底 |
|----|------|------|
| Resilience4j TimeLimiter 的 cancel 对阻塞 IO 无效 | 已知，靠 HTTP 层 readTimeout 兜底 | HTTP timeout 必配 |
| 工具调用默认不重试 | 工具有副作用，重试可能重复执行 | 显式配置才开启 |
| 熔断打开期间所有调用快速失败 | 牺牲部分可用性换系统稳定 | 业务层 RuleBased 降级 |
| Token 解析依赖模型返回 usage | 部分模型/流式可能不返回 | 缺失时记 0 + 日志告警 |
| 异常翻译依赖执行器正确分类 | 翻译错误会导致该重试的不重试 | 单测覆盖 + 日志可观测 |

---

## 10. 验收标准

| 类别 | 标准 |
|------|------|
| 功能 | Eval 14 条全过；端到端 Demo 正常 |
| 硬伤修复 | HTTP readTimeout 生效（线程不泄漏）；重试按异常分类；token 解析 |
| 治理收敛 | LLM / 向量 / 工具调用全部经 Gateway，无散落的 future.get / 手写重试 |
| 可观测 | 6 类指标暴露；日志含 callName/attempts/duration |
| 配置 | application.yml 配置驱动，新增调用零代码改动治理 |
| 文档 | KNOWN-ISSUES.md 建立；AGENTS.md / PROJECT-OVERVIEW 同步 |

---

## 11. 不做（YAGNI）

- ❌ 分布式追踪 / TraceId 跨线程（另议）
- ❌ 治理内部调用（DB/Kafka/Redis）
- ❌ 限流（RateLimiter）——预留配置位，本期不启用
- ❌ 动态配置热更新（配置中心）——本期静态配置即可
- ❌ 多模型适配抽象——本期仍 DashScope 单模型
