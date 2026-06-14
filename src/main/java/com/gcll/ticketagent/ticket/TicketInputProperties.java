package com.gcll.ticketagent.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "opsmind.input")
public class TicketInputProperties {

    private int maxContentLength = 8000;
    private List<String> sensitiveWords = new ArrayList<>(List.of(
            "password", "passwd", "token", "secret", "apikey", "api_key",
            "密码", "密钥", "口令"
    ));

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(int maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public List<String> getSensitiveWords() {
        return sensitiveWords;
    }

    public void setSensitiveWords(List<String> sensitiveWords) {
        this.sensitiveWords = sensitiveWords;
    }
}
