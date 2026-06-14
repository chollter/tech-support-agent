package com.gcll.ticketagent.domain;

public enum AgentRunStatus {
    RUNNING,
    WAIT_USER_INPUT,
    WAIT_HUMAN_CONFIRM,
    FINAL,
    FAILED,
    ESCALATED
}
