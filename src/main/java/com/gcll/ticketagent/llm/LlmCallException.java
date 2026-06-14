package com.gcll.ticketagent.llm;

public class LlmCallException extends RuntimeException {
    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
