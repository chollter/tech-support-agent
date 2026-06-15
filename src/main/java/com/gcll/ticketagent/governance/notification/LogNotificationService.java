package com.gcll.ticketagent.governance.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认通知适配器：把分派通知写入结构化日志并埋点 {@code dispatch_notification_total{channel,result}}。
 * <p>日志几乎不会失败；此处 try/catch 仅作防御性兜底，使失败计数可达。
 */
@Component
public class LogNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationService.class);
    private static final String CHANNEL = "log";

    private final Counter okCounter;
    private final Counter failedCounter;

    public LogNotificationService(MeterRegistry meterRegistry) {
        this.okCounter = Counter.builder("dispatch_notification_total")
                .tag("channel", CHANNEL).tag("result", "ok")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("dispatch_notification_total")
                .tag("channel", CHANNEL).tag("result", "failed")
                .register(meterRegistry);
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public NotificationResult send(NotificationRequest request) {
        try {
            log.info("dispatch notification: runId={}, targetTeam={}, priority={}, confirmedBy={}, traceId={}",
                    request.runId(), request.targetTeam(), request.priority(),
                    request.confirmedBy(), request.traceId());
            okCounter.increment();
            return NotificationResult.ok(CHANNEL + ":" + request.pendingActionId());
        } catch (Throwable t) {
            failedCounter.increment();
            return NotificationResult.fail(t.getClass().getSimpleName());
        }
    }
}
