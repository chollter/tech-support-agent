package com.gcll.ticketagent.domain;

import java.time.Instant;

public class AgentStep {
    private final String id;
    private final String runId;
    private final String stepName;
    private String status;
    private String inputSnapshot;
    private String outputSnapshot;
    private boolean llmUsed;
    private String toolUsed;
    private long costMs;
    private String errorMessage;
    private final Instant createdAt;
    private String detail;

    public AgentStep(String id, String runId, String stepName, String status, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.stepName = stepName;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static AgentStep legacy(String id, String runId, String name, String detail, Instant createdAt) {
        AgentStep step = new AgentStep(id, runId, name, "SUCCESS", createdAt);
        step.detail = detail;
        step.outputSnapshot = detail;
        return step;
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getStepName() {
        return stepName;
    }

    public String getName() {
        return stepName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInputSnapshot() {
        return inputSnapshot;
    }

    public void setInputSnapshot(String inputSnapshot) {
        this.inputSnapshot = inputSnapshot;
    }

    public String getOutputSnapshot() {
        return outputSnapshot;
    }

    public void setOutputSnapshot(String outputSnapshot) {
        this.outputSnapshot = outputSnapshot;
    }

    public boolean isLlmUsed() {
        return llmUsed;
    }

    public void setLlmUsed(boolean llmUsed) {
        this.llmUsed = llmUsed;
    }

    public String getToolUsed() {
        return toolUsed;
    }

    public void setToolUsed(String toolUsed) {
        this.toolUsed = toolUsed;
    }

    public long getCostMs() {
        return costMs;
    }

    public void setCostMs(long costMs) {
        this.costMs = costMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getDetail() {
        return detail;
    }
}
