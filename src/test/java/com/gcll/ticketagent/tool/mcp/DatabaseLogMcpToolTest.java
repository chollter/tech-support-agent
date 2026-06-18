package com.gcll.ticketagent.tool.mcp;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DatabaseLogMcpTool} client 侧单测（mock McpSyncClient，不依赖真 server）。
 * <p>覆盖：正常调用（解析 TextContent）、MCP disabled 降级、调用异常。
 */
class DatabaseLogMcpToolTest {

    private final McpSyncClient mcpClient = mock(McpSyncClient.class);

    private TicketExtractResult sampleExtract() {
        return new TicketExtractResult(
                IssueType.INCIDENT, "payment", "callback", "/pay/callback",
                "500", "Internal Server Error", "production", "multiple users",
                "10:00-10:30", "payment failures", List.of("500", "callback"), 0.9
        );
    }

    @Test
    void executeCallsMcpAndParsesTextContent() {
        when(mcpClient.callTool(any(CallToolRequest.class))).thenReturn(
                new CallToolResult(List.of(new TextContent("OOMKilled memory exceeded")), false));
        DatabaseLogMcpTool tool = new DatabaseLogMcpTool(Optional.of(mcpClient));

        ToolResult result = tool.execute(sampleExtract(), "内存溢出");

        assertThat(result.success()).isTrue();
        assertThat(result.toolType()).isEqualTo(ToolType.MCP);
        assertThat(result.toolName()).isEqualTo("query_logs");
        assertThat(result.output()).contains("memory exceeded");

        // 验证传给 MCP server 的参数正确
        ArgumentCaptor<CallToolRequest> captor = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(mcpClient).callTool(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("query_logs");
        assertThat(captor.getValue().arguments()).containsEntry("systemName", "payment");
        assertThat(captor.getValue().arguments()).containsEntry("moduleName", "callback");
    }

    @Test
    void degradesWhenMcpDisabled() {
        // MCP 关闭：Optional.empty()，工具仍执行但返回降级结果
        DatabaseLogMcpTool tool = new DatabaseLogMcpTool(Optional.empty());

        ToolResult result = tool.execute(sampleExtract(), "内存溢出");

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("mcp disabled");
    }

    @Test
    void returnsFailureWhenMcpCallThrows() {
        when(mcpClient.callTool(any(CallToolRequest.class))).thenThrow(new RuntimeException("server down"));
        DatabaseLogMcpTool tool = new DatabaseLogMcpTool(Optional.of(mcpClient));

        ToolResult result = tool.execute(sampleExtract(), "内存溢出");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("server down");
    }

    @Test
    void returnsFailureWhenMcpReportsError() {
        when(mcpClient.callTool(any(CallToolRequest.class))).thenReturn(
                new CallToolResult(List.of(new TextContent("invalid args")), true));
        DatabaseLogMcpTool tool = new DatabaseLogMcpTool(Optional.of(mcpClient));

        ToolResult result = tool.execute(sampleExtract(), "内存溢出");

        assertThat(result.success()).isFalse();
    }
}
