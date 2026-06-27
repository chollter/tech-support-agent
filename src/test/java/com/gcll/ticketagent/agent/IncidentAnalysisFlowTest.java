package com.gcll.ticketagent.agent;

import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.SubmitAgentRunRequest;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.governance.human.HumanConfirmService;
import com.gcll.ticketagent.human.PendingAction;
import com.gcll.ticketagent.human.PendingActionType;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.persistence.repository.PendingActionRepository;
import com.gcll.ticketagent.ticket.TicketApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
class IncidentAnalysisFlowTest extends com.gcll.ticketagent.testsupport.RedisIsolatedTest {

    @Autowired
    private TicketApplicationService ticketApplicationService;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private HumanConfirmService humanConfirmService;

    @Autowired
    private PendingActionRepository pendingActionRepository;

    @Test
    void incompleteTicketReturnsNeedMoreInfo() {
        var response = ticketApplicationService.submit(new SubmitAgentRunRequest(
                "sess-001",
                "u-1001",
                "",
                "接口报错了",
                "WEB",
                null,
                null
        ));

        assertThat(response.replyType()).isEqualTo(ReplyType.NEED_MORE_INFO);
        assertThat(response.status()).isEqualTo(AgentRunStatus.WAIT_USER_INPUT);
        assertThat(response.questions()).isNotEmpty();
        assertThat(response.questions().size()).isLessThanOrEqualTo(6);
        assertThat(response.analysis()).isNull();
    }

    @Test
    void completePaymentIncidentReturnsP1Analysis() {
        var response = ticketApplicationService.submit(new SubmitAgentRunRequest(
                "sess-002",
                "u-1001",
                "",
                "生产环境，支付系统的 /pay/callback 接口 500，从上午 10 点开始，多个用户支付成功但订单状态还是待支付。",
                "WEB",
                null,
                null
        ));

        assertThat(response.replyType()).isEqualTo(ReplyType.TICKET_ANALYSIS_RESULT);
        assertThat(response.analysis()).isNotNull();
        assertThat(response.analysis().ticket().priority()).isEqualTo("P1");
        assertThat(response.analysis().routing().primaryTeam()).isEqualTo("支付研发组");
        assertThat(response.analysis().humanConfirm().required()).isTrue();
        assertThat(response.status()).isEqualTo(AgentRunStatus.WAIT_HUMAN_CONFIRM);

        AgentRun run = agentRunRepository.findById(response.runId()).orElseThrow();
        var stepNames = run.getSteps().stream().map(s -> s.getStepName()).toList();
        assertThat(stepNames).contains("EVIDENCE_COLLECTION");
    }

    @Test
    void supplementAfterIncompleteProceedsToAnalysis() {
        var incomplete = ticketApplicationService.submit(new SubmitAgentRunRequest(
                "sess-sup-1",
                "u-1001",
                "",
                "接口报错了",
                "WEB",
                null,
                null
        ));
        assertThat(incomplete.status()).isEqualTo(AgentRunStatus.WAIT_USER_INPUT);

        var complete = ticketApplicationService.supplement(
                incomplete.runId(),
                new com.gcll.ticketagent.api.dto.SupplementMessageRequest(
                        "生产环境，支付系统的 /pay/callback 接口 500，从上午 10 点开始，多个用户支付成功但订单状态还是待支付。",
                        null
                )
        );
        assertThat(complete.replyType()).isEqualTo(ReplyType.TICKET_ANALYSIS_RESULT);
        assertThat(complete.analysis()).isNotNull();
    }

    @Test
    void confirmedDispatchNotifiesTargetTeam() {
        var response = ticketApplicationService.submit(new SubmitAgentRunRequest(
                "sess-disp-1",
                "u-1001",
                "",
                "生产环境，支付系统的 /pay/callback 接口 500，从上午 10 点开始，多个用户支付成功但订单状态还是待支付。",
                "WEB",
                null,
                null
        ));
        assertThat(response.status()).isEqualTo(AgentRunStatus.WAIT_HUMAN_CONFIRM);

        PendingAction action = pendingActionRepository.findPending().stream()
                .filter(a -> a.getRunId().equals(response.runId()))
                .findFirst()
                .orElseThrow();
        assertThat(action.getTargetTeam()).isEqualTo("支付研发组");

        AgentRun run = humanConfirmService.confirm(action.getId(), "tester", PendingActionType.DISPATCH);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FINAL);

        AgentRun refreshed = agentRunRepository.findById(response.runId()).orElseThrow();
        String lastFinalOutput = refreshed.getSteps().stream()
                .filter(s -> "FINAL".equals(s.getStepName()))
                .reduce((first, second) -> second)
                .map(AgentStep::getOutputSnapshot)
                .orElseThrow();
        assertThat(lastFinalOutput).contains("dispatched to 支付研发组");
    }
}
