package com.gcll.ticketagent.ticket;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TicketInputProcessorTest {

    @Test
    void shortContentPassesThroughUnchanged() {
        TicketInputProcessor processor = new TicketInputProcessor(8000);
        String result = processor.buildContent("title", "short content", null);

        assertThat(result).isEqualTo("title\nshort content");
    }

    @Test
    void contentExceedingMaxIsTruncatedWithMarker() {
        TicketInputProcessor processor = new TicketInputProcessor(50);
        String longContent = "A".repeat(200);

        ProcessedInput processed = processor.process(null, longContent, null);

        assertThat(processed.content()).endsWith(TicketInputProcessor.TRUNCATION_MARKER);
        assertThat(processed.preprocessSummary()).contains("truncated=true");
    }

    @Test
    void truncationPreservesTitleAndMetadataWhenWithinLimit() {
        TicketInputProcessor processor = new TicketInputProcessor(200);
        String longContent = "B".repeat(100);

        ProcessedInput processed = processor.process("my title", longContent, Map.of("env", "prod"));

        assertThat(processed.content()).startsWith("my title");
        assertThat(processed.content()).contains("env: prod");
        assertThat(processed.content()).contains("BBBB");
    }

    @Test
    void nullContentHandledGracefully() {
        TicketInputProcessor processor = new TicketInputProcessor(8000);

        String result = processor.buildContent(null, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void sensitiveWordsAreMasked() {
        TicketInputProcessor processor = new TicketInputProcessor(8000, List.of("password", "token"));

        ProcessedInput processed = processor.process(null, "user password=secret token=abc", null);

        assertThat(processed.content()).doesNotContain("password");
        assertThat(processed.content()).doesNotContain("token");
        assertThat(processed.preprocessSummary()).contains("sensitiveMasked=true");
    }
}
