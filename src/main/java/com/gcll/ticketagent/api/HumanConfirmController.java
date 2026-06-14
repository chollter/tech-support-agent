package com.gcll.ticketagent.api;

import com.gcll.ticketagent.api.dto.ConfirmActionRequest;
import com.gcll.ticketagent.api.dto.PendingActionResponse;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.governance.human.HumanConfirmService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/human/pending")
public class HumanConfirmController {

    private final HumanConfirmService humanConfirmService;

    public HumanConfirmController(HumanConfirmService humanConfirmService) {
        this.humanConfirmService = humanConfirmService;
    }

    @GetMapping
    public List<PendingActionResponse> pending() {
        return humanConfirmService.pending().stream()
                .map(action -> new PendingActionResponse(
                        action.getId(),
                        action.getRunId(),
                        action.getActionType().name(),
                        action.getStatus().name(),
                        action.getReason()
                ))
                .toList();
    }

    @PostMapping("/{id}/confirm")
    public AgentRun confirm(@PathVariable String id, @RequestBody ConfirmActionRequest request) {
        return humanConfirmService.confirm(id, request.confirmedBy(), request.actionType());
    }

    @PostMapping("/{id}/reject")
    public AgentRun reject(@PathVariable String id, @RequestBody ConfirmActionRequest request) {
        return humanConfirmService.reject(id, request.confirmedBy());
    }
}
