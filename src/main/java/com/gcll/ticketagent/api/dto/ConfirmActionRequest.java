package com.gcll.ticketagent.api.dto;

import com.gcll.ticketagent.human.PendingActionType;

public record ConfirmActionRequest(
        String confirmedBy,
        PendingActionType actionType
) {
}
