package com.gcll.ticketagent.tool.mcp;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.persistence.entity.OpsLogSampleEntity;
import com.gcll.ticketagent.persistence.repository.OpsSampleRepository;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class DatabaseLogMcpTool implements ToolGateway {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpsSampleRepository opsSampleRepository;

    public DatabaseLogMcpTool(OpsSampleRepository opsSampleRepository) {
        this.opsSampleRepository = opsSampleRepository;
    }

    @Override
    public ToolType toolType() {
        return ToolType.MCP;
    }

    @Override
    public String toolName() {
        return "query_logs";
    }

    @Override
    public ToolResult execute(TicketExtractResult extract, String originalContent) {
        long start = System.currentTimeMillis();
        String input = "system=" + extract.affectedSystem()
                + ",module=" + extract.affectedModule()
                + ",query=" + originalContent;
        List<OpsLogSampleEntity> logs = opsSampleRepository.searchLogs(
                extract.affectedSystem(),
                extract.affectedModule(),
                originalContent,
                5
        );
        String output = logs.isEmpty()
                ? "no log evidence found"
                : formatLogs(logs);
        return ToolResult.success(ToolType.MCP, toolName(), input, output, System.currentTimeMillis() - start);
    }

    private String formatLogs(List<OpsLogSampleEntity> logs) {
        StringBuilder sb = new StringBuilder();
        for (OpsLogSampleEntity log : logs) {
            sb.append('[').append(FORMATTER.format(log.getOccurredAt())).append("] ")
                    .append(log.getLevel()).append(' ')
                    .append(log.getSystemName()).append('/')
                    .append(log.getServiceName()).append(' ')
                    .append("traceId=").append(log.getTraceId()).append(' ')
                    .append(log.getMessage()).append('\n');
        }
        return sb.toString().trim();
    }
}
