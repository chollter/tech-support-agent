package com.gcll.ticketagent.async;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.async")
public class AsyncAgentRunProperties {

    private boolean enabled;
    private String topic = "opsmind.agent-run.execute";
    private long stuckScanIntervalMs = 300_000;
    private long stuckRunningTimeoutMinutes = 15;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public long getStuckScanIntervalMs() {
        return stuckScanIntervalMs;
    }

    public void setStuckScanIntervalMs(long stuckScanIntervalMs) {
        this.stuckScanIntervalMs = stuckScanIntervalMs;
    }

    public long getStuckRunningTimeoutMinutes() {
        return stuckRunningTimeoutMinutes;
    }

    public void setStuckRunningTimeoutMinutes(long stuckRunningTimeoutMinutes) {
        this.stuckRunningTimeoutMinutes = stuckRunningTimeoutMinutes;
    }
}
