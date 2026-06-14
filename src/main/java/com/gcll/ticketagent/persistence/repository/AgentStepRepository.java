package com.gcll.ticketagent.persistence.repository;

import com.gcll.ticketagent.domain.AgentStep;

import java.util.List;

public interface AgentStepRepository {
    void save(AgentStep step);

    List<AgentStep> findByRunId(String runId);
}
