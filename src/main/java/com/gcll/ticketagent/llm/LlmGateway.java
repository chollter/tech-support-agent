package com.gcll.ticketagent.llm;

import com.gcll.ticketagent.resilience.LlmResponse;
import com.gcll.ticketagent.resilience.NonRetryableCallException;
import com.gcll.ticketagent.resilience.RetryableCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * LLM 纯执行器：负责 prompt 加载、系统提示拼装、结构化调用与 token 解析。
 * <p><b>不含任何治理逻辑</b>（重试 / 超时 / 熔断）——治理由调用方经
 * {@link com.gcll.ticketagent.resilience.ExternalCallGateway}（推荐通过
 * {@link com.gcll.ticketagent.resilience.LlmCallExecutor}）包装。
 *
 * <h3>异常分类契约</h3>
 * 底层异常在本类翻译为 Resilience4j 可识别的两类：
 * <ul>
 *   <li>{@link NonRetryableCallException}：prompt 文件加载失败等确定性错误，不重试</li>
 *   <li>{@link RetryableCallException}：其他异常（网络抖动 / 5xx / 超时等），默认可重试</li>
 * </ul>
 */
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
     * 纯执行：调一次 LLM，返回内容 + token 用量。
     *
     * @param promptFile  classpath:prompts/ 下的提示词文件名
     * @param userContent 用户工单内容
     * @return LLM 响应（内容 + prompt/completion token；token 缺失记 0）
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
            // 其他异常默认按可重试处理（网络抖动 / 5xx / 超时等）
            log.debug("LLM invoke failed, classified as retryable, error={}", ex.getMessage());
            throw new RetryableCallException("LLM call failed", ex);
        }
    }

    /**
     * @deprecated 治理已收敛到 {@link com.gcll.ticketagent.resilience.ExternalCallGateway}，
     * 新代码应经 {@link com.gcll.ticketagent.resilience.LlmCallExecutor#execute} 调用。
     * 本方法保留仅为过渡期兼容，委托 {@link #invoke} 且<b>不再治理</b>（无重试/超时）。
     */
    @Deprecated
    public LlmCallResult call(String promptFile, String userContent) {
        long start = System.currentTimeMillis();
        try {
            LlmResponse response = invoke(promptFile, userContent);
            long latency = System.currentTimeMillis() - start;
            return new LlmCallResult(response.content(), latency,
                    response.promptTokens(), response.completionTokens());
        } catch (NonRetryableCallException | RetryableCallException ex) {
            throw new LlmCallException(ex.getMessage(), ex);
        }
    }

    private String loadPrompt(String promptFile) throws IOException {
        return new ClassPathResource("prompts/" + promptFile).getContentAsString(StandardCharsets.UTF_8);
    }
}
