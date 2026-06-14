package com.gcll.ticketagent.governance.human;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.routing.RoutingResult;
import org.springframework.stereotype.Component;

@Component
public class HumanConfirmTrigger {

    public boolean needHumanConfirm(PriorityResult priority, RoutingResult routing, TicketExtractResult extract) {
        if (priority != null && priority.needHumanConfirm()) {
            return true;
        }
        if (routing != null && routing.confidence() < 0.7) {
            return true;
        }
        if (extract != null && extract.confidence() < 0.5) {
            return true;
        }
        return false;
    }

    public String reason(PriorityResult priority, RoutingResult routing, TicketExtractResult extract) {
        if (priority != null && priority.needHumanConfirm()) {
            return priority.priority() + " 级生产故障需要人工确认后分派";
        }
        if (routing != null && routing.confidence() < 0.7) {
            return "团队路由置信度较低，需要人工确认";
        }
        if (extract != null && extract.confidence() < 0.5) {
            return "LLM 抽取置信度较低，需要人工确认";
        }
        return "需要人工确认";
    }
}
