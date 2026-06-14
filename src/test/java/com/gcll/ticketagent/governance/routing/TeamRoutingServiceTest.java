package com.gcll.ticketagent.governance.routing;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeamRoutingServiceTest {

    private final TeamRoutingService service = new TeamRoutingService();

    @Test
    void paymentSystemRoutesToPaymentTeam() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", "支付回调", "/pay/callback", "500", null,
                "生产", "多个用户", null, null,
                List.of(), 0.9
        );

        RoutingResult result = service.route(extract, List.of());

        assertThat(result.primaryTeam()).isEqualTo("支付研发组");
        assertThat(result.backupTeams()).contains("订单研发组");
    }
}
