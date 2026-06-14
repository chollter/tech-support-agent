package com.gcll.ticketagent.governance.priority;

import java.util.List;

public record PriorityResult(
        TicketPriority priority,
        List<String> reasonCodes,
        String explanation,
        boolean needEscalation,
        boolean needHumanConfirm
) {
}
