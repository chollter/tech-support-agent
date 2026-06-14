# Known Issues & Trade-offs

> 项目已知限制与设计权衡。这是「工程师水准」的体现：问题已知、可控、有兜底，而非假装没问题。

## 外部调用治理（ExternalCallGateway）

### Resilience4j TimeLimiter 的 cancel 对阻塞 IO 无效

- **已知限制**：TimeLimiter 内部用 CompletableFuture 机制，cancel 无法中断阻塞 socket read。
- **兜底**：HTTP 层 socket readTimeout（DashScope `spring.ai.dashscope.read-timeout=60s`）是治本，TimeLimiter 仅作应用级补充。
- **影响**：单次 LLM 调用最坏 60s 释放线程（HTTP 层保证），不会无限泄漏——这正是修复前 `future.cancel(true)` 对阻塞 IO 无效导致线程泄漏的根治方案。

### RestClientCustomizer 与 DashScopeAutoConfiguration 冲突

- **发现（执行期）**：spring-ai-alibaba 的 `DashScopeAutoConfiguration` 已定义名为 `restClientCustomizer` 的 bean；自定义同名 bean 会触发 `BeanDefinitionOverrideException`（Spring Boot 默认禁止 bean 覆盖）。
- **方案**：不自定义 RestClientCustomizer，改用 DashScope 原生的 `spring.ai.dashscope.read-timeout`（秒）配置 socket readTimeout。反编译确认 DashScope 的 `restClientCustomizer` lambda 消费 `DashScopeConnectionProperties.readTimeout`，通过 `ClientHttpRequestFactorySettings.withReadTimeout` 设置 requestFactory。
- **权衡**：用框架原生能力，避免与框架 bean 冲突；比 plan 原方案的 customizer 更干净。

### connectTimeout 不可配

- **已知限制**：`DashScopeConnectionProperties` 只暴露 `readTimeout`，未暴露 `connectTimeout`（父类 `DashScopeParentProperties` 也无）。
- **兜底**：使用底层 `ClientHttpRequestFactorySettings.DEFAULTS` 的默认 connectTimeout；建连失败通常快速失败（TCP RST / OS 级超时）。
- **改进方向**：若需精确控制 connectTimeout，可自定义 `RestClient.Builder` 覆盖（需避免与 DashScope autoconfig 的 bean name 冲突）。

### 工具调用默认不重试（maxAttempts=1）

- **权衡**：工具多有副作用（写日志、改状态），重试可能导致重复执行。
- **兜底**：显式为某工具配置 `tool-default` 之外的 retry 实例才开启重试。

### 熔断打开期间所有调用快速失败

- **权衡**：牺牲部分可用性换系统稳定（防雪崩）。
- **兜底**：业务层 RuleBased 降级（每个 SpringAi service 都有 fallback）；`CallResult.circuitOpen()` 标志让业务层可区分熔断失败。

### Token 解析依赖模型返回 usage

- **已知限制**：部分模型 / 流式响应可能不返回 usage。
- **兜底**：缺失时记 0，CallMetrics 正常累计 `ai_token_usage` 指标。

### 异常分类的精细度

- **现状**：`LlmGateway.invoke()` 把 IOException / prompt 加载失败翻译为 `NonRetryableCallException`；其他异常默认翻译为 `RetryableCallException`（可重试）。
- **待改进**：4xx vs 5xx 的精确判断（依赖 Spring AI 抛出的具体异常类型细化）。
- **兜底**：错误分类在日志可观测（`external_call_failure_total` 的 `errorType` tag），可据指标调整 `retryExceptions` 配置。

### CompletableFuture 异步异常包装

- **已知**：实际调用经 `CompletableFuture.supplyAsync` 异步执行，业务异常被 `CompletionException` 包装。
- **处理**：`ExternalCallGateway.unwrap()` 在落盘前解包 `CompletionException` / `ExecutionException`，使 `CallResult.error()` 返回业务可识别的异常类型。

## 状态机

### AgentRuntime 是方法调用链，非 status 驱动状态机

- **影响**：中断后无法从断点恢复，需重跑。
- **Roadmap**：状态机可恢复化（见 [PROJECT-OVERVIEW.md §8](PROJECT-OVERVIEW.md) P1）。

## 业务闭环

### 路B 分发（confirm 后通知责任人）未实现

- **现状**：`HumanConfirmController.confirm` 只更新状态。
- **Roadmap**：NotificationService 抽象（防腐层 + 日志实现）。
