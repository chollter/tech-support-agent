package com.gcll.ticketagent.governance.notification;

/**
 * 分派通知结果。
 */
public record NotificationResult(
        boolean success,
        String messageId,
        String errorType
) {

    public static NotificationResult ok(String messageId) {
        return new NotificationResult(true, messageId, null);
    }

    public static NotificationResult fail(String errorType) {
        return new NotificationResult(false, null, errorType);
    }
}
