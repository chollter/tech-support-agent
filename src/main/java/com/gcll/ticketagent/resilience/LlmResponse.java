package com.gcll.ticketagent.resilience;

/**
 * LLM 执行结果：内容 + token 用量。
 * <p>token 缺失（部分模型/流式不返回 usage）时记 0。
 */
public record LlmResponse(
        String content,
        int promptTokens,
        int completionTokens
) {

    public static LlmResponse of(String content, int promptTokens, int completionTokens) {
        return new LlmResponse(content, promptTokens, completionTokens);
    }
}
