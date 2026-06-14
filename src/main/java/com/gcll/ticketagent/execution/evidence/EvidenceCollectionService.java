package com.gcll.ticketagent.execution.evidence;

import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.persistence.repository.ToolExecutionLogRepository;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolRegistry;
import com.gcll.ticketagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EvidenceCollectionService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCollectionService.class);

    private final ToolRegistry toolRegistry;
    private final ToolExecutionLogRepository toolExecutionLogRepository;

    public EvidenceCollectionService(
            ToolRegistry toolRegistry,
            ToolExecutionLogRepository toolExecutionLogRepository
    ) {
        this.toolRegistry = toolRegistry;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
    }

    public List<ToolResult> collect(
            String runId,
            TicketExtractResult extract,
            String originalContent,
            ToolSelection selection
    ) {
        List<ToolResult> results = new ArrayList<>();
        if (selection == null || selection.selectedToolNames() == null || selection.selectedToolNames().isEmpty()) {
            log.info("No tools selected for evidence collection, runId={}", runId);
            return results;
        }

        for (String toolName : selection.selectedToolNames()) {
            ToolGateway tool = toolRegistry.find(toolName).orElse(null);
            if (tool == null) {
                log.warn("Selected tool not registered, runId={}, toolName={}", runId, toolName);
                continue;
            }
            try {
                ToolResult result = tool.execute(extract, originalContent);
                results.add(result);
                toolExecutionLogRepository.save(runId, "EVIDENCE_COLLECTION", result);
                if (result.success()) {
                    log.info("Tool [{}] executed successfully, durationMs={}", tool.toolName(), result.durationMs());
                } else {
                    log.warn("Tool [{}] failed: {}", tool.toolName(), result.errorMessage());
                }
            } catch (Exception ex) {
                log.warn("Tool [{}] threw exception: {}", toolName, ex.getMessage());
                ToolResult failure = ToolResult.failure(
                        tool.toolType(), tool.toolName(), originalContent,
                        ex.getMessage(), 0
                );
                results.add(failure);
                toolExecutionLogRepository.save(runId, "EVIDENCE_COLLECTION", failure);
            }
        }

        return results;
    }
}
