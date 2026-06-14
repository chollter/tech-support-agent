package com.gcll.ticketagent.governance.priority;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityEvaluationServiceTest {

    private final PriorityEvaluationService service = new PriorityEvaluationService();

    @Test
    void productionCoreMultiUserIsP1() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", "支付回调", "/pay/callback", "500", null,
                "生产", "多个用户", null, null,
                List.of("CORE_PAYMENT"), 0.9
        );

        PriorityResult result = service.evaluate(extract);

        assertThat(result.priority()).isEqualTo(TicketPriority.P1);
        assertThat(result.needHumanConfirm()).isTrue();
    }
}
