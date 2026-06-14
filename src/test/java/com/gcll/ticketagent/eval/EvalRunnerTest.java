package com.gcll.ticketagent.eval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EvalRunnerTest {

    @Autowired
    private EvalRunner evalRunner;

    @Test
    void allIncidentCasesPass() {
        EvalReport report = evalRunner.run();
        assertThat(report.failures()).isEmpty();
        assertThat(report.passed()).isEqualTo(report.total());
    }
}
