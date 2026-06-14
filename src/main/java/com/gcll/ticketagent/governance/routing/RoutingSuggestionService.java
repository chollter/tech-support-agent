package com.gcll.ticketagent.governance.routing;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.llm.StepOutcome;

import java.util.List;

public interface RoutingSuggestionService {

    StepOutcome<RoutingSuggestion> suggest(
            String userContent,
            TicketExtractResult extract,
            List<KnowledgeHit> hits,
            RoutingResult ruleBaseline
    );
}
