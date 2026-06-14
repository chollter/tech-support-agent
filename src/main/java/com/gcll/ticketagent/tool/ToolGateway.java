package com.gcll.ticketagent.tool;

import com.gcll.ticketagent.extract.TicketExtractResult;

import java.util.List;

public interface ToolGateway {

    ToolType toolType();

    String toolName();

    ToolResult execute(TicketExtractResult extract, String originalContent);

    default ToolDescriptor descriptor() {
        return new ToolDescriptor(toolName(), toolType(), toolName());
    }
}
