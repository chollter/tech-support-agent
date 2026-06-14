package com.gcll.ticketagent.llm;

public record LlmCallResult(
        String content,
        long latencyMs,
        int promptTokens,
        int completionTokens
) {
}
