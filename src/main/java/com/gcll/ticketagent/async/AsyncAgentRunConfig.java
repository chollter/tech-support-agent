package com.gcll.ticketagent.async;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AsyncAgentRunProperties.class)
public class AsyncAgentRunConfig {
}
