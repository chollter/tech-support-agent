package com.gcll.ticketagent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手动声明 {@link ChatClient.Builder} bean。
 *
 * <p><b>背景</b>：项目依赖 spring-ai-alibaba-starter 1.0.0-M6.1，其 {@code DashScopeAutoConfiguration}
 * 会注册 {@code DashScopeChatModel}（一个 {@link ChatModel}）。但该版本与 Spring AI 原生
 * {@code ChatClientAutoConfiguration} 存在 bean 注册时序问题——{@code ChatClient.Builder}
 * 虽然条件 matched，但最终未被实例化（debug ConditionEvaluationReport 可见
 * {@code LlmGateway} 因找不到 {@code ChatClient$Builder} 而 Did not match）。
 *
 * <p><b>后果</b>：依赖 {@code @ConditionalOnBean(ChatClient.Builder.class)} 的
 * {@code LlmGateway} 不创建 → 所有 LLM 调用 fail-fast → 走规则降级 → {@code aiGenerated=false}。
 *
 * <p><b>修法</b>：在此显式从已注册的 {@link ChatModel} 构建 {@link ChatClient.Builder}，
 * 绕开 autoconfig 时序问题。升级到 Spring AI GA 版本后此配置可移除。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
