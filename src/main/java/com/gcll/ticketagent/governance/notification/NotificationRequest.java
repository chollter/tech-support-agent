package com.gcll.ticketagent.governance.notification;

/**
 * 分派通知请求（不可变）。pendingActionId 同时作为幂等键。
 */
public record NotificationRequest(
        String pendingActionId,
        String runId,
        String targetTeam,
        String priority,
        String confirmedBy,
        String traceId,
        String content
) {
}
