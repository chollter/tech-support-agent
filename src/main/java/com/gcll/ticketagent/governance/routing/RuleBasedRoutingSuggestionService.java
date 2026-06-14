package com.gcll.ticketagent.governance.routing;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.llm.StepOutcome;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RuleBasedRoutingSuggestionService implements RoutingSuggestionService {

    @Override
    public StepOutcome<RoutingSuggestion> suggest(
            String userContent,
            TicketExtractResult extract,
            List<KnowledgeHit> hits,
            RoutingResult ruleBaseline
    ) {
        return StepOutcome.ruleBased(RoutingSuggestion.empty());
    }
}
