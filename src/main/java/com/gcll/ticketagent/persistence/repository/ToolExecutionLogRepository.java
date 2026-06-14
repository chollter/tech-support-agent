package com.gcll.ticketagent.persistence.repository;

import com.gcll.ticketagent.tool.ToolResult;

import java.util.List;

public interface ToolExecutionLogRepository {
    void save(String runId, String stepName, ToolResult result);

    List<ToolResult> findByRunId(String runId);
}
