package com.gcll.ticketagent.resilience;

import com.gcll.ticketagent.llm.LlmGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * LLM 调用执行器：封装 {@link ExternalCallGateway} 治理 + {@link LlmGateway} 纯执行 + token 埋点。
 * <p>业务层（extract / gap / planner / rootcause / suggestion / routing / follow-up / tool-select）
 * 统一通过本类调用 LLM，无需各自处理治理与埋点，避免 8 处重复代码。
 *
 * <p>token 埋点职责落在此处（而非通用的 {@link ExternalCallGateway}）：Gateway 是类型无关的
 * 治理层，不感知 LLM 特有的 token 概念；本执行器拿到 {@link LlmResponse} 后调
 * {@link CallMetrics#recordTokenUsage}，实现 spec §6.1 的 {@code ai_token_usage} 指标。
 */
@Component
public class LlmCallExecutor {

    private final ExternalCallGateway gateway;
    private final CallMetrics metrics;
    private final ObjectProvider<LlmGateway> llmGatewayProvider;

    public LlmCallExecutor(ExternalCallGateway gateway,
                           CallMetrics metrics,
                           ObjectProvider<LlmGateway> llmGatewayProvider) {
        this.gateway = gateway;
        this.metrics = metrics;
        this.llmGatewayProvider = llmGatewayProvider;
    }

    /**
     * 按 callName 治理调用 LLM（加载 promptFile 提示词 + userContent），成功时埋点 token 用量。
     *
     * @param callName    对应 {@code opsmind.resilience.call-mappings} 的策略名（如 llm.ticket-extract）
     * @param promptFile  classpath:prompts/ 下的提示词文件名
     * @param userContent 用户工单内容
     * @return 治理后的调用结果；LlmGateway 不可用（无 ChatClient.Builder）时返回失败结果，调用方走降级
     */
    public CallResult<LlmResponse> execute(String callName, String promptFile, String userContent) {
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return CallResult.fail(
                    new NonRetryableCallException("LlmGateway bean not available (no ChatClient.Builder)"),
                    0, 0L);
        }
        CallResult<LlmResponse> result = gateway.execute(
                callName, () -> llmGateway.invoke(promptFile, userContent));
        if (result.success()) {
            LlmResponse response = result.value();
            metrics.recordTokenUsage(callName, response.promptTokens(), response.completionTokens());
        }
        return result;
    }
}
