package com.gcll.ticketagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(TicketAgentApplication.class, args);
    }
}
