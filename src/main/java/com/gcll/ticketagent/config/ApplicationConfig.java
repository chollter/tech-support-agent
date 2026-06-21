package com.gcll.ticketagent.config;

import com.gcll.ticketagent.knowledge.KnowledgeProperties;
import com.gcll.ticketagent.knowledge.RrfProperties;
import com.gcll.ticketagent.ticket.TicketInputProcessor;
import com.gcll.ticketagent.ticket.TicketInputProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableConfigurationProperties({KnowledgeProperties.class, TicketInputProperties.class, RrfProperties.class})
@EnableTransactionManagement
public class ApplicationConfig {

    @Bean
    public TicketInputProcessor ticketInputProcessor(TicketInputProperties properties) {
        return new TicketInputProcessor(properties.getMaxContentLength(), properties.getSensitiveWords());
    }
}
