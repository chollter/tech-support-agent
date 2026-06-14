package com.gcll.ticketagent.governance.human;

import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.api.BusinessException;
import com.gcll.ticketagent.api.ErrorCode;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.human.PendingAction;
import com.gcll.ticketagent.human.PendingActionStatus;
import com.gcll.ticketagent.human.PendingActionType;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.persistence.repository.PendingActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class HumanConfirmService {

    private final PendingActionRepository pendingActionRepository;
    private final AgentRunRepository agentRunRepository;
    private final AuditLogService auditLogService;

    public HumanConfirmService(PendingActionRepository pendingActionRepository,
                               AgentRunRepository agentRunRepository,
                               AuditLogService auditLogService) {
        this.pendingActionRepository = pendingActionRepository;
        this.agentRunRepository = agentRunRepository;
        this.auditLogService = auditLogService;
    }

    public PendingAction createDispatchAction(AgentRun run, String payload, String reason) {
        PendingAction action = new PendingAction(
                UUID.randomUUID().toString(),
                run.getId(),
                PendingActionType.DISPATCH,
                payload,
                reason
        );
        return pendingActionRepository.save(action);
    }

    public List<PendingAction> pending() {
        return pendingActionRepository.findPending();
    }

    @Transactional
    public AgentRun confirm(String actionId, String confirmedBy, PendingActionType actionType) {
        PendingAction action = pendingActionRepository.findById(actionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND, "Pending action not found: " + actionId));
        if (action.getStatus() != PendingActionStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "Pending action already decided.");
        }
        action.confirm(confirmedBy);
        pendingActionRepository.save(action);

        AgentRun run = agentRunRepository.findById(action.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found"));
        run.setStatus(AgentRunStatus.FINAL);
        agentRunRepository.save(run);

        auditLogService.recordStep(run, AgentStepName.FINAL, action.getPayload(),
                "confirmed by " + confirmedBy, false, null, 0, null);
        return run;
    }

    @Transactional
    public AgentRun reject(String actionId, String confirmedBy) {
        PendingAction action = pendingActionRepository.findById(actionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND, "Pending action not found: " + actionId));
        if (action.getStatus() != PendingActionStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "Pending action already decided.");
        }
        action.reject(confirmedBy);
        pendingActionRepository.save(action);

        AgentRun run = agentRunRepository.findById(action.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found"));
        run.setStatus(AgentRunStatus.ESCALATED);
        agentRunRepository.save(run);

        auditLogService.recordStep(run, AgentStepName.FINAL, action.getPayload(),
                "rejected by " + confirmedBy + ", escalated", false, null, 0, null);
        return run;
    }
}
