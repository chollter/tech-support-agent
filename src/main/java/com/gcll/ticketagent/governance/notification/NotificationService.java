package com.gcll.ticketagent.governance.notification;

/**
 * 分派通知防腐层端口。默认实现 {@link LogNotificationService}（日志 + 指标）。
 * 将来接入 IM / 工单系统时新增实现并以 @Primary 或配置类切换，接口不变。
 */
public interface NotificationService {

    NotificationResult send(NotificationRequest request);

    String channel();
}
