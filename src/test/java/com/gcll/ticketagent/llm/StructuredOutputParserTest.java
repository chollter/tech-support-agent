package com.gcll.ticketagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredOutputParserTest {

    private final StructuredOutputParser parser = new StructuredOutputParser(new ObjectMapper());

    @Test
    void parsesJsonWithCodeFence() {
        String json = """
                ```json
                {"issueType":"INCIDENT","confidence":0.8}
                ```
                """;
        TestJson result = parser.parse(json, TestJson.class);
        assertThat(result.issueType()).isEqualTo("INCIDENT");
        assertThat(result.confidence()).isEqualTo(0.8);
    }

    private record TestJson(String issueType, double confidence) {
    }
}
