package com.gcll.ticketagent.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmGatewayRetryTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private LlmProperties llmProperties;
    private LlmGateway llmGateway;

    @BeforeEach
    void setUp() throws Exception {
        llmProperties = new LlmProperties();
        llmProperties.setMaxRetries(2);
        llmProperties.setReadTimeoutMs(3000);

        when(chatClientBuilder.build()).thenReturn(chatClient);
        Executor directExecutor = Runnable::run;
        llmGateway = new LlmGateway(chatClientBuilder, llmProperties, directExecutor);
    }

    @Test
    void retriesOnFailureThenThrows() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("temporary"));

        assertThatThrownBy(() -> llmGateway.call("ticket-extract.txt", "test"))
                .isInstanceOf(LlmCallException.class);
        verify(requestSpec, times(3)).call();
    }

    @Test
    void succeedsOnSecondAttempt() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call())
                .thenThrow(new RuntimeException("temporary"))
                .thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn("ok");

        LlmCallResult result = llmGateway.call("ticket-extract.txt", "test");
        org.assertj.core.api.Assertions.assertThat(result.content()).isEqualTo("ok");
        verify(requestSpec, times(2)).call();
    }
}
