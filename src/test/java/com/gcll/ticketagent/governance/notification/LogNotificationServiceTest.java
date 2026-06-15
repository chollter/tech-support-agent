package com.gcll.ticketagent.governance.notification;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogNotificationServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private NotificationRequest request() {
        return new NotificationRequest("a-1", "run-1", "支付研发组", "P1", "tester", "trace-1", "payload");
    }

    @Test
    void sendReturnsOkAndIncrementsCounter() {
        LogNotificationService service = new LogNotificationService(registry);

        NotificationResult result = service.send(request());

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("log:a-1");
        assertThat(service.channel()).isEqualTo("log");
        assertThat(registry.counter("dispatch_notification_total",
                "channel", "log", "result", "ok").count()).isEqualTo(1.0);
    }
}
