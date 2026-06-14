package com.gcll.ticketagent.tool.mcp;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.persistence.entity.OpsMetricSampleEntity;
import com.gcll.ticketagent.persistence.repository.OpsSampleRepository;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class DatabaseMetricMcpTool implements ToolGateway {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpsSampleRepository opsSampleRepository;

    public DatabaseMetricMcpTool(OpsSampleRepository opsSampleRepository) {
        this.opsSampleRepository = opsSampleRepository;
    }

    @Override
    public ToolType toolType() {
        return ToolType.MCP;
    }

    @Override
    public String toolName() {
        return "query_metric";
    }

    @Override
    public ToolResult execute(TicketExtractResult extract, String originalContent) {
        long start = System.currentTimeMillis();
        String input = "system=" + extract.affectedSystem()
                + ",module=" + extract.affectedModule()
                + ",query=" + originalContent;
        List<OpsMetricSampleEntity> metrics = opsSampleRepository.searchMetrics(
                extract.affectedSystem(),
                extract.affectedModule(),
                originalContent,
                8
        );
        String output = metrics.isEmpty()
                ? "no metric evidence found"
                : formatMetrics(metrics);
        return ToolResult.success(ToolType.MCP, toolName(), input, output, System.currentTimeMillis() - start);
    }

    private String formatMetrics(List<OpsMetricSampleEntity> metrics) {
        StringBuilder sb = new StringBuilder();
        for (OpsMetricSampleEntity metric : metrics) {
            sb.append('[').append(FORMATTER.format(metric.getOccurredAt())).append("] ")
                    .append(metric.getSystemName()).append('/')
                    .append(metric.getServiceName()).append(' ')
                    .append(metric.getMetricName()).append('=')
                    .append(metric.getMetricValue())
                    .append(metric.getUnit() == null ? "" : metric.getUnit())
                    .append(" status=").append(metric.getStatus())
                    .append(" labels=").append(metric.getLabels())
                    .append('\n');
        }
        return sb.toString().trim();
    }
}
