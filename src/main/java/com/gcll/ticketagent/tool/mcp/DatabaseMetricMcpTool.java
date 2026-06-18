package com.gcll.ticketagent.tool.mcp;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运维指标查询工具，通过真 MCP server（ops-mcp-server）执行。
 * <p>与 {@link DatabaseLogMcpTool} 同构，经 {@link McpSyncClient#callTool} 调用 STDIO MCP server。
 * <p>降级：MCP client 关闭时仍注册但返回"无证据"。
 */
@Component
public class DatabaseMetricMcpTool implements ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMetricMcpTool.class);
    private static final String TOOL_NAME = "query_metric";
    private static final String NO_EVIDENCE = "no metric evidence found (mcp disabled)";

    private final Optional<McpSyncClient> mcpClient;

    public DatabaseMetricMcpTool(Optional<McpSyncClient> mcpClient) {
        this.mcpClient = mcpClient;
    }

    @Override
    public ToolType toolType() {
        return ToolType.MCP;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(TicketExtractResult extract, String originalContent) {
        long start = System.currentTimeMillis();
        String input = "system=" + extract.affectedSystem()
                + ",module=" + extract.affectedModule()
                + ",query=" + originalContent;
        if (mcpClient.isEmpty()) {
            return ToolResult.success(ToolType.MCP, TOOL_NAME, input, NO_EVIDENCE, System.currentTimeMillis() - start);
        }
        Map<String, Object> arguments = Map.of(
                "systemName", extract.affectedSystem(),
                "moduleName", extract.affectedModule(),
                "query", originalContent
        );
        try {
            CallToolResult result = mcpClient.get().callTool(new CallToolRequest(TOOL_NAME, arguments));
            String output = extractText(result);
            if (Boolean.TRUE.equals(result.isError())) {
                return ToolResult.failure(ToolType.MCP, TOOL_NAME, input, output, System.currentTimeMillis() - start);
            }
            return ToolResult.success(ToolType.MCP, TOOL_NAME, input, output, System.currentTimeMillis() - start);
        } catch (Exception ex) {
            log.warn("MCP query_metric failed, error={}", ex.getMessage());
            return ToolResult.failure(ToolType.MCP, TOOL_NAME, input,
                    "mcp call failed: " + ex.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private String extractText(CallToolResult result) {
        List<Content> contents = result.content();
        if (contents == null || contents.isEmpty()) {
            return "no metric evidence found";
        }
        StringBuilder sb = new StringBuilder();
        for (Content content : contents) {
            if (content instanceof TextContent text) {
                sb.append(text.text());
            }
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? "no metric evidence found" : text;
    }
}
