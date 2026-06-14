package com.gcll.ticketagent.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AgentRun {
    private final String id;
    private final String traceId;
    private final String sessionId;
    private final String userId;
    private AgentRunStatus status;
    private String originalContent;
    private String currentSummary;
    private String issueType;
    private String priority;
    private String gapAnalysisJson;
    private String agentPlanJson;
    private String toolSelectionJson;
    private String idempotencyKey;
    private String requestId;
    private long version;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<AgentStep> steps = new ArrayList<>();

    public AgentRun(String id, String traceId, String sessionId, String userId, String originalContent) {
        this.id = id;
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.originalContent = originalContent;
        this.status = AgentRunStatus.RUNNING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() {
        return id;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public AgentRunStatus getStatus() {
        return status;
    }

    public void setStatus(AgentRunStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getOriginalContent() {
        return originalContent;
    }

    public void setOriginalContent(String originalContent) {
        this.originalContent = originalContent;
        this.updatedAt = Instant.now();
    }

    public String getCurrentSummary() {
        return currentSummary;
    }

    public void setCurrentSummary(String currentSummary) {
        this.currentSummary = currentSummary;
        this.updatedAt = Instant.now();
    }

    public String getIssueType() {
        return issueType;
    }

    public void setIssueType(String issueType) {
        this.issueType = issueType;
        this.updatedAt = Instant.now();
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
        this.updatedAt = Instant.now();
    }

    public String getGapAnalysisJson() {
        return gapAnalysisJson;
    }

    public void setGapAnalysisJson(String gapAnalysisJson) {
        this.gapAnalysisJson = gapAnalysisJson;
        this.updatedAt = Instant.now();
    }

    public String getAgentPlanJson() {
        return agentPlanJson;
    }

    public void setAgentPlanJson(String agentPlanJson) {
        this.agentPlanJson = agentPlanJson;
        this.updatedAt = Instant.now();
    }

    public String getToolSelectionJson() {
        return toolSelectionJson;
    }

    public void setToolSelectionJson(String toolSelectionJson) {
        this.toolSelectionJson = toolSelectionJson;
        this.updatedAt = Instant.now();
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        this.updatedAt = Instant.now();
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
        this.updatedAt = Instant.now();
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }
}
