package com.gcll.ticketagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@ConditionalOnBean(ChatClient.Builder.class)
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final ChatClient chatClient;
    private final LlmProperties llmProperties;
    private final Executor llmCallExecutor;
    private final String systemBasePrompt;

    public LlmGateway(
            ChatClient.Builder chatClientBuilder,
            LlmProperties llmProperties,
            @Qualifier("llmCallExecutor") Executor llmCallExecutor
    ) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.llmProperties = llmProperties;
        this.llmCallExecutor = llmCallExecutor;
        this.systemBasePrompt = new ClassPathResource("prompts/system-base.txt")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public LlmCallResult call(String promptFile, String userContent) {
        int maxAttempts = Math.max(1, llmProperties.getMaxRetries() + 1);
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            try {
                LlmCallResult result = invokeWithTimeout(promptFile, userContent);
                log.info("LLM call completed, prompt={}, attempt={}, latencyMs={}",
                        promptFile, attempt, result.latencyMs());
                return result;
            } catch (Exception ex) {
                lastException = ex;
                long latency = System.currentTimeMillis() - start;
                log.warn("LLM call failed, prompt={}, attempt={}/{}, latencyMs={}, error={}",
                        promptFile, attempt, maxAttempts, latency, rootMessage(ex));
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            }
        }
        throw new LlmCallException("LLM call failed after " + maxAttempts + " attempts", lastException);
    }

    private LlmCallResult invokeWithTimeout(String promptFile, String userContent) throws Exception {
        long start = System.currentTimeMillis();
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> invokeChat(promptFile, userContent),
                llmCallExecutor
        );
        try {
            String response = future.get(llmProperties.getReadTimeoutMs(), TimeUnit.MILLISECONDS);
            long latency = System.currentTimeMillis() - start;
            return new LlmCallResult(response, latency, 0, 0);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new LlmCallException("LLM read timeout after " + llmProperties.getReadTimeoutMs() + "ms", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new LlmCallException("LLM call failed", cause);
        }
    }

    private String invokeChat(String promptFile, String userContent) {
        try {
            String promptTemplate = loadPrompt(promptFile);
            return chatClient.prompt()
                    .system(systemBasePrompt)
                    .user(promptTemplate + "\n\n工单内容：\n" + userContent)
                    .call()
                    .content();
        } catch (IOException ex) {
            throw new LlmCallException("Failed to load prompt: " + promptFile, ex);
        }
    }

    private void sleepBackoff(int attempt) {
        long delayMs = Math.min(2000L * attempt, 8000L);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String loadPrompt(String promptFile) throws IOException {
        return new ClassPathResource("prompts/" + promptFile).getContentAsString(StandardCharsets.UTF_8);
    }

    private String rootMessage(Exception ex) {
        Throwable cause = ex.getCause();
        return cause == null ? ex.getMessage() : cause.getMessage();
    }
}
