package com.gcll.ticketagent.governance.human;

import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.api.BusinessException;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.governance.notification.NotificationRequest;
import com.gcll.ticketagent.governance.notification.NotificationResult;
import com.gcll.ticketagent.governance.notification.NotificationService;
import com.gcll.ticketagent.human.PendingAction;
import com.gcll.ticketagent.human.PendingActionType;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.persistence.repository.PendingActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HumanConfirmServiceTest {

    private PendingActionRepository pendingActionRepository;
    private AgentRunRepository agentRunRepository;
    private AuditLogService auditLogService;
    private NotificationService notificationService;
    private HumanConfirmService service;

    @BeforeEach
    void setUp() {
        pendingActionRepository = mock(PendingActionRepository.class);
        agentRunRepository = mock(AgentRunRepository.class);
        auditLogService = mock(AuditLogService.class);
        notificationService = mock(NotificationService.class);
        service = new HumanConfirmService(
                pendingActionRepository, agentRunRepository, auditLogService, notificationService);
    }

    private PendingAction dispatchAction() {
        return new PendingAction("a-1", "run-1", PendingActionType.DISPATCH,
                "payload", "reason", "支付研发组");
    }

    private AgentRun run() {
        AgentRun run = new AgentRun("run-1", "trace-1", "sess-1", "u-1", "content");
        run.setPriority("P1");
        return run;
    }

    @Test
    void confirmDispatchNotifiesTargetTeamOnce() {
        PendingAction action = dispatchAction();
        when(pendingActionRepository.findById("a-1")).thenReturn(Optional.of(action));
        when(pendingActionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRunRepository.findById("run-1")).thenReturn(Optional.of(run()));
        when(notificationService.send(any())).thenReturn(NotificationResult.ok("log:a-1"));

        service.confirm("a-1", "tester", PendingActionType.DISPATCH);

        ArgumentCaptor<NotificationRequest> req = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, times(1)).send(req.capture());
        assertThat(req.getValue().targetTeam()).isEqualTo("支付研发组");
        assertThat(req.getValue().traceId()).isEqualTo("trace-1");

        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).recordStep(any(), any(), any(), output.capture(),
                anyBoolean(), any(), anyLong(), any());
        assertThat(output.getValue()).contains("dispatched to 支付研发组");
    }

    @Test
    void secondConfirmIsRejectedAndDoesNotNotifyAgain() {
        PendingAction action = dispatchAction();
        when(pendingActionRepository.findById("a-1")).thenReturn(Optional.of(action));
        when(pendingActionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRunRepository.findById("run-1")).thenReturn(Optional.of(run()));
        when(notificationService.send(any())).thenReturn(NotificationResult.ok("log:a-1"));

        service.confirm("a-1", "tester", PendingActionType.DISPATCH);
        assertThatThrownBy(() -> service.confirm("a-1", "tester", PendingActionType.DISPATCH))
                .isInstanceOf(BusinessException.class);

        verify(notificationService, times(1)).send(any());
    }

    @Test
    void confirmStillFinalWhenNotificationThrows() {
        PendingAction action = dispatchAction();
        when(pendingActionRepository.findById("a-1")).thenReturn(Optional.of(action));
        when(pendingActionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(agentRunRepository.findById("run-1")).thenReturn(Optional.of(run()));
        when(notificationService.send(any())).thenThrow(new RuntimeException("boom"));

        AgentRun result = service.confirm("a-1", "tester", PendingActionType.DISPATCH);

        assertThat(result.getStatus()).isEqualTo(AgentRunStatus.FINAL);
        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).recordStep(any(), any(), any(), output.capture(),
                anyBoolean(), any(), anyLong(), error.capture());
        assertThat(output.getValue()).contains("notification failed");
        assertThat(error.getValue()).isNotNull();
    }

    @Test
    void rejectDoesNotNotify() {
        PendingAction action = dispatchAction();
        when(pendingActionRepository.findById("a-1")).thenReturn(Optional.of(action));
        when(agentRunRepository.findById("run-1")).thenReturn(Optional.of(run()));

        service.reject("a-1", "tester");

        verifyNoInteractions(notificationService);
    }
}
